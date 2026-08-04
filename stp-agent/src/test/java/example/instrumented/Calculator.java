// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.instrumented;

public class Calculator {
	public int add(int left, int right) {
		return adjust(left + right);
	}

	public long add(long left, long right) {
		return left + right;
	}

	public int divide(int dividend, int divisor) {
		return dividend / divisor;
	}

	public static int scale(int value, int factor) {
		return value * factor;
	}

	public synchronized int synchronizedAdd(int left, int right) {
		return left + right;
	}

	public int safeDivide(int dividend, int divisor) {
		try {
			return divide(dividend, divisor);
		} catch (ArithmeticException ignored) {
			return 0;
		}
	}

	public int classify(int value) {
		return value < 0 ? -1 : value == 0 ? 0 : 1;
	}

	private int adjust(int value) {
		return value;
	}
}

