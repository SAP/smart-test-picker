// SPDX-License-Identifier: Apache-2.0
package example.cfsupport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sap.oss.smarttestpicker.remote.RemoteTestContext;
import example.cf.ObservedStages;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import jakarta.servlet.http.*;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.*;
import java.util.*;
import java.util.concurrent.*;

/** Real Servlet requests register and complete futures in separate REQUEST dispatches. */
public final class StageOwnershipServer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, CompletableFuture<String>> SOURCES = new ConcurrentHashMap<>();
    private static final Map<String, State> STATES = new ConcurrentHashMap<>();
    private static final Map<String, CyclicBarrier> BARRIERS = new ConcurrentHashMap<>();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final ExecutorService PARALLEL = Executors.newFixedThreadPool(2);
    private record State(String shape, CompletableFuture<String> source, CompletableFuture<String> other,
                         StageCallbacks callback, CompletableFuture<?> stage, String owner, String extra,
                         String trace, long thread) { }

    public static void main(String[] args) throws Exception {
        // Warm the observed class outside any request; constructors/<clinit> cannot pollute assertions.
        ObservedStages.a();
        WORKER.submit(() -> { }).get();
        Server server = new Server(0);
        ServletContextHandler handler = new ServletContextHandler();
        handler.setContextPath("/");
        handler.addServlet(new ServletHolder(new Handler()), "/*");
        server.setHandler(handler);
        server.start();
        System.out.println("JVM:" + System.getProperty("java.version") + ";pid=" + ProcessHandle.current().pid());
        System.out.println("READY:" + ((ServerConnector) server.getConnectors()[0]).getLocalPort());
        server.join();
    }

    private static final class Handler extends HttpServlet {
        @Override protected void doGet(HttpServletRequest req, HttpServletResponse res) throws java.io.IOException {
            try {
                String key = req.getParameter("key");
                Map<String, Object> result = new LinkedHashMap<>();
                switch (req.getPathInfo()) {
                    case "/create" -> {
                        SOURCES.put(key, new CompletableFuture<>());
                        ObservedStages.a();
                        result.put("created", true);
                    }
                    case "/register" -> {
                        String shape = req.getParameter("shape");
                        String sourceKey = Optional.ofNullable(req.getParameter("source")).orElse(key);
                        var source = SOURCES.computeIfAbsent(sourceKey, ignored -> new CompletableFuture<>());
                        var other = SOURCES.computeIfAbsent(sourceKey + "-other", ignored -> new CompletableFuture<>());
                        StageCallbacks cb = new StageCallbacks();
                        cb.leaf = Optional.ofNullable(req.getParameter("leaf")).orElse("a");
                        cb.fail = "true".equals(req.getParameter("fail"));
                        String barrier = req.getParameter("barrier");
                        if (barrier != null) cb.barrier = BARRIERS.computeIfAbsent(barrier, ignored -> new CyclicBarrier(2));
                        if ("true".equals(req.getParameter("ready"))) {
                            complete(source, shape.startsWith("exceptionally") ? "exception" : "success");
                            other.complete("right");
                        }
                        String owner = String.valueOf(RemoteTestContext.currentIdentity());
                        String trace = Span.current().getSpanContext().getTraceId();
                        try (Scope scope = Context.current().with(StageCallbacks.EXTRA, key).makeCurrent()) {
                            var stage = ObservedStages.register(shape, source, other, cb, barrier == null ? WORKER : PARALLEL);
                            if ("true".equals(req.getParameter("chain"))) stage = ObservedStages.follow(stage, ObservedStages::continuation);
                            STATES.put(key, new State(shape, source, other, cb, stage, owner, key, trace, Thread.currentThread().getId()));
                        }
                        result.put("registered", true);
                    }
                    case "/complete" -> {
                        State state = STATES.get(key);
                        String before = String.valueOf(RemoteTestContext.currentIdentity());
                        ObservedStages.completion();
                        String side = req.getParameter("side");
                        if (!"right".equals(side)) complete(state.source, Optional.ofNullable(req.getParameter("outcome")).orElse(
                                state.shape.startsWith("exceptionally") ? "exception" : "success"));
                        if (!"left".equals(side)) state.other.complete("right");
                        ObservedStages.afterCompletion();
                        if (!before.equals(String.valueOf(RemoteTestContext.currentIdentity()))) throw new AssertionError("completion thread leaked context");
                        result.put("restored", true);
                    }
                    case "/inner" -> {
                        State state = STATES.get(key);
                        if (!state.callback.entered.await(10, TimeUnit.SECONDS)) throw new AssertionError("compose callback not entered");
                        result.put("pending", !state.stage.isDone());
                        state.callback.inner.complete("inner-result");
                    }
                    case "/cancel" -> result.put("cancelled", STATES.get(key).stage.cancel(false));
                    case "/result" -> {
                        State state = STATES.get(key);
                        try { result.put("result", state.stage.get(10, TimeUnit.SECONDS)); }
                        catch (ExecutionException e) { result.put("failure", e.getCause().getClass().getName()); }
                        catch (CancellationException e) { result.put("failure", e.getClass().getName()); }
                        StageCallbacks cb = state.callback;
                        result.put("calls", cb.calls);
                        result.put("owner", state.owner);
                        result.put("actual", cb.actual);
                        result.put("extra", cb.extra);
                        result.put("trace", cb.trace);
                        result.put("registrationTrace", state.trace);
                        result.put("requestThread", state.thread);
                        result.put("callbackThread", cb.thread);
                        result.put("callbackThreadName", cb.threadName);
                        result.put("input", cb.input);
                        result.put("error", cb.error == null ? null : cb.error.getClass().getName());
                    }
                    case "/probe" -> result.put("worker", WORKER.submit(() ->
                            String.valueOf(RemoteTestContext.currentIdentity()) + ":" + Context.current().get(StageCallbacks.EXTRA)).get(10, TimeUnit.SECONDS));
                    default -> throw new IllegalArgumentException(req.getPathInfo());
                }
                res.setContentType("application/json");
                res.getWriter().write(JSON.writeValueAsString(result));
            } catch (Throwable e) {
                e.printStackTrace();
                res.setStatus(500);
                res.getWriter().write(e.toString());
            }
        }
    }
    private static void complete(CompletableFuture<String> source, String outcome) {
        switch (outcome) {
            case "exception" -> source.completeExceptionally(new IllegalArgumentException("source-failure"));
            case "cancel" -> source.cancel(false);
            default -> source.complete("left");
        }
    }
}
