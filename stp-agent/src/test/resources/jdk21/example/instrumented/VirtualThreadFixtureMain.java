// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.instrumented;

import com.sap.oss.smarttestpicker.runtime.RuntimeContextRegistry;
import com.sap.oss.smarttestpicker.runtime.RuntimeContextService;
import com.sap.oss.smarttestpicker.runtime.model.TestExecutionStatus;
import com.sap.oss.smarttestpicker.runtime.model.TestIdentity;
import com.sap.oss.smarttestpicker.runtime.model.TestResult;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class VirtualThreadFixtureMain {
	private static final TestResult SUCCESS = new TestResult(TestExecutionStatus.SUCCESSFUL, null, null);
	public static void main(String[] args) throws Exception {
		RuntimeContextService context = RuntimeContextRegistry.current().orElseThrow();
		Application app = new Application();
		run(context, test("start-virtual"), () -> join(Thread.startVirtualThread(app::startVirtual)));
		run(context, test("builder-virtual"), () -> join(Thread.ofVirtual().start(app::builderVirtual)));
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			run(context, test("virtual-executor"), () -> executor.submit(app::virtualExecutor).get());
			run(context, test("virtual-failure"), () -> {
				try { executor.submit(app::failure).get(); } catch (java.util.concurrent.ExecutionException expected) { }
			});
			executor.submit(app::noContext).get();
		}
		System.out.println("virtual-thread-fixture-ok");
	}
	private static void join(Thread thread) throws InterruptedException {
		if (!thread.isVirtual()) throw new AssertionError("not virtual");
		thread.join();
	}
	private static void run(RuntimeContextService context, TestIdentity identity, Checked action) throws Exception {
		context.beginTest(identity);
		try { action.run(); } finally { context.endTest(identity, SUCCESS); }
	}
	private static TestIdentity test(String id) {
		return new TestIdentity(id, id, "fixture.VirtualThreadTests", id, "fixture", "fixture-run",
				RuntimeContextRegistry.current().orElseThrow().jvmId());
	}
	@FunctionalInterface private interface Checked { void run() throws Exception; }
	public static final class Application {
		public void startVirtual() { nested(); }
		public void builderVirtual() { }
		public void virtualExecutor() { }
		public void failure() { throw new IllegalStateException("expected"); }
		public void noContext() { }
		public void nested() { }
	}
}
