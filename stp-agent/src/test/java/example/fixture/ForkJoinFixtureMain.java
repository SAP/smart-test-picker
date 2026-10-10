// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.fixture;

import com.sap.oss.smarttestpicker.runtime.*;
import com.sap.oss.smarttestpicker.runtime.model.*;
import example.instrumented.ForkJoinApplication;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Real application call sites; no explicit context wrapping or capture calls. */
public class ForkJoinFixtureMain {
	static final RuntimeContextService CONTEXT = RuntimeContextRegistry.current().orElseThrow();
	static final TestResult SUCCESS = new TestResult(TestExecutionStatus.SUCCESSFUL, null, null);

	public static void main(String[] args) throws Exception {
		ForkJoinPool pool = new CustomPool(1);
		try {
			if (args.length != 0 && args[0].equals("unsupported")) { unsupported(pool); return; }
			Action outside = new Action(() -> { owner("fork"); ForkJoinApplication.fork(1); });
			run("fork", () -> { check(outside.fork() == outside, "fork task identity"); outside.get(5, TimeUnit.SECONDS); });
			run("direct", () -> new Action(() -> { owner("direct"); ForkJoinApplication.direct(1); }).invoke());
			run("execute", () -> { Action task = new Action(() -> { owner("execute"); ForkJoinApplication.execute(1); });
				pool.execute(task); task.get(5, TimeUnit.SECONDS); });
			run("submit", () -> { Action task = new Action(() -> { owner("submit"); ForkJoinApplication.submit(1); });
				check(pool.submit(task) == task, "submit task identity"); task.get(5, TimeUnit.SECONDS); });
			run("invoke", () -> check(pool.invoke(new Value()) == 42, "result"));
			run("recursive", () -> pool.invoke(new Tree(3)));
			run("custom", () -> check(pool.invoke(new CustomTask()) == 7, "custom exec result"));

			var observedTask = new example.instrumented.ForkJoinObservedTask();
			run("compute", () -> check(pool.invoke(observedTask) == 42, "instrumented compute result"));
			AtomicReference<Thread> worker = new AtomicReference<>();
			Action reused = new Action(() -> {
				Thread previous = worker.getAndSet(Thread.currentThread());
				check(previous == null || previous == Thread.currentThread(), "same worker reused");
				ForkJoinApplication.reuse(1);
			});
			run("reuseA", () -> pool.invoke(reused)); reused.reinitialize();
			run("reuseB", () -> pool.invoke(reused));
			for (boolean error : new boolean[]{false, true}) {
				run(error ? "error" : "exception", () -> {
				Action failed = new Action(() -> { ForkJoinApplication.failure(1);
					if (error) throw new AssertionError("expected"); throw new IllegalStateException("expected"); });
				pool.execute(failed);
				try { failed.get(5, TimeUnit.SECONDS); throw new AssertionError("must fail"); }
				catch (ExecutionException e) { check(error ? e.getCause() instanceof AssertionError
						: e.getCause() instanceof IllegalStateException, "failure type"); }
				});
				// Generic Callable overload, no active owner: original callback, no ForkJoin task scope.
				check(pool.submit((Callable<Boolean>) () -> Thread.currentThread() == worker.get()
						&& CONTEXT.currentTest().isEmpty()).get(5, TimeUnit.SECONDS), "raw worker clean after failure");
			}
			// No active test here, although the caller retains its last-finished marker.
			pool.invoke(new Action(() -> { check(Thread.currentThread() == worker.get(), "cleanup worker");
				check(CONTEXT.currentTest().isEmpty(), "worker cleaned"); ForkJoinApplication.unowned(1); }));
			check(pool.submit((Callable<Boolean>) () -> CONTEXT.currentTest().isEmpty()).get(5, TimeUnit.SECONDS),
					"raw worker clean after no-owner task");
			run("cancelled", () -> {
				CountDownLatch busy = new CountDownLatch(1), release = new CountDownLatch(1);
				Action blocker = new Action(() -> { busy.countDown(); await(release); });
				pool.execute(blocker); await(busy);
				Action cancelled = new Action(() -> ForkJoinApplication.cancelled(1));
				try { pool.execute(cancelled); check(cancelled.cancel(false), "cancelled"); }
				finally { release.countDown(); }
				blocker.get(5, TimeUnit.SECONDS);
				try { cancelled.join(); throw new AssertionError("cancellation result"); }
				catch (CancellationException expected) { }
			});
			parallel();
			late(pool);
			setup(pool, false);
			check(CONTEXT.aggregator().snapshot().setupDiagnostics().isEmpty(), "supported diagnostics: "
					+ CONTEXT.aggregator().snapshot().setupDiagnostics());
			System.out.println("forkjoin-supported-ok");
		} finally { pool.shutdown(); check(pool.awaitTermination(10, TimeUnit.SECONDS), "pool terminated"); }
	}

	static void parallel() throws Exception {
		ForkJoinPool pool = new ForkJoinPool(2);
		CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Runnable a = () -> { try { run("parallelA", () -> pool.invoke(new Action(() -> {
			entered.countDown(); await(release); owner("parallelA"); ForkJoinApplication.parallelA(1);
		}))); } catch (Throwable t) { failure.set(t); } };
		Runnable b = () -> { try { run("parallelB", () -> pool.invoke(new Action(() -> {
			entered.countDown(); await(release); owner("parallelB"); ForkJoinApplication.parallelB("B");
		}))); } catch (Throwable t) { failure.set(t); } };
		Thread one = new Thread(a), two = new Thread(b);
		try { one.start(); two.start(); await(entered); release.countDown(); one.join(10000); two.join(10000);
			check(!one.isAlive() && !two.isAlive(), "concurrent callers finished");
			if (failure.get() != null) throw new AssertionError(failure.get());
		} finally { release.countDown(); pool.shutdownNow(); }
	}
	static void late(ForkJoinPool pool) throws Exception {
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		Action blocker = new Action(() -> { entered.countDown(); await(release); });
		pool.execute(blocker); await(entered);
		Action task = new Action(() -> { owner("lateA"); ForkJoinApplication.late(1); });
		run("lateA", () -> pool.execute(task));
		try { run("lateB", () -> { release.countDown(); task.get(5, TimeUnit.SECONDS); }); }
		finally { release.countDown(); }
		blocker.get(5, TimeUnit.SECONDS);
		var a = observation("lateA");
		check(a.methods().isEmpty() && a.unattributedEvents().stream().anyMatch(e -> e.reason() == UnattributedReason.LATE_EVENT), "late A");
		check(observation("lateB").methods().isEmpty(), "no late attribution B");
	}

	static void setup(ForkJoinPool pool, boolean close) throws Exception {
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		CONTEXT.beginContainer("container", "fixture.SetupTests", false);
		Action task = new Action(() -> { entered.countDown(); await(release);
			new Action(() -> ForkJoinApplication.setup(1)).fork().join(); });
		pool.execute(task); await(entered);
		if (close) CONTEXT.endContainer("container");
		release.countDown(); task.get(5, TimeUnit.SECONDS);
		if (!close) CONTEXT.endContainer("container");
	}
	static void unsupported(ForkJoinPool pool) throws Exception {
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		Action task = new Action(() -> { entered.countDown(); await(release); ForkJoinApplication.ambiguous(1); });
		run("ambiguousA", () -> { pool.execute(task); await(entered); });
		try { run("ambiguousB", () -> { pool.execute(task); release.countDown(); task.get(5, TimeUnit.SECONDS); }); }
		finally { release.countDown(); }
		check(observation("ambiguousA").methods().isEmpty() && observation("ambiguousB").methods().isEmpty(), "ambiguous quarantined");
		run("unobservedSubmission", () -> ForkJoinTask.adapt(() -> ForkJoinApplication.unowned(1)).quietlyInvoke());
		check(observation("unobservedSubmission").methods().isEmpty(), "no guessed owner for unobserved API");
		setup(pool, true);
		check(!CONTEXT.aggregator().snapshot().setupDiagnostics().isEmpty(), "incomplete diagnostics");
		System.out.println("forkjoin-unsupported-ok");
	}
	static RuntimeObservation.PhysicalTest observation(String id) {
		return CONTEXT.aggregator().snapshot().tests().stream().filter(t -> t.identity().platformUniqueId().equals(id)).findFirst().orElseThrow();
	}
	static void run(String id, Checked action) throws Exception {
		TestIdentity test = new TestIdentity(id, id, "fixture.ForkJoinTests", id, "fixture", "fixture-run", CONTEXT.jvmId());
		CONTEXT.beginTest(test); try { action.run(); } finally { CONTEXT.endTest(test, SUCCESS); }
	}
	static void owner(String id) { check(CONTEXT.currentTest().orElseThrow().platformUniqueId().equals(id), "owner " + id); }
	static void await(CountDownLatch latch) {
		try { check(latch.await(10, TimeUnit.SECONDS), "latch timeout"); }
		catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
	}
	static void check(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
	@FunctionalInterface interface Checked { void run() throws Exception; }
	static class Action extends RecursiveAction {
		final Runnable work;
		Action(Runnable work) { this.work = work; }
		@Override protected void compute() { work.run(); }
	}
	static class Value extends RecursiveTask<Integer> {
		@Override protected Integer compute() { owner("invoke"); return ForkJoinApplication.invoke(42); }
	}
	static class Tree extends RecursiveAction {
		final int depth;
		Tree(int depth) { this.depth = depth; }
		@Override protected void compute() {
			owner("recursive"); ForkJoinApplication.recursive(depth);
			if (depth > 0) { Tree left = new Tree(depth - 1); left.fork(); new Tree(depth - 1).invoke(); left.join(); }
		}
	}
	static class CustomTask extends ForkJoinTask<Integer> {
		int result;
		@Override protected boolean exec() { owner("custom"); ForkJoinApplication.custom(1); result = 7; return true; }
		@Override public Integer getRawResult() { return result; }
		@Override protected void setRawResult(Integer result) { this.result = result; }
	}
	static class CustomPool extends ForkJoinPool { CustomPool(int threads) { super(threads); } }
	public static class UnsupportedMain { public static void main(String[] args) throws Exception {
		ForkJoinFixtureMain.main(new String[]{"unsupported"});
	} }
}
