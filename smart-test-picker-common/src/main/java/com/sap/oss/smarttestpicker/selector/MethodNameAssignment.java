// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.selector;

import java.util.Set;
import java.util.TreeSet;
import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;

/** Build-tool method-name filters cannot distinguish declared parameter types. */
public final class MethodNameAssignment {
    private MethodNameAssignment() { }

    public static void requireCompleteOverloads(String target, Set<TestIdentity> assigned,
            Set<TestIdentity> effectiveSelection) {
        Set<TestIdentity> missing = new TreeSet<>();
        Set<MethodName> names = new java.util.HashSet<>();
        assigned.forEach(test -> names.add(new MethodName(test.className(), test.methodName())));
        for (TestIdentity candidate : effectiveSelection) {
            if (!assigned.contains(candidate) && names.contains(new MethodName(candidate.className(), candidate.methodName()))) {
                missing.add(candidate);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Partial overload assignment for " + target + "; assigned identities: "
                    + new TreeSet<>(assigned) + "; missing overloads: " + missing
                    + ". Method-name filters require all effective overloads to be assigned together.");
        }
    }

    private record MethodName(String className, String methodName) { }
}
