// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.fixture;

import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

/** Regression fixture for executor overloads whose Runnable is not the top stack value. */
public final class ExecutorOverloadVerificationFixtureMain {
	private ExecutorOverloadVerificationFixtureMain() { }

	public static void main(String[] args) throws Exception {
		TimedExecutor executor = (command, timeout) -> command.run();
		if (!"ok".equals(executor.submit(() -> "ok").get())) throw new AssertionError("result");
		System.out.println("executor-overload-fixture-ok");
	}

	private interface TimedExecutor extends Executor {
		void execute(Runnable command, long timeout);

		@Override
		default void execute(Runnable command) {
			execute(command, 0L);
		}

		default <V> Future<V> submit(Callable<V> callable) {
			FutureTask<V> task = new FutureTask<>(callable);
			execute(task, Long.MAX_VALUE);
			return task;
		}
	}
}
