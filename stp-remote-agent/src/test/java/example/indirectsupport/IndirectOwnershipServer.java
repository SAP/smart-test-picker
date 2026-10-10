// SPDX-License-Identifier: Apache-2.0
package example.indirectsupport;

import com.fasterxml.jackson.databind.ObjectMapper;
import example.indirect.*;
import io.opentelemetry.context.*;
import jakarta.servlet.http.*;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.servlet.*;
import java.lang.invoke.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** HTTP requests prepare handles, submit work, and release/complete it in separate dispatches. */
public final class IndirectOwnershipServer {
    static final ObjectMapper JSON = new ObjectMapper();
    static final IndirectWork.MeasuredExecutor WORKER = new IndirectWork.MeasuredExecutor(1);
    static final IndirectWork.MeasuredExecutor PARALLEL = new IndirectWork.MeasuredExecutor(2);
    static final IndirectWork.MeasuredScheduler TIMER = new IndirectWork.MeasuredScheduler();
    static final Map<String, State> STATES = new ConcurrentHashMap<>();
    static final Map<String, CyclicBarrier> BARRIERS = new ConcurrentHashMap<>();
    static final Map<String, CompletableFuture<String>> SOURCES = new ConcurrentHashMap<>();
    static final Map<String, CountDownLatch> BLOCKERS = new ConcurrentHashMap<>();
    static final class State {
        String route, shape, scope;
        Object receiver;
        Object[] args;
        Method method;
        MethodHandle handle;
        IndirectWork work;
        CompletableFuture<String> source = new CompletableFuture<>(), other = new CompletableFuture<>();
        volatile Object result;
        volatile Throwable invocationFailure;
        volatile Map<String, Object> owner;
        volatile String uncaught, uncaughtIdentity;
        CountDownLatch submitted = new CountDownLatch(1), invoking = new CountDownLatch(1);
        boolean periodic() { return shape.startsWith("fixed"); }
        boolean bulk() { return Set.of("all", "allTimed", "any", "anyTimed").contains(shape); }
        boolean cf() { return Set.of("apply", "applyAsync", "combine").contains(shape); }
    }
    public static void main(String[] args) throws Exception {
        IndirectMethods.a(); IndirectMethods.b(); IndirectMethods.after();
        WORKER.prestartAllCoreThreads(); PARALLEL.prestartAllCoreThreads(); TIMER.prestartAllCoreThreads();
        Server server = new Server(0);
        ServletContextHandler handler = new ServletContextHandler();
        handler.setContextPath("/"); handler.addServlet(new ServletHolder(new Handler()), "/*");
        server.setHandler(handler); server.start();
        System.out.println("JVM:" + System.getProperty("java.version") + ";pid=" + ProcessHandle.current().pid());
        System.out.println("READY:" + ((ServerConnector) server.getConnectors()[0]).getLocalPort());
        server.join();
    }
    static final class Handler extends HttpServlet {
        @Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws java.io.IOException {
            try {
                String key = request.getParameter("key");
                Map<String, Object> out = new LinkedHashMap<>();
                switch (request.getPathInfo()) {
                    case "/prepare" -> {
                        State s = prepare(request); STATES.put(key, s);
                        out.put("handleType", s.handle.type().toString());
                    }
                    case "/submit" -> {
                        State s = STATES.get(key);
                        try (Scope ignored = Context.current().with(IndirectWork.EXTRA, key).makeCurrent()) {
                            s.owner = IndirectWork.context(); s.invoking.countDown();
                            try { s.result = invoke(s); }
                            catch (Throwable e) { s.invocationFailure = e; }
                            finally { s.submitted.countDown(); }
                            if (!s.owner.equals(IndirectWork.context())) throw new AssertionError("submission Context changed");
                            out.put("owner", s.owner); describeFailure(out, s.invocationFailure);
                        }
                        out.put("handleType", s.handle.type().toString());
                    }
                    case "/release" -> {
                        State s = STATES.get(key);
                        if (!s.invoking.await(15, TimeUnit.SECONDS)) throw new AssertionError("not submitted");
                        try (Scope ignored = Context.current().with(IndirectWork.EXTRA, "completion-" + key).makeCurrent()) {
                            var before = IndirectWork.context(); out.put("completion", before);
                            s.work.release.countDown();
                            if ("cancelSource".equals(request.getParameter("action"))) s.source.cancel(false);
                            else { s.source.complete("left"); s.other.complete("right"); }
                            IndirectMethods.after();
                            if (!before.equals(IndirectWork.context())) throw new AssertionError("completion Context changed");
                        }
                    }
                    case "/cancel" -> {
                        State s = STATES.get(key);
                        if (!s.submitted.await(15, TimeUnit.SECONDS)) throw new AssertionError("not submitted");
                        out.put("cancelled", ((Future<?>) s.result).cancel(false));
                    }
                    case "/result" -> {
                        State s = STATES.get(key);
                        if (!s.submitted.await(15, TimeUnit.SECONDS)) throw new AssertionError("submission did not return");
                        if (s.periodic()) {
                            if (!s.work.finished.await(15, TimeUnit.SECONDS)) throw new AssertionError("periodic executions missing");
                            out.put("cancelled", ((Future<?>) s.result).cancel(false));
                        } else if (s.invocationFailure == null) {
                            try {
                                if (s.result instanceof Future<?> future) out.put("value", future.get(15, TimeUnit.SECONDS));
                                else if (s.result instanceof List<?> futures) {
                                    List<Object> values = new ArrayList<>();
                                    for (Object future : futures) values.add(((Future<?>) future).get(15, TimeUnit.SECONDS));
                                    out.put("value", values);
                                } else if (s.bulk()) out.put("value", s.result);
                                else if (s.receiver instanceof Thread thread) {
                                    thread.join(15000); if (thread.isAlive()) throw new AssertionError("thread alive");
                                    out.put("uncaught", s.uncaught); out.put("uncaughtIdentity", s.uncaughtIdentity);
                                } else if (!s.work.finished.await(15, TimeUnit.SECONDS)) throw new AssertionError("task unfinished");
                            } catch (ExecutionException | CancellationException e) { describeFailure(out, e); }
                        }
                        describeFailure(out, s.invocationFailure);
                        out.put("owner", s.owner); out.put("executions", s.work.executions);
                    }
                    case "/probe" -> {
                        out.put("worker", WORKER.submit(IndirectWork::context).get(15, TimeUnit.SECONDS));
                        out.put("workerAfter", WORKER.after);
                        out.put("timer", TIMER.submit(IndirectWork::context).get(15, TimeUnit.SECONDS));
                        out.put("timerAfter", TIMER.after);
                    }
                    case "/block" -> {
                        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
                        BLOCKERS.put(key, release);
                        Executor executor = "timer".equals(request.getParameter("pool")) ? TIMER : WORKER;
                        executor.execute(() -> {
                            entered.countDown();
                            try { if (!release.await(15, TimeUnit.SECONDS)) throw new AssertionError("blocker not released"); }
                            catch (InterruptedException e) { throw new AssertionError(e); }
                        });
                        if (!entered.await(15, TimeUnit.SECONDS)) throw new AssertionError("blocker not entered");
                        out.put("blocked", true);
                    }
                    case "/unblock" -> { BLOCKERS.remove(key).countDown(); out.put("unblocked", true); }
                    case "/invalid" -> {
                        State s = STATES.get(key);
                        Object[] original = s.args; s.args = s.args.clone(); s.args[0] = new Object();
                        try { invoke(s); throw new AssertionError("invalid argument accepted"); }
                        catch (Throwable expected) { describeFailure(out, expected); }
                        finally { s.args = original; }
                    }
                    case "/restart" -> {
                        State s = STATES.get(key);
                        try { invoke(s); throw new AssertionError("thread restarted"); }
                        catch (Throwable expected) { describeFailure(out, expected); }
                    }
                    case "/wrongType" -> {
                        try { STATES.get(key).handle.invokeExact(); throw new AssertionError("wrong handle type accepted"); }
                        catch (WrongMethodTypeException expected) { describeFailure(out, expected); }
                    }
                    default -> throw new IllegalArgumentException(request.getPathInfo());
                }
                response.setContentType("application/json"); response.getWriter().write(JSON.writeValueAsString(out));
            } catch (Throwable failure) {
                failure.printStackTrace(); response.setStatus(500); response.getWriter().write(failure.toString());
            }
        }
    }
    static Object invoke(State s) throws Throwable {
        return s.scope.equals("included")
                ? IncludedCalls.invoke(s.route, s.shape, s.method, s.handle, s.receiver, s.args)
                : OutsideCalls.invoke(s.route, s.shape, s.method, s.handle, s.receiver, s.args);
    }
    static void describeFailure(Map<String, Object> out, Throwable error) {
        if (error == null) return;
        out.put("failure", error.getClass().getName());
        if (error.getCause() != null) out.put("cause", error.getCause().getClass().getName());
        Throwable root = error;
        while (root.getCause() != null) root = root.getCause();
        out.put("rootFailure", root.getClass().getName());
    }
    static State prepare(HttpServletRequest req) throws Exception {
        State s = new State(); s.route = req.getParameter("route"); s.shape = req.getParameter("shape"); s.scope = req.getParameter("scope");
        s.work = new IndirectWork(s.periodic() ? 2 : 1);
        s.work.fail = "true".equals(req.getParameter("fail"));
        if ("b".equals(req.getParameter("leaf"))) s.work.leaf = "b";
        String barrier = req.getParameter("barrier");
        if (barrier != null) s.work.barrier = BARRIERS.computeIfAbsent(barrier, ignored -> new CyclicBarrier(2));
        ExecutorService executor = barrier == null ? WORKER : PARALLEL;
        String sourceKey = req.getParameter("source");
        if (sourceKey != null) s.source = SOURCES.computeIfAbsent(sourceKey, ignored -> new CompletableFuture<>());
        s.receiver = executor;
        List<Callable<String>> tasks = List.of(s.work);
        switch (s.shape) {
            case "execute", "customExecute" -> {
                s.method = (s.shape.equals("execute") ? Executor.class : IndirectWork.MeasuredExecutor.class).getMethod("execute", Runnable.class);
                s.args = new Object[]{s.work};
            }
            case "submitRunnable" -> { s.method = ExecutorService.class.getMethod("submit", Runnable.class); s.args = new Object[]{s.work}; }
            case "submitResult" -> { s.method = ExecutorService.class.getMethod("submit", Runnable.class, Object.class); s.args = new Object[]{s.work, "supplied-result"}; }
            case "submitCallable" -> { s.method = ExecutorService.class.getMethod("submit", Callable.class); s.args = new Object[]{s.work}; }
            case "all", "any", "allTimed", "anyTimed" -> {
                String name = s.shape.startsWith("all") ? "invokeAll" : "invokeAny";
                if (s.shape.endsWith("Timed")) { s.method = ExecutorService.class.getMethod(name, Collection.class, long.class, TimeUnit.class); s.args = new Object[]{tasks, 15L, TimeUnit.SECONDS}; }
                else { s.method = ExecutorService.class.getMethod(name, Collection.class); s.args = new Object[]{tasks}; }
            }
            case "scheduleRunnable", "scheduleCallable" -> {
                s.receiver = TIMER;
                s.method = ScheduledExecutorService.class.getMethod("schedule", s.shape.endsWith("Runnable") ? Runnable.class : Callable.class, long.class, TimeUnit.class);
                s.args = new Object[]{s.work, 0L, TimeUnit.MILLISECONDS};
            }
            case "fixedRate", "fixedDelay" -> {
                s.receiver = TIMER;
                s.method = ScheduledExecutorService.class.getMethod(s.shape.equals("fixedRate") ? "scheduleAtFixedRate" : "scheduleWithFixedDelay", Runnable.class, long.class, long.class, TimeUnit.class);
                s.args = new Object[]{s.work, 0L, 5L, TimeUnit.MILLISECONDS};
            }
            case "apply" -> { s.receiver = s.source; s.method = CompletableFuture.class.getMethod("thenApply", Function.class); s.args = new Object[]{(Function<String, String>) s.work::apply}; }
            case "applyAsync" -> { s.receiver = s.source; s.method = CompletableFuture.class.getMethod("thenApplyAsync", Function.class, Executor.class); s.args = new Object[]{(Function<String, String>) s.work::apply, executor}; }
            case "combine" -> { s.receiver = s.source; s.method = CompletableFuture.class.getMethod("thenCombine", CompletionStage.class, BiFunction.class); s.args = new Object[]{s.other, (BiFunction<String, String, String>) s.work::apply}; }
            case "start" -> {
                Thread thread = IndirectMethods.thread(s.work); s.receiver = thread;
                thread.setUncaughtExceptionHandler((t, e) -> { s.uncaught = e.getClass().getName(); s.uncaughtIdentity = String.valueOf(IndirectWork.context().get("identity")); });
                s.method = Thread.class.getMethod("start"); s.args = new Object[0];
            }
            default -> throw new IllegalArgumentException(s.shape);
        }
        if ("lambda".equals(req.getParameter("kind"))) {
            if (s.bulk()) s.args[0] = List.of((Callable<String>) s.work::call);
            else if (s.args.length > 0 && s.args[0] == s.work)
                s.args[0] = s.method.getParameterTypes()[0] == Callable.class
                        ? (Callable<String>) s.work::call : (Runnable) s.work::run;
        }
        s.handle = MethodHandles.publicLookup().unreflect(s.method);
        if (s.route.startsWith("bound-")) s.handle = s.handle.bindTo(s.receiver);
        return s;
    }
}
