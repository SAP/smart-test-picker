// SPDX-License-Identifier: Apache-2.0
package example.chainsupport;

import com.sap.oss.smarttestpicker.remote.RemoteTestContext;
import example.chains.*;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/** Latches gate execution after HTTP responses, CF registration, and the checkpoint snapshot. */
public final class ChainState {
    public static final ContextKey<String> EXTRA = ContextKey.named("chain-extra");
    public static final Function<ChainState, String> SHARED_CALLBACK = ChainState::callback;
    public final String key, kind;
    public final boolean sideB, fail;
    public final ExecutorService executor;
    public final CyclicBarrier barrier;
    public final Map<String, Object> owner;
    public final List<Map<String, Object>> events = new CopyOnWriteArrayList<>();
    public final List<CompletableFuture<ChainState>> sources = new CopyOnWriteArrayList<>();
    public final List<CompletableFuture<String>> stages = new CopyOnWriteArrayList<>();
    public final CountDownLatch release = new CountDownLatch(1), registered = new CountDownLatch(1), lateRelease = new CountDownLatch(1);
    public final CountDownLatch ready, finished;
    public volatile Throwable initialFailure;
    public volatile Future<?> initial, scheduled;
    public volatile Thread thread;
    public volatile List<String> bulkResults;

    public ChainState(String key, String kind, boolean sideB, boolean fail, ExecutorService executor, CyclicBarrier barrier) {
        this.key = key; this.kind = kind; this.sideB = sideB; this.fail = fail; this.executor = executor; this.barrier = barrier;
        owner = context();
        ready = new CountDownLatch(kind.equals("bulk") ? 2 : 1);
        finished = new CountDownLatch(kind.equals("bulk") ? 2 : 1);
    }
    public static Map<String, Object> context() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("identity", String.valueOf(RemoteTestContext.currentIdentity()));
        result.put("extra", Context.current().get(EXTRA));
        result.put("trace", Span.current().getSpanContext().getTraceId());
        result.put("thread", Thread.currentThread().getId());
        result.put("virtual", Runtime.version().feature() >= 21 && Thread.currentThread().isVirtual());
        return result;
    }
    public void start() throws Throwable {
        switch (kind) {
            case "fork" -> ChainBoundaries.fork(this::initialBody);
            case "thread" -> thread = ChainBoundaries.thread(this::initialBody);
            case "virtual" -> thread = ChainBoundaries.virtual(this::initialBody);
            case "reflection" -> initial = ChainBoundaries.reflection(executor, () -> { initialBody(); return "submitted"; });
            case "handle" -> initial = ChainBoundaries.handle(executor, () -> { initialBody(); return "submitted"; });
            default -> ChainBoundaries.execute(executor, this::initialBody);
        }
    }
    private void initialBody() {
        try {
            await(release);
            if (barrier != null) barrier.await(15, TimeUnit.SECONDS);
            switch (kind) {
                case "fork" -> {
                    event("fork");
                    initial = ChainBoundaries.submit(executor, () -> {
                        event("nested"); register(); registered.countDown();
                    });
                    return;
                }
                case "bulk" -> {
                    event("executor");
                    List<Future<String>> results = ChainBoundaries.bulk(ChainServer.BULK, List.of(
                            () -> { event("bulk"); register(); return "left"; },
                            () -> { event("bulk"); register(); return "right"; }));
                    bulkResults = List.of(results.get(0).get(), results.get(1).get());
                }
                default -> {
                    event(switch (kind) {
                        case "thread", "virtual" -> "thread";
                        case "reflection", "handle" -> "indirect";
                        default -> "executor";
                    });
                    register();
                }
            }
        } catch (Throwable failure) { initialFailure = failure; }
        finally { if (!kind.equals("fork") || initialFailure != null) registered.countDown(); }
    }
    private void register() {
        CompletableFuture<ChainState> source = new CompletableFuture<>();
        sources.add(source);
        stages.add(ChainBoundaries.callback(source, SHARED_CALLBACK, executor));
    }
    private String callback() {
        event("callback");
        if (fail) { event("failed"); throw new IllegalStateException("chain-failure"); }
        if (kind.equals("cf")) {
            scheduled = ChainBoundaries.schedule(ChainServer.TIMER, () -> { event("scheduled"); finish(); });
            return "scheduled-" + key;
        }
        finish(); return "done-" + key;
    }
    private void finish() {
        ready.countDown(); await(lateRelease); event("late"); finished.countDown();
    }
    public void event(String step) {
        Map<String, Object> actual = context(); actual.put("step", step); events.add(actual);
        switch (step) {
            case "executor" -> { if (sideB) ChainMethods.executorB(); else ChainMethods.executorA(); }
            case "fork" -> { if (sideB) ChainMethods.forkB(); else ChainMethods.forkA(); }
            case "nested" -> { if (sideB) ChainMethods.nestedB(); else ChainMethods.nestedA(); }
            case "bulk" -> { if (sideB) ChainMethods.bulkB(); else ChainMethods.bulkA(); }
            case "thread" -> { if (sideB) ChainMethods.threadB(); else ChainMethods.threadA(); }
            case "indirect" -> { if (sideB) ChainMethods.indirectB(); else ChainMethods.indirectA(); }
            case "callback" -> { if (sideB) ChainMethods.callbackB(); else ChainMethods.callbackA(); }
            case "scheduled" -> { if (sideB) ChainMethods.scheduledB(); else ChainMethods.scheduledA(); }
            case "failed" -> { if (sideB) ChainMethods.failedB(); else ChainMethods.failedA(); }
            case "late" -> { if (sideB) ChainMethods.lateB(); else ChainMethods.lateA(); }
            default -> throw new AssertionError(step);
        }
    }
    public static void await(CountDownLatch latch) {
        try { if (!latch.await(20, TimeUnit.SECONDS)) throw new AssertionError("chain gate timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
