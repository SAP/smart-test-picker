// SPDX-License-Identifier: Apache-2.0
package example.chainsupport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sap.oss.smarttestpicker.remote.ChainCheckpointControl;
import example.chains.ChainMethods;
import io.opentelemetry.context.*;
import jakarta.servlet.http.*;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.servlet.*;
import java.util.*;
import java.util.concurrent.*;

public final class ChainServer {
    static final ObjectMapper JSON = new ObjectMapper();
    static final InspectingExecutor WORKER = new InspectingExecutor(4), SINGLE = new InspectingExecutor(1), BULK = new InspectingExecutor(2);
    static final InspectingScheduler TIMER = new InspectingScheduler();
    static final Queue<Map<String, Object>> CLEANUP_FAILURES = new ConcurrentLinkedQueue<>();
    static final Map<String, ChainState> STATES = new ConcurrentHashMap<>();
    static final Map<String, CyclicBarrier> BARRIERS = new ConcurrentHashMap<>();
    public static void main(String[] args) throws Exception {
        // Initialize observed classes without identity to keep <clinit> out of expected relations.
        Class.forName("example.chains.ChainMethods"); Class.forName("example.chains.ChainBoundaries");
        WORKER.prestartAllCoreThreads(); SINGLE.prestartAllCoreThreads(); BULK.prestartAllCoreThreads(); TIMER.prestartAllCoreThreads();
        Server server = new Server(0); ServletContextHandler handler = new ServletContextHandler();
        handler.setContextPath("/"); handler.addServlet(new ServletHolder(new Handler()), "/*"); server.setHandler(handler); server.start();
        System.out.println("JVM:" + System.getProperty("java.version") + ";pid=" + ProcessHandle.current().pid());
        System.out.println("READY:" + ((ServerConnector) server.getConnectors()[0]).getLocalPort()); server.join();
    }
    static final class Handler extends HttpServlet {
        @Override protected void doGet(HttpServletRequest req, HttpServletResponse res) throws java.io.IOException {
            try {
                String key = req.getParameter("key"); Map<String, Object> result = new LinkedHashMap<>();
                switch (req.getPathInfo()) {
                    case "/start" -> {
                        try (Scope ignored = Context.current().with(ChainState.EXTRA, key).makeCurrent()) {
                            String barrier = req.getParameter("barrier");
                            var state = new ChainState(key, req.getParameter("kind"), "B".equals(req.getParameter("side")),
                                    "true".equals(req.getParameter("fail")), "true".equals(req.getParameter("single")) ? SINGLE : WORKER,
                                    barrier == null ? null : BARRIERS.computeIfAbsent(barrier, unused -> new CyclicBarrier(2)));
                            STATES.put(key, state); state.start(); result.put("owner", state.owner);
                            result.put("callbackObject", System.identityHashCode(ChainState.SHARED_CALLBACK));
                        }
                    }
                    case "/release" -> { STATES.get(key).release.countDown(); result.put("released", true); }
                    case "/registered" -> {
                        var state = STATES.get(key); ChainState.await(state.registered);
                        if (state.initialFailure != null) throw new AssertionError(state.initialFailure);
                        result.put("callbacks", state.stages.size()); result.put("bulkResults", state.bulkResults);
                    }
                    case "/complete" -> {
                        var state = STATES.get(key); ChainState.await(state.registered);
                        try (Scope ignored = Context.current().with(ChainState.EXTRA, "completion-" + key).makeCurrent()) {
                            var before = ChainState.context();
                            for (var source : state.sources) source.complete(state);
                            ChainMethods.completion();
                            if (!before.equals(ChainState.context())) throw new AssertionError("completion Context leaked");
                            result.put("completion", before);
                        }
                    }
                    case "/ready" -> { ChainState.await(STATES.get(key).ready); result.put("ready", true); }
                    case "/finish" -> { STATES.get(key).lateRelease.countDown(); result.put("released", true); }
                    case "/cancel" -> {
                        var state = STATES.get(key); ChainState.await(state.registered);
                        result.put("cancelled", state.stages.get(0).cancel(false));
                    }
                    case "/result" -> {
                        var state = STATES.get(key); List<String> values = new ArrayList<>();
                        try {
                            for (var stage : state.stages) values.add(stage.get(20, TimeUnit.SECONDS));
                            if (state.scheduled != null) state.scheduled.get(20, TimeUnit.SECONDS);
                            if (state.initial != null) state.initial.get(20, TimeUnit.SECONDS);
                            if (state.thread != null) { state.thread.join(20000); if (state.thread.isAlive()) throw new AssertionError("thread alive"); }
                        } catch (ExecutionException | CancellationException failure) {
                            result.put("failure", failure.getClass().getName());
                            result.put("cause", failure.getCause() == null ? null : failure.getCause().getClass().getName());
                        }
                        result.put("values", values); result.put("events", state.events); result.put("owner", state.owner);
                    }
                    case "/probe" -> {
                        drain(WORKER, 4); drain(SINGLE, 1); drain(BULK, 2); drain(TIMER, 2);
                        if (!CLEANUP_FAILURES.isEmpty()) throw new AssertionError("worker context leaked: " + CLEANUP_FAILURES);
                        result.put("worker", WORKER.after); result.put("single", SINGLE.after); result.put("bulk", BULK.after); result.put("timer", TIMER.after);
                    }
                    case "/checkpoint/begin" -> { ChainCheckpointControl.begin(); result.put("captured", true); }
                    case "/checkpoint/finish" -> { ChainCheckpointControl.finish(); result.put("persisted", true); }
                    case "/checkpoint" -> { ChainCheckpointControl.checkpoint(); result.put("persisted", true); }
                    case "/snapshots" -> {
                        result.put("memory", JSON.readTree(ChainCheckpointControl.memory()));
                        result.put("snapshot", JSON.readTree(ChainCheckpointControl.snapshot()));
                        String output = ChainCheckpointControl.output(); result.put("output", output == null ? null : JSON.readTree(output));
                    }
                    default -> throw new IllegalArgumentException(req.getPathInfo());
                }
                res.setContentType("application/json"); res.getWriter().write(JSON.writeValueAsString(result));
            } catch (Throwable e) { e.printStackTrace(); res.setStatus(500); res.getWriter().write(e.toString()); }
        }
    }
    static void drain(ExecutorService pool, int workers) throws Exception {
        // Every worker must finish its previous afterExecute before all probes can meet here.
        CyclicBarrier barrier = new CyclicBarrier(workers);
        List<Future<?>> probes = new ArrayList<>();
        for (int i = 0; i < workers; i++) probes.add(pool.submit(() -> { barrier.await(20, TimeUnit.SECONDS); return null; }));
        for (Future<?> probe : probes) probe.get(20, TimeUnit.SECONDS);
    }
    static void inspected(Map<Long, Map<String, Object>> after) {
        var context = ChainState.context();
        after.put(Thread.currentThread().getId(), context);
        // Retain every violation: a later clean task must not hide an earlier leak.
        if (!"null".equals(context.get("identity")) || context.get("extra") != null) CLEANUP_FAILURES.add(context);
    }
    static final class InspectingExecutor extends ThreadPoolExecutor {
        final Map<Long, Map<String, Object>> after = new ConcurrentHashMap<>();
        InspectingExecutor(int count) { super(count, count, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>()); }
        @Override protected void afterExecute(Runnable task, Throwable error) { inspected(after); }
    }
    static final class InspectingScheduler extends ScheduledThreadPoolExecutor {
        final Map<Long, Map<String, Object>> after = new ConcurrentHashMap<>();
        InspectingScheduler() { super(2); }
        @Override protected void afterExecute(Runnable task, Throwable error) { inspected(after); }
    }
}
