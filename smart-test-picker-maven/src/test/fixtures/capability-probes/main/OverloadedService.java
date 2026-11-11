// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example;

/** The overload probe from GradleCollectorBackendFunctionalTest. */
public class OverloadedService {
    public String value(String input) { return input.trim(); }
    public int value(int input) { return input + 1; }
}
