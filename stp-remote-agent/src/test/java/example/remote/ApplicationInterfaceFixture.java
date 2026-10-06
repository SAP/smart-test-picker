// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.remote;

public interface ApplicationInterfaceFixture {
	default String defaultMethod() { return "ok"; }
	static String staticMethod() { return "static-ok"; }
}
