// SPDX-License-Identifier: Apache-2.0
package example.threadsupport;

import com.fasterxml.jackson.databind.ObjectMapper;
import example.threadapp.ThreadOperations;
import io.opentelemetry.context.Context;
import jakarta.servlet.http.*;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.servlet.*;
import java.util.*;
import java.util.concurrent.*;

public final class ThreadOwnershipServer {
    private static final Map<String, Entry> THREADS = new ConcurrentHashMap<>();
    private static final Map<String, CyclicBarrier> BARRIERS = new ConcurrentHashMap<>();
    // Worker is created lazily by the standard executor through an included application factory.
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(task -> ThreadOperations.create("plain", task));
    private record Entry(Thread thread, ThreadWork work, Map<String, Object> construction) { }
    public static void main(String[] args) throws Exception {
        ThreadOperations.a();
        Server server = new Server(0);
        ServletContextHandler handler = new ServletContextHandler(); handler.setContextPath("/");
        handler.addServlet(new ServletHolder(new Handler()), "/*"); server.setHandler(handler); server.start();
        System.out.println("JVM:" + System.getProperty("java.version") + ";pid=" + ProcessHandle.current().pid());
        System.out.println("READY:" + ((ServerConnector) server.getConnectors()[0]).getLocalPort());
        server.join();
    }
    private static final class Handler extends HttpServlet {
        @Override protected void doGet(HttpServletRequest req, HttpServletResponse res) throws java.io.IOException {
            String key = req.getParameter("key");
            Map<String, Object> result = new LinkedHashMap<>();
            try (var scope = Context.current().with(ThreadWork.EXTRA, key).makeCurrent()) {
                switch (req.getPathInfo()) {
                    case "/create" -> {
                        ThreadWork work = new ThreadWork();
                        work.failure = req.getParameter("failure"); work.nested = "true".equals(req.getParameter("nested"));
                        work.leaf = Optional.ofNullable(req.getParameter("leaf")).orElse("a");
                        String barrier = req.getParameter("barrier");
                        if (barrier != null) work.barrier = BARRIERS.computeIfAbsent(barrier, ignored -> new CyclicBarrier(2));
                        Thread thread = ThreadOperations.create(req.getParameter("shape"), work);
                        thread.setUncaughtExceptionHandler(work::uncaught);
                        THREADS.put(key, new Entry(thread, work, ThreadWork.context()));
                        result.put("construction", ThreadWork.context());
                    }
                    case "/start", "/direct", "/restart" -> {
                        Entry entry = THREADS.get(key);
                        result.put("before", ThreadWork.context());
                        if (req.getPathInfo().equals("/direct")) {
                            entry.work.direct = true;
                            try { ThreadOperations.direct(entry.thread); } finally { entry.work.direct = false; }
                            result.put("executions", entry.work.executions);
                        } else {
                            try {
                                if ("outside".equals(req.getParameter("type"))) OutsideThreads.start(entry.thread);
                                else if ("concrete".equals(req.getParameter("type"))) ThreadOperations.concreteStart((ThreadOperations.OverrideThread) entry.thread);
                                else if ("inherited".equals(req.getParameter("type"))) ThreadOperations.inheritedStart((ThreadOperations.InheritedThread) entry.thread);
                                else ThreadOperations.start(entry.thread);
                                result.put("started", true);
                            } catch (IllegalThreadStateException original) { result.put("failure", original.getClass().getName()); }
                        }
                        result.put("after", ThreadWork.context());
                    }
                    case "/release" -> { THREADS.get(key).work.release.countDown(); result.put("released", true); }
                    case "/interrupt" -> {
                        Entry entry = THREADS.get(key);
                        if (!entry.work.entered.await(10, TimeUnit.SECONDS)) throw new AssertionError("work did not enter");
                        entry.thread.interrupt(); result.put("interrupted", true);
                    }
                    case "/result" -> {
                        Entry entry = THREADS.get(key); entry.thread.join(10000);
                        if (entry.thread.isAlive()) throw new AssertionError("thread did not finish");
                        result.put("executions", entry.work.executions);
                        result.put("failureContext", entry.work.failureContext);
                        result.put("failureExtra", entry.work.failureExtra);
                        result.put("failureType", entry.work.failureType);
                        result.put("terminated", entry.thread.getState() == Thread.State.TERMINATED);
                    }
                    case "/executor" -> {
                        ThreadWork work = new ThreadWork(); work.release.countDown();
                        result.put("before", ThreadWork.context());
                        EXECUTOR.submit(work).get(10, TimeUnit.SECONDS);
                        result.put("executions", work.executions);
                    }
                    default -> throw new IllegalArgumentException(req.getPathInfo());
                }
                res.setContentType("application/json"); res.getWriter().write(new ObjectMapper().writeValueAsString(result));
            } catch (Throwable failure) { failure.printStackTrace(); res.setStatus(500); res.getWriter().write(failure.toString()); }
        }
    }
}
