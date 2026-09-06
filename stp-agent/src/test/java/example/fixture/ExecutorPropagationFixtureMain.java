// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.fixture;

import com.sap.oss.smarttestpicker.runtime.RuntimeContextRegistry;
import com.sap.oss.smarttestpicker.runtime.model.TestExecutionStatus;
import com.sap.oss.smarttestpicker.runtime.model.TestIdentity;
import com.sap.oss.smarttestpicker.runtime.model.TestResult;
import example.instrumented.AsyncApplication;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;

public final class ExecutorPropagationFixtureMain {
	private static final TestResult SUCCESS = new TestResult(TestExecutionStatus.SUCCESSFUL, null, null);

	private ExecutorPropagationFixtureMain() { }

	public static void main(String[] args) throws Exception {
		var context = RuntimeContextRegistry.current().orElseThrow();
		AsyncApplication application = new AsyncApplication();
		ExecutorService single = Executors.newSingleThreadExecutor();
		ExecutorService fixed = Executors.newFixedThreadPool(2);
		ThreadPoolExecutor direct = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
				new LinkedBlockingQueue<>());
		CustomExecutor custom = new CustomExecutor();
		ForkJoinPool forkJoin = new ForkJoinPool(2);
		try {
			run(context, test("single"), () -> single.submit(application::single).get());
			Executor executor = single;
			run(context, test("execute-runnable"), () -> {
				CountDownLatch done = new CountDownLatch(1);
				executor.execute(() -> { application.executeRunnable(); done.countDown(); });
				check(done.await(5, TimeUnit.SECONDS), "execute runnable completion");
			});
			run(context, test("submit-runnable"), () ->
					single.submit(application::submitRunnable).get());
			run(context, test("reuse-a"), () -> single.submit(application::reusedA).get());
			run(context, test("reuse-b"), () -> single.submit(application::reusedB).get());
			run(context, test("fixed"), () -> {
				Future<?> one = fixed.submit(application::fixedOne);
				Future<?> two = fixed.submit(application::fixedTwo);
				one.get();
				two.get();
			});
			run(context, test("callable"), () -> check(AsyncApplication.CALLABLE_RESULT ==
					single.submit((Callable<String>) application::callable).get(), "callable result identity"));
			Runnable reusedRunnable = application::reusedRunnable;
			run(context, test("same-runnable-a"), () -> single.submit(reusedRunnable).get());
			run(context, test("same-runnable-b"), () -> single.submit(reusedRunnable).get());
			Callable<String> reusedCallable = application::reusedCallable;
			run(context, test("same-callable-a"), () -> single.submit(reusedCallable).get());
			run(context, test("same-callable-b"), () -> single.submit(reusedCallable).get());
			run(context, test("nested"), () -> fixed.submit(() -> {
				application.nestedOuter();
				try {
					fixed.submit(application::nestedInner).get();
				} catch (Exception failure) {
					throw new AssertionError(failure);
				}
			}).get());
			run(context, test("failure"), () -> {
				Future<?> failed = single.submit(application::failing);
				ExecutionException failure = expect(ExecutionException.class, failed::get);
				check(failure.getCause() == AsyncApplication.Failure.INSTANCE, "exception identity");
			});
			run(context, test("direct-thread-pool"), () -> direct.submit(application::directThreadPool).get());
			NarrowExecutor narrow = custom;
			run(context, test("custom-interface"), () -> {
				CountDownLatch done = new CountDownLatch(1);
				narrow.execute(() -> { application.customInterface(); done.countDown(); });
				check(done.await(5, TimeUnit.SECONDS), "custom interface completion");
			});
			run(context, test("custom-implementation"), () -> {
				CountDownLatch done = new CountDownLatch(1);
				custom.execute(() -> { application.customImplementation(); done.countDown(); });
				check(done.await(5, TimeUnit.SECONDS), "custom implementation completion");
			});
			run(context, test("cf-explicit-run"), () ->
					CompletableFuture.runAsync(application::completableFuture, direct).get());
			run(context, test("cf-common-run"), () ->
					CompletableFuture.runAsync(application::completableFutureCommon).get());
			run(context, test("cf-explicit-supply"), () -> check(AsyncApplication.CALLABLE_RESULT ==
					CompletableFuture.supplyAsync(application::completableFutureSupply, direct).get(),
					"supplier result identity"));
			run(context, test("cf-common-supply"), () -> check(AsyncApplication.CALLABLE_RESULT ==
					CompletableFuture.supplyAsync(application::completableFutureSupply).get(),
					"common supplier result identity"));
			run(context, test("cf-common-then-apply"), () -> check(AsyncApplication.CALLABLE_RESULT ==
					CompletableFuture.completedFuture(AsyncApplication.CALLABLE_RESULT)
							.thenApplyAsync(application::completableFutureApply).get(),
					"function result identity"));
			run(context, test("cf-explicit-then-apply"), () -> check(AsyncApplication.CALLABLE_RESULT ==
					CompletableFuture.completedFuture(AsyncApplication.CALLABLE_RESULT)
							.thenApplyAsync(application::completableFutureApply, direct).get(),
					"explicit function result identity"));
			run(context, test("cf-explicit-then-run"), () ->
					CompletableFuture.completedFuture("ready")
							.thenRunAsync(application::completableFutureThenRun, direct).get());
			run(context, test("cf-common-then-run"), () ->
					CompletableFuture.completedFuture("ready")
							.thenRunAsync(application::completableFutureThenRun).get());
			run(context, test("forkjoin-execute"), () -> {
				CountDownLatch done = new CountDownLatch(1);
				forkJoin.execute(() -> { application.forkJoinExecute(); done.countDown(); });
				check(done.await(5, TimeUnit.SECONDS), "fork/join execute completion");
			});
			run(context, test("forkjoin-submit"), () -> check(AsyncApplication.CALLABLE_RESULT ==
					forkJoin.submit((Callable<String>) application::forkJoinSubmit).get(),
					"fork/join callable result identity"));

			ThreadPoolExecutor rejecting = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
					new LinkedBlockingQueue<>());
			rejecting.shutdown();
			run(context, test("rejected"), () ->
					expect(RejectedExecutionException.class, () -> rejecting.execute(application::single)));

			CountDownLatch release = new CountDownLatch(1);
			Future<?> blocker = single.submit((Callable<Void>) () -> { release.await(); return null; });
			TestIdentity delayed = test("delayed");
			context.beginTest(delayed);
			Future<?> afterEnd = single.submit(application::delayed);
			context.endTest(delayed, SUCCESS);
			release.countDown();
			blocker.get();
			afterEnd.get();

			single.submit(application::unrelated).get();
			CountDownLatch cancelRelease = new CountDownLatch(1);
			Future<?> cancelBlocker = single.submit((Callable<Void>) () -> { cancelRelease.await(); return null; });
			Future<?> cancelled = single.submit(() -> { throw new AssertionError("cancelled task ran"); });
			check(cancelled.cancel(false), "task cancellation");
			cancelRelease.countDown();
			cancelBlocker.get();
			single.submit(application::afterFailure).get();
			System.out.println("executor-propagation-fixture-ok");
		} finally {
			single.shutdownNow();
			fixed.shutdownNow();
			direct.shutdownNow();
			custom.shutdown();
			forkJoin.shutdownNow();
			single.awaitTermination(5, TimeUnit.SECONDS);
			fixed.awaitTermination(5, TimeUnit.SECONDS);
		}
	}

	private interface NarrowExecutor extends Executor { }

	private static final class CustomExecutor implements NarrowExecutor {
		private final ExecutorService delegate = Executors.newSingleThreadExecutor();
		@Override public void execute(Runnable command) { delegate.execute(command); }
		void shutdown() { delegate.shutdownNow(); }
	}

	private static void run(com.sap.oss.smarttestpicker.runtime.RuntimeContextService context,
			TestIdentity identity, Checked action) throws Exception {
		context.beginTest(identity);
		try {
			action.run();
		} finally {
			context.endTest(identity, SUCCESS);
		}
	}

	private static TestIdentity test(String id) {
		return new TestIdentity(id, id, "fixture.ExecutorTests", id, "fixture", "fixture-run",
				RuntimeContextRegistry.current().orElseThrow().jvmId());
	}

	private static <T extends Throwable> T expect(Class<T> type, Checked action) throws Exception {
		try {
			action.run();
		} catch (Throwable failure) {
			if (type.isInstance(failure)) return type.cast(failure);
			throw failure;
		}
		throw new AssertionError("expected " + type.getName());
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	@FunctionalInterface
	private interface Checked { void run() throws Exception; }
}
