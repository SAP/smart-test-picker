// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.selector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.sap.oss.smarttestpicker.coverage.model.SetupScope;
import com.sap.oss.smarttestpicker.coverage.model.TestContainer;
import com.sap.oss.smarttestpicker.coverage.model.TestCoverage;
import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;
import com.sap.oss.smarttestpicker.coverage.model.UnmappedTest;

/** Pure schema-v2 mandatory-test policy. All ingress and change analysis belongs to the analyzer. */
public final class SchemaV2TestSelector
{
	public SelectionOutput select(SelectionAnalysisResult analysis)
	{
		if (analysis == null)
			return fullSuite("Schema-v2 selection analysis is missing");
		if (analysis.isRunAll())
			return fullSuite(analysis.reason());
		return select(analysis.context().orElse(null));
	}

	public SelectionOutput select(SelectionContext context)
	{
		if (context == null || context.map() == null || context.changedClasses() == null
				|| context.headTests() == null || context.newTests() == null || context.deletedTests() == null
				|| context.changedTests() == null)
			return fullSuite("Schema-v2 selection context is incomplete");

		try
		{
			NavigableSet<TestIdentity> mandatory = new TreeSet<>();
			Set<TestIdentity> head = context.headTests();
			for (Map.Entry<TestIdentity, TestCoverage> entry : context.map().tests().entrySet())
				if (intersects(entry.getValue().coveredClasses(), context.changedClasses()))
					mandatory.add(entry.getKey());

			Map<TestContainer, NavigableSet<TestIdentity>> byContainer = new TreeMap<>();
			for (TestIdentity test : head)
				byContainer.computeIfAbsent(new TestContainer(test.className()), ignored -> new TreeSet<>()).add(test);
			for (SetupScope scope : context.map().setupScopes())
			{
				if (!intersects(scope.coveredClasses(), context.changedClasses())) continue;
				for (TestContainer container : scope.affectedContainers())
				{
					Set<TestIdentity> members = byContainer.get(container);
					if (members == null || members.isEmpty())
						return fullSuite("Setup scope " + scope.id() + " references absent head container " + container.binaryName());
					mandatory.addAll(members);
				}
			}

			Map<String, String> unmappedDiagnostics = new LinkedHashMap<>();
			for (UnmappedTest unmapped : context.map().unmapped())
				if (head.contains(unmapped.test()))
				{
					mandatory.add(unmapped.test());
					unmappedDiagnostics.put(unmapped.test().toString(), unmapped.reason().name());
				}
			mandatory.addAll(context.newTests());
			mandatory.addAll(context.changedTests());
			mandatory.removeAll(context.deletedTests());
			mandatory.retainAll(head);

			List<String> selected = mandatory.stream().map(TestIdentity::toString).toList();
			List<String> changedClasses = new ArrayList<>(new TreeSet<>(context.changedClasses()));
			SelectionOutput output = new SelectionOutput(selected.isEmpty() ? "NONE" : "SELECTED",
					selected.isEmpty() ? "No mandatory tests selected" : selected.size() + " mandatory test(s) selected",
					selected, unmappedDiagnostics);
			output.setChangedClasses(changedClasses);
			return output;
		}
		catch (RuntimeException exception)
		{
			return fullSuite("Schema-v2 selector inconsistency: " + exception.getMessage());
		}
	}

	private static boolean intersects(Set<String> left, Set<String> right)
	{
		for (String value : left) if (right.contains(value)) return true;
		return false;
	}

	private static SelectionOutput fullSuite(String reason)
	{
		return new SelectionOutput("FULL_SUITE", reason, List.of(), Map.of());
	}
}
