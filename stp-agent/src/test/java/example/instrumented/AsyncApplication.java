// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.instrumented;

public final class AsyncApplication {
	public static final String CALLABLE_RESULT = new String("callable-result");
	public void single() { }
	public void reusedA() { }
	public void reusedB() { }
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

	public static final class Failure extends RuntimeException {
		public static final Failure INSTANCE = new Failure();
		private Failure() { super("expected"); }
	}
}
