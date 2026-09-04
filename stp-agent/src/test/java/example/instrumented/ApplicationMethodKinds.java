// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.instrumented;

import java.util.function.IntUnaryOperator;

public final class ApplicationMethodKinds {
	static int initialized;

	static {
		initialized = 41;
	}

	private final int value;

	public ApplicationMethodKinds(int value) {
		this.value = value;
	}

	public int lambda(int input) {
		IntUnaryOperator operator = candidate -> candidate + value;
		return operator.applyAsInt(input);
	}

	public Runnable anonymous() {
		return new Runnable() {
			@Override
			public void run() {
				initialized++;
			}
		};
	}

	public record SampleRecord(String name) {
	}

	public interface Defaults {
		default int publicDefault(int input) {
			return privateDefault(input) + 1;
		}

		private int privateDefault(int input) {
			return input * 2;
		}
	}
}
