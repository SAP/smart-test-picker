// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.fixture;

import com.sap.oss.smarttestpicker.runtime.RuntimeContextRegistry;
import com.sap.oss.smarttestpicker.runtime.RuntimeContextService;
import com.sap.oss.smarttestpicker.runtime.model.TestExecutionStatus;
import com.sap.oss.smarttestpicker.runtime.model.TestIdentity;
import com.sap.oss.smarttestpicker.runtime.model.TestResult;
import example.instrumented.AsyncApplication;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;

public final class ThreadBoundaryFixtureMain {
	private static final TestResult SUCCESS = new TestResult(TestExecutionStatus.SUCCESSFUL, null, null);
	private ThreadBoundaryFixtureMain() { }

	public static void main(String[] args) throws Exception {
		RuntimeContextService context = RuntimeContextRegistry.current().orElseThrow();
		AsyncApplication app = new AsyncApplication();
		ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
		ForkJoinPool forkJoin = new ForkJoinPool(1);
		try {
			run(context, test("scheduled-runnable"), () -> scheduler.schedule(app::scheduledRunnable, 1,
					TimeUnit.MILLISECONDS).get());
			run(context, test("scheduled-callable"), () -> check(AsyncApplication.CALLABLE_RESULT ==
					scheduler.schedule(app::scheduledCallable, 1, TimeUnit.MILLISECONDS).get(), "callable identity"));
			runPeriodic(context, scheduler, test("fixed-rate"), app::scheduledFixedRate, true);
			runPeriodic(context, scheduler, test("fixed-delay"), app::scheduledFixedDelay, false);
			run(context, test("scheduled-nested"), () -> scheduler.schedule(() -> {
				app.scheduledNestedOuter();
				try { scheduler.schedule(app::scheduledNestedInner, 0, TimeUnit.MILLISECONDS).get(); }
				catch (Exception failure) { throw new AssertionError(failure); }
			}, 0, TimeUnit.MILLISECONDS).get());
			run(context, test("scheduled-failure"), () -> {
				try { scheduler.schedule(app::scheduledFailure, 0, TimeUnit.MILLISECONDS).get(); }
				catch (java.util.concurrent.ExecutionException expected) { }
			});
			scheduler.schedule(app::scheduledNoContext, 0, TimeUnit.MILLISECONDS).get();
			CountDownLatch lateRan = new CountDownLatch(1);
			TestIdentity latePeriodic = test("late-periodic");
			context.beginTest(latePeriodic);
			ScheduledFuture<?> lateFuture = scheduler.scheduleAtFixedRate(() -> {
				app.scheduledLatePeriodic(); lateRan.countDown();
			}, 20, 20, TimeUnit.MILLISECONDS);
			context.endTest(latePeriodic, SUCCESS);
			check(lateRan.await(5, TimeUnit.SECONDS), "late periodic completion");
			lateFuture.cancel(false);

			Thread[] seen = new Thread[1];
			run(context, test("raw-thread"), () -> {
				Thread thread = new Thread(() -> { seen[0] = Thread.currentThread(); app.rawThread(); }, "stp-raw");
				thread.start();
				thread.join();
				check(seen[0] == thread && seen[0] != Thread.currentThread(), "raw physical thread");
			});
			Runnable reused = app::rawThreadReused;
			run(context, test("raw-reuse-a"), () -> join(new Thread(reused)));
			run(context, test("raw-reuse-b"), () -> join(new Thread(reused)));
			run(context, test("raw-failure"), () -> {
				Thread failed = new Thread(app::rawThreadFailure);
				failed.setUncaughtExceptionHandler((thread, failure) -> { });
				join(failed);
			});
			run(context, test("raw-nested"), () -> join(new Thread(() -> {
				app.rawThreadNestedOuter();
				try { join(new Thread(app::rawThreadNestedInner)); } catch (InterruptedException failure) {
					throw new AssertionError(failure);
				}
			})));
			join(new Thread(app::rawNoContext));
			TestIdentity subclass = test("thread-subclass-unsupported");
			context.beginTest(subclass);
			join(new ApplicationThread(app));
			context.endTest(subclass, SUCCESS);

			RecursiveAction submitted = action(app::forkJoinPoolTaskSubmit);
			run(context, test("fj-task-submit-unsupported"), () -> forkJoin.submit(submitted).get());
			RecursiveAction invoked = action(app::forkJoinPoolTaskInvoke);
			run(context, test("fj-task-invoke-unsupported"), () -> forkJoin.invoke(invoked));
			RecursiveAction forked = action(app::forkJoinDirectFork);
			run(context, test("fj-direct-fork-unsupported"), () -> forked.fork().get());
			System.out.println("thread-boundary-fixture-ok");
		} finally {
			scheduler.shutdownNow();
			scheduler.awaitTermination(5, TimeUnit.SECONDS);
			forkJoin.shutdownNow();
		}
	}
	private static RecursiveAction action(Runnable runnable) {
		return new RecursiveAction() { @Override protected void compute() { runnable.run(); } };
	}
	private static final class ApplicationThread extends Thread {
		private final AsyncApplication app;
		private ApplicationThread(AsyncApplication app) { this.app = app; }
		@Override public void run() { app.threadSubclass(); }
	}

	private static void runPeriodic(RuntimeContextService context, ScheduledExecutorService scheduler,
			TestIdentity identity, Runnable hit, boolean fixedRate) throws Exception {
		context.beginTest(identity);
		CountDownLatch twice = new CountDownLatch(2);
		AtomicInteger invocations = new AtomicInteger();
		Runnable task = () -> { hit.run(); invocations.incrementAndGet(); twice.countDown(); };
		ScheduledFuture<?> future = fixedRate
				? scheduler.scheduleAtFixedRate(task, 0, 1, TimeUnit.MILLISECONDS)
				: scheduler.scheduleWithFixedDelay(task, 0, 1, TimeUnit.MILLISECONDS);
		check(twice.await(5, TimeUnit.SECONDS), "periodic completion");
		future.cancel(false);
		context.endTest(identity, SUCCESS);
		check(invocations.get() >= 2, "periodic invocations");
	}

	private static void run(RuntimeContextService context, TestIdentity identity, Checked action) throws Exception {
		context.beginTest(identity);
		try { action.run(); } finally { context.endTest(identity, SUCCESS); }
	}

	private static void join(Thread thread) throws InterruptedException { thread.start(); thread.join(); }
	private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
	private static TestIdentity test(String id) {
		return new TestIdentity(id, id, "fixture.ThreadBoundaryTests", id, "fixture", "fixture-run",
				RuntimeContextRegistry.current().orElseThrow().jvmId());
	}
	@FunctionalInterface private interface Checked { void run() throws Exception; }
}
