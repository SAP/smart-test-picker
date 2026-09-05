// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.instrumented;

public final class AsyncApplication {
	public void single() { }
	public void reusedA() { }
	public void reusedB() { }
	public void fixedOne() { }
	public void fixedTwo() { }
	public String callable() { return "callable-result"; }
	public void nestedOuter() { }
	public void nestedInner() { }
	public void failing() { throw Failure.INSTANCE; }
	public void delayed() { }
	public void unrelated() { }
	public void afterFailure() { }

	public static final class Failure extends RuntimeException {
		public static final Failure INSTANCE = new Failure();
		private Failure() { super("expected"); }
	}
}
