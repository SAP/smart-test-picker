// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.fixture;

import com.sap.oss.smarttestpicker.runtime.RuntimeContextRegistry;
import com.sap.oss.smarttestpicker.runtime.model.TestExecutionStatus;
import com.sap.oss.smarttestpicker.runtime.model.TestIdentity;
import com.sap.oss.smarttestpicker.runtime.model.TestResult;
import example.instrumented.AsyncApplication;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class ExecutorPropagationFixtureMain {
	private static final TestResult SUCCESS = new TestResult(TestExecutionStatus.SUCCESSFUL, null, null);

	private ExecutorPropagationFixtureMain() { }

	public static void main(String[] args) throws Exception {
		var context = RuntimeContextRegistry.current().orElseThrow();
		AsyncApplication application = new AsyncApplication();
		ExecutorService single = Executors.newSingleThreadExecutor();
		ExecutorService fixed = Executors.newFixedThreadPool(2);
		try {
			run(context, test("single"), () -> single.submit(application::single).get());
			run(context, test("reuse-a"), () -> single.submit(application::reusedA).get());
			run(context, test("reuse-b"), () -> single.submit(application::reusedB).get());
			run(context, test("fixed"), () -> {
				Future<?> one = fixed.submit(application::fixedOne);
				Future<?> two = fixed.submit(application::fixedTwo);
				one.get();
				two.get();
			});
			run(context, test("callable"), () -> check("callable-result".equals(
					single.submit((Callable<String>) application::callable).get()), "callable result"));
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
			single.awaitTermination(5, TimeUnit.SECONDS);
			fixed.awaitTermination(5, TimeUnit.SECONDS);
		}
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
