// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.instrumented;

public final class InstrumentedFixtureMain {
	private InstrumentedFixtureMain() {
	}

	public static void main(String[] args) {
		Calculator calculator = new Calculator();
		check(calculator.add(2, 3) == 5, "int add");
		check(calculator.add(4L, 7L) == 11L, "long add");
		check(calculator.divide(8, 2) == 4, "divide");
		try {
			calculator.divide(1, 0);
			throw new AssertionError("divide should throw");
		} catch (ArithmeticException expected) {
			// Expected and behaviorally unchanged.
		}
		check(Calculator.scale(3, 4) == 12, "static method");
		check(calculator.synchronizedAdd(5, 6) == 11, "synchronized method");
		check(calculator.safeDivide(9, 3) == 3, "try path");
		check(calculator.safeDivide(9, 0) == 0, "catch path");
		check(calculator.classify(-2) == -1 && calculator.classify(0) == 0
				&& calculator.classify(2) == 1, "branches");
		check(new GreetingService().greet("Ada").equals("Hello, Ada!"), "object return and private helper");
		System.out.println("instrumented-fixture-ok");
	}

	private static void check(boolean condition, String description) {
		if (!condition) throw new AssertionError(description);
	}
}

