// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.fixture;

import com.sap.oss.smarttestpicker.runtime.*;
import com.sap.oss.smarttestpicker.runtime.model.*;
import example.instrumented.BulkApplication;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real bulk call sites: no explicit capture or wrapping in the fixture. */
public class BulkExecutorFixtureMain {
    static final RuntimeContextService CONTEXT = RuntimeContextRegistry.current().orElseThrow();
    static final TestResult SUCCESS = new TestResult(TestExecutionStatus.SUCCESSFUL, null, null);
    static final AtomicReference<Throwable> ASYNC_FAILURE = new AtomicReference<>();

    public static void main(String[] args) throws Exception {
        ProbePool pool = new ProbePool(4);
        ForkJoinPool forkJoin = new ForkJoinPool(4);
        DelegatingPool custom = new DelegatingPool(pool);
        try {
            if (args.length > 0) {
                closedSetup(pool);
                run("missingParent", () -> ForkJoinTask.adapt((Callable<Integer>) () ->
                        pool.invokeAll(List.<Callable<Integer>>of(() -> {
                            check(CONTEXT.currentTest().isEmpty(), "missing parent cannot regain owner");
                            BulkApplication.unowned(1); return 1;
                        })).get(0).get()).quietlyInvoke());
                check(observation("missingParent").methods().isEmpty(), "no fabricated parent attribution");
                check(CONTEXT.aggregator().snapshot().setupDiagnostics().stream()
                        .anyMatch(d -> d.detail().contains("without observed submission")), "missing parent remains incomplete");
                System.out.println("bulk-closed-setup-ok"); return;
            }
            shapes("standard", pool); shapes("forkjoin", forkJoin); shapes("custom", custom);
            // A concrete owner and an invokespecial super call must keep their original dispatch.
            run("concrete", () -> check(custom.invokeAll(List.<Callable<Integer>>of(() -> BulkApplication.first(3))).get(0).get() == 3, "concrete result"));
            parallel(pool);
            anyCompetitors(pool);
            nested(pool);
            nestedForkJoin();
            sequentialReuse();
            run("customSubmit", () -> check(new SubmittingPool(pool).invokeAll(List.<Callable<Integer>>of(() -> {
                owner("customSubmit"); return BulkApplication.first(5);
            })).get(0).get() == 5, "internal submit result"));
            run("bulkError", () -> {
                var f = pool.invokeAll(List.<Callable<Integer>>of(() -> { BulkApplication.failed(1); throw new AssertionError("expected"); })).get(0);
                try { f.get(); throw new IllegalStateException("must fail"); }
                catch (ExecutionException expected) { check(expected.getCause() instanceof AssertionError, "error preserved"); }
            });
            ambientOwner();
            rejected();
            cancellation();
            for (boolean timed : List.of(false, true)) { interruption(false, timed); interruption(true, timed); }
            timeoutAndLate();
            setup(pool);
            arguments(pool); arguments(forkJoin); arguments(custom);
            Collection<Callable<Integer>> noOwner = List.of(() -> {
                check(CONTEXT.currentTest().isEmpty(), "unowned"); BulkApplication.unowned(1); return 1;
            });
            check(pool.invokeAll(noOwner).get(0).get() == 1, "unowned result");
            run("afterFailures", () -> pool.invokeAll(List.<Callable<Integer>>of(() -> { owner("afterFailures"); return BulkApplication.first(9); })));
            check(CONTEXT.aggregator().snapshot().setupDiagnostics().isEmpty(), "diagnostics " + CONTEXT.aggregator().snapshot().setupDiagnostics());
            var snapshot = CONTEXT.aggregator().snapshot();
            check(snapshot.tests().stream().filter(t -> !t.identity().platformUniqueId().equals("lateA"))
                    .allMatch(t -> t.unattributedEvents().isEmpty()), "no leaked finished owner");
            check(!snapshot.unattributedEvents().isEmpty() && snapshot.unattributedEvents().stream().allMatch(e ->
                    e.reason() == UnattributedReason.NO_ACTIVE_TEST && e.eventIdentity().contains("BulkApplication#unowned(I)V")),
                    "only deliberately unowned hits are unattributed: " + snapshot.unattributedEvents());
            check(snapshot.setup().size() == 1 && snapshot.setup().get(0).methods().equals(Set.of(
                    new MethodIdentity("example.instrumented.BulkApplication", "setup", "(I)V"),
                    new MethodIdentity("example.instrumented.BulkApplication", "first", "(I)I"))), "exact setup method ownership");
            System.out.println("bulk-supported-ok");
        } finally {
            pool.shutdown(); forkJoin.shutdown();
            check(pool.awaitTermination(10, TimeUnit.SECONDS), "pool shutdown");
            check(forkJoin.awaitTermination(10, TimeUnit.SECONDS), "forkjoin shutdown");
            if (ASYNC_FAILURE.get() != null) throw new AssertionError("async assertion", ASYNC_FAILURE.get());
        }
    }

    static void shapes(String prefix, ExecutorService executor) throws Exception {
        for (boolean timed : List.of(false, true)) {
            String all = prefix + (timed ? "-allTimed" : "-all");
            run(all, () -> {
                Callable<Object> first = () -> { owner(all); return BulkApplication.first(42); };
                Callable<Object> failure = () -> { owner(all); BulkApplication.failed(1); throw new IllegalStateException("expected"); };
                Callable<Object> second = () -> { owner(all); return BulkApplication.second("last"); };
                var tasks = Collections.unmodifiableList(List.of(first, failure, second));
                var futures = timed ? executor.invokeAll(tasks, 10, TimeUnit.SECONDS) : executor.invokeAll(tasks);
                check(futures.size() == 3 && futures.get(0).get().equals(42) && futures.get(2).get().equals("last"), "ordered results");
                try { futures.get(1).get(); throw new AssertionError("expected failure"); }
                catch (ExecutionException expected) { check(expected.getCause() instanceof IllegalStateException, "cause preserved"); }
                check(tasks.get(0) == first && tasks.get(1) == failure && tasks.get(2) == second, "collection unchanged");
            });
            String any = prefix + (timed ? "-anyTimed" : "-any");
            run(any, () -> {
                var tasks = List.<Callable<String>>of(() -> { owner(any); return BulkApplication.second("result"); });
                check((timed ? executor.invokeAny(tasks, 10, TimeUnit.SECONDS) : executor.invokeAny(tasks)).equals("result"), "any result");
            });
            run(prefix + (timed ? "-allFailTimed" : "-allFail"), () -> {
                var tasks = List.<Callable<Object>>of(() -> { BulkApplication.failed(1); throw new IllegalArgumentException("one"); },
                        () -> { BulkApplication.failed(2); throw new IllegalArgumentException("two"); });
                try { if (timed) executor.invokeAny(tasks, 10, TimeUnit.SECONDS); else executor.invokeAny(tasks);
                    throw new AssertionError("all failed"); }
                catch (ExecutionException expected) { check(expected.getCause() instanceof IllegalArgumentException, "all-failed cause"); }
            });
        }
    }

    static void parallel(ExecutorService pool) throws Exception {
        CyclicBarrier overlap = new CyclicBarrier(2);
        Callable<String> same = () -> { overlap.await(10, TimeUnit.SECONDS); BulkApplication.shared(1);
            String id = CONTEXT.currentTest().orElseThrow().platformUniqueId();
            if (id.equals("parallelA")) BulkApplication.onlyA(1); else if (id.equals("parallelB")) BulkApplication.onlyB("B");
            else throw new AssertionError("unknown shared owner " + id); return id; };
        Thread a = caller(() -> run("parallelA", () -> check(pool.invokeAll(List.of(same)).get(0).get().equals("parallelA"), "A result")));
        Thread b = caller(() -> run("parallelB", () -> check(pool.invokeAny(List.of(same)).equals("parallelB"), "B result")));
        a.start(); b.start(); join(a); join(b);
    }

    static void anyCompetitors(ExecutorService pool) throws Exception {
        for (boolean timed : List.of(false, true)) {
            CountDownLatch started = new CountDownLatch(2), finished = new CountDownLatch(1);
            run(timed ? "competitorsTimed" : "competitors", () -> {
                var tasks = List.<Callable<Integer>>of(
                    () -> { BulkApplication.failed(1); started.countDown(); throw new IllegalStateException("loser"); },
                    () -> { BulkApplication.competitor("started"); started.countDown();
                        try { new CountDownLatch(1).await(); return 0; } finally { finished.countDown(); } },
                    () -> { await(started); return BulkApplication.first(7); });
                check((timed ? pool.invokeAny(tasks, 10, TimeUnit.SECONDS) : pool.invokeAny(tasks)) == 7, "winner");
                await(finished);
            });
        }
    }

    static void nested(ExecutorService pool) throws Exception {
        run("nested", () -> check(pool.invokeAll(List.<Callable<Integer>>of(() -> {
            owner("nested"); BulkApplication.nested(1);
            int submitted = pool.submit(() -> { owner("nested"); return BulkApplication.first(2); }).get();
            return pool.invokeAny(List.<Callable<Integer>>of(() -> { owner("nested"); BulkApplication.second("inner"); return submitted; }));
        })).get(0).get() == 2, "nested result"));
    }

    static void nestedForkJoin() throws Exception {
        ForkJoinPool pool = new ForkJoinPool(1);
        try { run("nestedForkJoin", () -> pool.submit((Callable<Integer>) () -> {
            owner("nestedForkJoin");
            return pool.invokeAll(List.<Callable<Integer>>of(() -> {
                owner("nestedForkJoin"); BulkApplication.nested(1);
                return pool.invokeAny(List.<Callable<Integer>>of(() -> { owner("nestedForkJoin"); return BulkApplication.first(2); }));
            })).get(0).get();
        }).get());
        } finally { pool.shutdown(); check(pool.awaitTermination(10, TimeUnit.SECONDS), "nested forkjoin stopped"); }
    }
    static void sequentialReuse() throws Exception {
        ProbePool worker = new ProbePool(1);
        AtomicReference<Thread> previous = new AtomicReference<>();
        Callable<String> same = () -> {
            Thread old = previous.getAndSet(Thread.currentThread());
            check(old == null || old == Thread.currentThread(), "same physical worker");
            BulkApplication.shared(1); return CONTEXT.currentTest().orElseThrow().platformUniqueId();
        };
        try {
            run("reuseA", () -> check(worker.invokeAll(List.of(same)).get(0).get().equals("reuseA"), "reuse A"));
            run("reuseB", () -> check(worker.invokeAny(List.of(same)).equals("reuseB"), "reuse B"));
        } finally { worker.shutdown(); check(worker.awaitTermination(10, TimeUnit.SECONDS), "reuse worker stopped"); }
    }
    static void ambientOwner() throws Exception {
        // Simulate a synchronous executor entering a preexisting worker context before running the task.
        ExecutorService ambient = new DelegatingPool(null) {
            @Override public void execute(Runnable task) {
                try { run("ambient", () -> {
                    task.run(); owner("ambient"); BulkApplication.second("restored");
                }); } catch (Exception failure) { throw new AssertionError(failure); }
            }
        };
        check(ambient.invokeAll(List.<Callable<Integer>>of(() -> {
            check(CONTEXT.currentTest().isEmpty(), "no borrowed ambient owner"); BulkApplication.unowned(1); return 1;
        })).get(0).get() == 1, "ambient result");
    }

    static void rejected() throws Exception {
        ProbePool worker = new ProbePool(1);
        CountDownLatch executed = new CountDownLatch(1);
        ExecutorService rejecting = new DelegatingPool(worker) {
            int calls;
            @Override public void execute(Runnable task) {
                if (++calls == 2) throw new RejectedExecutionException("second");
                super.execute(task); await(executed);
            }
        };
        try {
            run("rejected", () -> {
                try { rejecting.invokeAll(List.<Callable<Integer>>of(() -> {
                    try { owner("rejected"); return BulkApplication.first(1); } finally { executed.countDown(); }
                }, () -> { BulkApplication.never(1); return 0; })); throw new AssertionError("must reject"); }
                catch (RejectedExecutionException expected) { check(expected.getMessage().equals("second"), "rejection preserved"); }
            });
        } finally { worker.shutdown(); check(worker.awaitTermination(10, TimeUnit.SECONDS), "reject pool stopped"); }
    }

    static void cancellation() throws Exception {
        ProbePool worker = new ProbePool(1);
        CountDownLatch occupied = new CountDownLatch(1), release = new CountDownLatch(1);
        worker.execute(() -> { occupied.countDown(); await(release); }); await(occupied);
        try {
            run("cancelBeforeStart", () -> {
                var tasks = List.<Callable<Integer>>of(() -> { BulkApplication.never(1); return 0; });
                var futures = worker.invokeAll(tasks, 0, TimeUnit.NANOSECONDS);
                check(futures.size() == 1 && futures.get(0).isCancelled(), "all timeout cancellation");
                try { futures.get(0).get(); throw new AssertionError("cancel result"); } catch (CancellationException expected) { }
                try { worker.invokeAny(tasks, 0, TimeUnit.NANOSECONDS); throw new AssertionError("any timeout"); }
                catch (TimeoutException expected) { }
            });
        } finally { release.countDown(); worker.shutdown(); check(worker.awaitTermination(10, TimeUnit.SECONDS), "cancel pool stopped"); }
    }

    static void interruption(boolean any, boolean timed) throws Exception {
        ProbePool worker = new ProbePool(1);
        CountDownLatch entered = new CountDownLatch(1), cancelled = new CountDownLatch(1);
        String id = (any ? "interruptAny" : "interruptAll") + (timed ? "Timed" : "");
        Thread caller = caller(() -> run(id, () -> {
            var tasks = List.<Callable<Integer>>of(() -> { owner(id); BulkApplication.competitor("interrupt"); entered.countDown();
                try { new CountDownLatch(1).await(); return 0; } finally { cancelled.countDown(); } });
            try {
                if (any) { if (timed) worker.invokeAny(tasks, 1, TimeUnit.DAYS); else worker.invokeAny(tasks); }
                else { if (timed) worker.invokeAll(tasks, 1, TimeUnit.DAYS); else worker.invokeAll(tasks); }
                throw new AssertionError("must interrupt");
            }
            catch (InterruptedException expected) { check(!Thread.currentThread().isInterrupted(), "interrupted status cleared"); }
            await(cancelled);
        }));
        try { caller.start(); await(entered); caller.interrupt(); join(caller); }
        finally { worker.shutdownNow(); check(worker.awaitTermination(10, TimeUnit.SECONDS), "interrupt pool stopped"); }
    }

    static void timeoutAndLate() throws Exception {
        ProbePool worker = new ProbePool(1);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(1);
        // invokeAny submits its first candidate before evaluating a zero timeout.
        ExecutorService gated = new DelegatingPool(worker) {
            @Override public void execute(Runnable task) { super.execute(task); await(entered); }
        };
        try {
            run("lateA", () -> {
                try { gated.invokeAny(List.<Callable<Integer>>of(() -> {
                    entered.countDown();
                    boolean interrupted = false;
                    for (;;) { try { release.await(); break; } catch (InterruptedException e) { interrupted = true; } }
                    try { owner("lateA"); BulkApplication.late(1); return 1; }
                    finally { if (interrupted) Thread.currentThread().interrupt(); done.countDown(); }
                }), 0, TimeUnit.NANOSECONDS); throw new AssertionError("must timeout"); }
                catch (TimeoutException expected) { }
            });
            run("lateB", () -> { release.countDown(); await(done); });
            check(observation("lateA").methods().isEmpty() && observation("lateA").unattributedEvents().stream()
                    .anyMatch(e -> e.reason() == UnattributedReason.LATE_EVENT), "late A");
            check(observation("lateB").methods().isEmpty(), "not B");
        } finally { release.countDown(); worker.shutdown(); check(worker.awaitTermination(10, TimeUnit.SECONDS), "late pool stopped"); }
    }

    static void setup(ExecutorService pool) throws Exception {
        CONTEXT.beginContainer("bulk-setup", "fixture.BulkTests", false);
        try { pool.invokeAll(List.<Callable<Integer>>of(() -> pool.submit(() -> {
            BulkApplication.setup(1); return pool.invokeAny(List.<Callable<Integer>>of(() -> BulkApplication.first(2)));
        }).get())).get(0).get(); }
        finally { CONTEXT.endContainer("bulk-setup"); }
    }
    static void closedSetup(ExecutorService pool) throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread closer = caller(() -> { await(entered); CONTEXT.endContainer("closed-bulk"); release.countDown(); });
        CONTEXT.beginContainer("closed-bulk", "fixture.BulkTests", false);
        closer.start();
        try { pool.invokeAll(List.<Callable<Integer>>of(() -> { entered.countDown(); await(release); BulkApplication.setup(1); return 1; })).get(0).get(); }
        finally { release.countDown(); CONTEXT.endContainer("closed-bulk"); join(closer); }
        check(CONTEXT.aggregator().snapshot().setupDiagnostics().stream().anyMatch(d -> d.detail().contains("closed JUnit container")), "closed setup diagnostic");
    }

    static void arguments(ExecutorService pool) throws Exception {
        check(pool.invokeAll(List.of()).isEmpty(), "empty all");
        check(pool.invokeAll(List.of(), 0, TimeUnit.NANOSECONDS).isEmpty(), "empty timed all");
        for (boolean timed : List.of(false, true)) {
            expect(IllegalArgumentException.class, () -> { if (timed) pool.invokeAny(List.of(), 1, TimeUnit.SECONDS); else pool.invokeAny(List.of()); });
            expect(NullPointerException.class, () -> { if (timed) pool.invokeAll(null, 1, TimeUnit.SECONDS); else pool.invokeAll(null); });
            expect(NullPointerException.class, () -> { if (timed) pool.invokeAny(null, 1, TimeUnit.SECONDS); else pool.invokeAny(null); });
            List<Callable<Integer>> invalid = Arrays.asList((Callable<Integer>) null);
            expect(NullPointerException.class, () -> { if (timed) pool.invokeAll(invalid, 1, TimeUnit.SECONDS); else pool.invokeAll(invalid); });
            expect(NullPointerException.class, () -> { if (timed) pool.invokeAny(invalid, 1, TimeUnit.SECONDS); else pool.invokeAny(invalid); });
        }
        expect(NullPointerException.class, () -> pool.invokeAll(List.of(), 1, null));
        expect(NullPointerException.class, () -> pool.invokeAny(List.of(), 1, null));
    }
    static void expect(Class<? extends Throwable> type, Checked work) throws Exception {
        try { work.run(); } catch (Throwable failure) { check(type.isInstance(failure), "expected " + type + ", got " + failure); return; }
        throw new AssertionError("expected " + type);
    }
    static Thread caller(Checked work) { return new Thread(() -> {
        try { work.run(); } catch (Throwable failure) { ASYNC_FAILURE.compareAndSet(null, failure); }
    }); }
    static void join(Thread thread) throws Exception { thread.join(10000); check(!thread.isAlive(), "caller finished");
        if (ASYNC_FAILURE.get() != null) throw new AssertionError(ASYNC_FAILURE.get()); }
    static void run(String id, Checked work) throws Exception {
        TestIdentity test = new TestIdentity(id, id, "fixture.BulkTests", id, "fixture", "fixture-run", CONTEXT.jvmId());
        CONTEXT.beginTest(test); try { work.run(); } finally { CONTEXT.endTest(test, SUCCESS); }
    }
    static RuntimeObservation.PhysicalTest observation(String id) { return CONTEXT.aggregator().snapshot().tests().stream()
            .filter(t -> t.identity().platformUniqueId().equals(id)).findFirst().orElseThrow(); }
    static void owner(String id) { check(CONTEXT.currentTest().orElseThrow().platformUniqueId().equals(id), "owner " + id); }
    static void await(CountDownLatch latch) { try { check(latch.await(10, TimeUnit.SECONDS), "latch timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); } }
    static void check(boolean value, String detail) { if (!value) throw new AssertionError(detail); }
    interface Checked { void run() throws Exception; }

    static class ProbePool extends ThreadPoolExecutor {
        ProbePool(int threads) { super(threads, threads, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>()); prestartAllCoreThreads(); }
        @Override protected void afterExecute(Runnable task, Throwable failure) {
            if (CONTEXT.currentTest().isPresent()) ASYNC_FAILURE.compareAndSet(null, new AssertionError("worker owner leaked"));
            if (CONTEXT.diagnosticContext().testIdentity() != null) ASYNC_FAILURE.compareAndSet(null, new AssertionError("worker marker leaked"));
        }
    }
    static class DelegatingPool extends AbstractExecutorService {
        final ExecutorService delegate;
        DelegatingPool(ExecutorService delegate) { this.delegate = delegate; }
        @Override public void execute(Runnable task) { delegate.execute(task); }
        @Override public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
            // This super call is instrumented too. AbstractExecutorService delegates via our execute.
            return super.invokeAll(tasks);
        }
        @Override public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
            return super.invokeAll(tasks, timeout, unit);
        }
        @Override public <T> Future<T> submit(Callable<T> task) { return delegate.submit(task); }
        public void shutdown() { delegate.shutdown(); }
        public List<Runnable> shutdownNow() { return delegate.shutdownNow(); }
        public boolean isShutdown() { return delegate.isShutdown(); }
        public boolean isTerminated() { return delegate.isTerminated(); }
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException { return delegate.awaitTermination(timeout, unit); }
    }
    static class SubmittingPool extends DelegatingPool {
        SubmittingPool(ExecutorService delegate) { super(delegate); }
        @Override public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
            List<Future<T>> result = new ArrayList<>();
            boolean completed = false;
            try {
                for (Callable<T> task : tasks) result.add(submit(task));
                for (Future<T> future : result) { try { future.get(); } catch (ExecutionException | CancellationException ignored) { } }
                completed = true; return result;
            } finally { if (!completed) for (Future<T> future : result) future.cancel(true); }
        }
    }
    public static class ClosedSetupMain { public static void main(String[] args) throws Exception { BulkExecutorFixtureMain.main(new String[]{"closed"}); } }
}
