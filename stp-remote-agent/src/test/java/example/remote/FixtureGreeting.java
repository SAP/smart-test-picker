// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.remote;

public interface FixtureGreeting {
	default String greet() { return "interface-default"; }
}
