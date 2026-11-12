// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.audit;

/** Reflection fixture: parameter spellings must come from the real JUnit producer. */
public class ParameterProducerProbe {
    public static class Nested {}

    public Object[] arrays(String text, int[] numbers, String[][] strings, Nested[] nested) {
        return new Object[] {text, numbers, strings, nested};
    }
}
