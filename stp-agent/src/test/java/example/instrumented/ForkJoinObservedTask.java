// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.instrumented;

import java.util.concurrent.RecursiveTask;

/** Both typed compute and its compiler bridge must be recorded inside the execution scope. */
public final class ForkJoinObservedTask extends RecursiveTask<Integer> {
	@Override protected Integer compute() {
		try {
			int answer = 42;
			if (answer == 42) return answer;
			throw new IllegalStateException();
		} catch (IllegalStateException failure) {
			return -1;
		}
	}
}
