// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.instrumented;

public class GreetingService {
	public String greet(String name) {
		return "Hello, " + name + suffix();
	}

	private String suffix() {
		return "!";
	}
}

