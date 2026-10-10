// SPDX-License-Identifier: Apache-2.0
package example.threadsupport;

import com.sap.oss.smarttestpicker.remote.RemoteTestContext;
import example.threadapp.ThreadOperations;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import java.util.*;
import java.util.concurrent.*;

/** Not instrumented; only application leaf calls contribute to the expected method relation. */
public final class ThreadWork implements Runnable {
    public static final ContextKey<String> EXTRA = ContextKey.named("thread-fixture-extra");
    public final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    public final List<Map<String, Object>> executions = new CopyOnWriteArrayList<>();
    public volatile String failureContext;
    public volatile String failureExtra;
    public volatile String failureType;
    public volatile boolean direct;
    public String leaf = "a", failure;
    public boolean nested;
    public CyclicBarrier barrier;
    public static Map<String, Object> context() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("identity", String.valueOf(RemoteTestContext.currentIdentity()));
        result.put("extra", Context.current().get(EXTRA));
        result.put("trace", Span.current().getSpanContext().getTraceId());
        result.put("thread", Thread.currentThread().getId());
        result.put("name", Thread.currentThread().getName());
        result.put("group", Thread.currentThread().getThreadGroup().getName());
        result.put("virtual", Runtime.version().feature() >= 21 && Thread.currentThread().isVirtual());
        return result;
    }
    @Override public void run() {
        entered.countDown();
        boolean interrupted = false;
        if (!direct) try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("release not received"); }
        catch (InterruptedException expected) { interrupted = true; }
        if (barrier != null) try { barrier.await(10, TimeUnit.SECONDS); }
        catch (Exception e) { throw new AssertionError(e); }
        Map<String, Object> result = context();
        result.put("interrupted", interrupted);
        executions.add(result);
        switch (leaf) {
            case "a" -> ThreadOperations.a();
            case "b" -> ThreadOperations.b();
            default -> ThreadOperations.anonymous();
        }
        if (nested) {
            ThreadWork child = new ThreadWork(); child.leaf = "b"; child.release.countDown();
            try { ThreadOperations.nested(child); }
            catch (InterruptedException e) { throw new AssertionError(e); }
            executions.addAll(child.executions);
        }
        if ("exception".equals(failure)) throw new IllegalStateException("original");
        if ("error".equals(failure)) throw new AssertionError("original");
    }
    public void uncaught(Thread thread, Throwable failure) {
        failureContext = String.valueOf(RemoteTestContext.currentIdentity());
        failureExtra = Context.current().get(EXTRA);
        failureType = failure.getClass().getName();
    }
}
