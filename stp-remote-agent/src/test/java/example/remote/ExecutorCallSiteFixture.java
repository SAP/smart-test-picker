// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.remote;

import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

public final class ExecutorCallSiteFixture {
	public void execute(Executor executor, Runnable task) { executor.execute(task); }
	public Future<?> submitRunnable(ExecutorService executor, Runnable task) { return executor.submit(task); }
	public Future<String> submitRunnableResult(ExecutorService executor, Runnable task) {
		return executor.submit(task, "result");
	}
	public <T> Future<T> submitCallable(ExecutorService executor, Callable<T> task) { return executor.submit(task); }
	public void executeCustom(RemoteServletFixtureMain.FixtureExecutor executor, Runnable task) { executor.execute(task); }
}
