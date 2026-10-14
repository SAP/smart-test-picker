// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.remote;

/** Fixture mixing trivial accessors/ctor with one method carrying real logic, for skip-trivial tests. */
public class TrivialMembersFixture {
	private int value;

	public TrivialMembersFixture() { }

	public int getValue() { return value; }

	public void setValue(int value) { this.value = value; }

	public boolean isReady() { return value > 0; }

	public int compute(int factor) {
		int result = value;
		for (int i = 0; i < factor; i++) {
			result += i;
		}
		return result;
	}
}
