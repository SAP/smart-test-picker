// SPDX-License-Identifier: Apache-2.0
package example.indirectsupport;

import com.sap.oss.smarttestpicker.remote.RemoteTestContext;
import example.indirect.IndirectMethods;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;

/** Uninstrumented coordination; only the explicit application leaves enter the fragment. */
public final class IndirectWork implements Runnable, Callable<String> {
    public static final ContextKey<String> EXTRA = ContextKey.named("indirect-extra");
    public final CountDownLatch release = new CountDownLatch(1), entered = new CountDownLatch(1);
    public final List<Map<String, Object>> executions = new CopyOnWriteArrayList<>();
    public final AtomicInteger calls = new AtomicInteger();
    public final CountDownLatch finished;
    public final int limit;
    public String leaf = "a";
    public boolean fail;
    public CyclicBarrier barrier;
    public IndirectWork(int limit) { this.limit = limit; finished = new CountDownLatch(limit); }
    public static Map<String, Object> context() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("identity", String.valueOf(RemoteTestContext.currentIdentity()));
        map.put("extra", Context.current().get(EXTRA));
        map.put("trace", Span.current().getSpanContext().getTraceId());
        map.put("thread", Thread.currentThread().getId());
        return map;
    }
    private String perform(String input) {
        // Periodic tasks are cancelled by the test after two completed executions; a racing third
        // invocation is deliberately inert, rather than manufacturing a third application hit.
        if (calls.incrementAndGet() > limit) return "result";
        entered.countDown();
        try {
            if (!release.await(15, TimeUnit.SECONDS)) throw new AssertionError("work not released");
            if (barrier != null) barrier.await(15, TimeUnit.SECONDS);
            var actual = context(); actual.put("input", input); executions.add(actual);
            if (leaf.equals("b")) IndirectMethods.b(); else IndirectMethods.a();
            if (fail) throw new IllegalStateException("task-failure");
            return input == null ? "result" : input + "-result";
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
        catch (BrokenBarrierException | TimeoutException e) { throw new AssertionError(e); }
        finally { finished.countDown(); }
    }
    @Override public void run() { perform(null); }
    @Override public String call() { return perform(null); }
    public String apply(String value) { return perform(value); }
    public String apply(String left, String right) { return perform(left + ":" + right); }

    public static final class MeasuredExecutor extends ThreadPoolExecutor {
        public volatile Map<String, Object> after = context();
        public MeasuredExecutor(int workers) { super(workers, workers, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>()); }
        @Override public void execute(Runnable task) { super.execute(task); }
        @Override protected void afterExecute(Runnable task, Throwable error) { after = context(); }
    }
    public static final class MeasuredScheduler extends ScheduledThreadPoolExecutor {
        public volatile Map<String, Object> after = context();
        public MeasuredScheduler() { super(1); setRemoveOnCancelPolicy(true); }
        @Override protected void afterExecute(Runnable task, Throwable error) { after = context(); }
    }
}
