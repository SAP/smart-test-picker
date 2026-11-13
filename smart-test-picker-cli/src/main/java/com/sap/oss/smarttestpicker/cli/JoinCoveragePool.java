// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.cli;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import com.sap.oss.smarttestpicker.coverage.model.*;

/** Invocation-local dictionaries: retain references, not repeated fragment method objects. */
final class JoinCoveragePool
{
    private final Map<String, String> classes = new HashMap<>();
    private final Map<MethodIdentity, MethodIdentity> methods = new HashMap<>();

    ExecutableCoverageFragment share(ExecutableCoverageFragment fragment)
    {
        Map<ExecutableTestIdentity, TestCoverage> tests = new HashMap<>();
        fragment.tests().forEach((id, coverage) -> {
            Set<MethodIdentity> hits = new HashSet<>();
            for (MethodIdentity method : coverage.coveredMethods())
            {
                MethodIdentity shared = methods.get(method);
                if (shared == null)
                {
                    shared = new MethodIdentity(owner(method.binaryClassName()), method.methodName(), method.jvmDescriptor());
                    methods.put(shared, shared);
                }
                hits.add(shared);
            }
            tests.put(id, new TestCoverage(owners(coverage.coveredClasses()), hits,
                    coverage.outcome(), coverage.collectionStatus()));
        });
        var scopes = fragment.setupScopes().stream().map(scope -> new SetupScope(scope.id(), scope.type(),
                owners(scope.coveredClasses()), scope.affectedContainers(), scope.owner())).toList();
        return new ExecutableCoverageFragment(fragment.schemaVersion(), fragment.revision(), fragment.shardId(),
                tests, fragment.unmapped(), scopes, fragment.collectionCompleted());
    }

    private String owner(String name) { return classes.computeIfAbsent(name, value -> value); }
    private Set<String> owners(Set<String> names)
    {
        Set<String> result = new HashSet<>();
        names.forEach(name -> result.add(owner(name)));
        return result;
    }
}
