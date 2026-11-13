// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.serialization;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.sap.oss.smarttestpicker.coverage.model.ExecutableCoverageMap;
import com.sap.oss.smarttestpicker.coverage.model.MethodIdentity;

/** Map-only dictionaries; fragment and assignment representations do not use them. */
final class CompactMapTables
{
    static final Comparator<MethodIdentity> METHOD_ORDER = Comparator.comparing(MethodIdentity::binaryClassName)
            .thenComparing(MethodIdentity::methodName).thenComparing(MethodIdentity::jvmDescriptor);

    final List<String> classes;
    final List<MethodIdentity> methods;
    final Map<String, Integer> classIds;
    final Map<MethodIdentity, Integer> methodIds;
    final boolean derivedClasses;

    CompactMapTables(ExecutableCoverageMap map)
    {
        Set<String> names = new HashSet<>();
        Set<MethodIdentity> identities = new HashSet<>();
        boolean derived = true;
        for (var coverage : map.tests().values())
        {
            names.addAll(coverage.coveredClasses());
            identities.addAll(coverage.coveredMethods());
            Set<String> owners = new HashSet<>();
            coverage.coveredMethods().forEach(method -> owners.add(method.binaryClassName()));
            names.addAll(owners);
            derived &= owners.equals(coverage.coveredClasses());
        }
        map.setupScopes().forEach(scope -> names.addAll(scope.coveredClasses()));
        classes = names.stream().sorted().toList();
        methods = identities.stream().sorted(METHOD_ORDER).toList();
        classIds = indices(classes);
        methodIds = indices(methods);
        derivedClasses = derived;
    }

    private static <T> Map<T, Integer> indices(List<T> values)
    {
        Map<T, Integer> result = new HashMap<>();
        for (int i = 0; i < values.size(); i++)
        {
            result.put(values.get(i), i);
        }
        return result;
    }
}
