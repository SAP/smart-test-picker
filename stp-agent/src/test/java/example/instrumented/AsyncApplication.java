// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.instrumented;

public final class AsyncApplication {
	public static final String CALLABLE_RESULT = new String("callable-result");
	public void single() { }
	public void executeRunnable() { }
	public void submitRunnable() { }
	public void reusedA() { }
	public void reusedB() { }
	public void reusedRunnable() { }
	public String reusedCallable() { return CALLABLE_RESULT; }
	public void fixedOne() { }
	public void fixedTwo() { }
	public String callable() { return CALLABLE_RESULT; }
	public void nestedOuter() { }
	public void nestedInner() { }
	public void failing() { throw Failure.INSTANCE; }
	public void delayed() { }
	public void unrelated() { }
	public void afterFailure() { }
	public void directThreadPool() { }
	public void customInterface() { }
	public void customImplementation() { }
	public void completableFuture() { }
	public void completableFutureCommon() { }
	public String completableFutureSupply() { return CALLABLE_RESULT; }
	public String completableFutureApply(String value) { return value; }
	public void completableFutureThenRun() { }
	public void forkJoinExecute() { }
	public String forkJoinSubmit() { return CALLABLE_RESULT; }
	public void scheduledRunnable() { }
	public String scheduledCallable() { return CALLABLE_RESULT; }
	public void scheduledFixedRate() { }
	public void scheduledFixedDelay() { }
	public void scheduledLatePeriodic() { }
	public void scheduledNestedOuter() { }
	public void scheduledNestedInner() { }
	public void scheduledFailure() { throw Failure.INSTANCE; }
	public void scheduledNoContext() { }
	public void rawThread() { }
	public void rawThreadNestedOuter() { }
	public void rawThreadNestedInner() { }
	public void rawThreadReused() { }
	public void rawThreadFailure() { throw Failure.INSTANCE; }
	public void rawNoContext() { }
	public void threadSubclass() { }
	public void forkJoinDirectFork() { }
	public void forkJoinPoolTaskSubmit() { }
	public void forkJoinPoolTaskInvoke() { }

	public static final class Failure extends RuntimeException {
		public static final Failure INSTANCE = new Failure();
		private Failure() { super("expected"); }
	}
}
