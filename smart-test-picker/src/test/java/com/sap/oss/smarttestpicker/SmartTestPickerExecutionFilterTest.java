// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.gradle.api.Project;
import org.gradle.api.tasks.testing.TestFilter;
import org.gradle.testfixtures.ProjectBuilder;

import com.sap.oss.smarttestpicker.execution.ExecutionIdentityMetadata;
import com.sap.oss.smarttestpicker.execution.ExecutionShape;
import com.sap.oss.smarttestpicker.selector.SelectionOutput;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SmartTestPickerExecutionFilterTest
{
	@Test
	void structuredIdentityOverridesLegacyHashedSessionKey()
	{
		String key = "ExampleTests#selectedMethod_1a2b3c4";
		SelectionOutput output = new SelectionOutput("SELECTED", "test", List.of(key), Map.of());
		output.setExecutionIdentities(Map.of(key, new ExecutionIdentityMetadata(null,
				"com.example.ExampleTests", "selectedMethod", key, "junit-jupiter", ExecutionShape.ORDINARY)));

		assertEquals(java.util.Set.of("com.example.ExampleTests.selectedMethod"),
				SmartTestPickerPlugin.buildIncludePatterns(output, false));
	}

	@Test
	void classModeUsesStructuredFullyQualifiedClass()
	{
		String key = "ExampleTests#selectedMethod_1a2b3c4";
		SelectionOutput output = new SelectionOutput("SELECTED", "test", List.of(key), Map.of());
		output.setExecutionIdentities(Map.of(key, new ExecutionIdentityMetadata(null,
				"com.example.ExampleTests", "selectedMethod", key, "junit-jupiter", ExecutionShape.ORDINARY)));

		assertEquals(java.util.Set.of("com.example.ExampleTests.*"),
				SmartTestPickerPlugin.buildIncludePatterns(output, true));
	}

	@Test
	void onlySelectionOutputUnmappedClassesBecomeConservativePatterns()
	{
		Map<String, String> unmapped = new LinkedHashMap<>();
		unmapped.put("com.example.NewRunnableTests", "New test");
		SelectionOutput output = new SelectionOutput("NONE", "test", List.of(), unmapped);

		assertEquals(java.util.Set.of("com.example.NewRunnableTests.*"),
				SmartTestPickerPlugin.buildIncludePatterns(output, false));
	}

	@Test
	void emptySelectionIsAnExplicitSuccessfulZeroTestPlan()
	{
		Project project = ProjectBuilder.builder().build();
		org.gradle.api.tasks.testing.Test test = project.getTasks().create("smartTestFixture",
				org.gradle.api.tasks.testing.Test.class);

		SmartTestPickerPlugin.configureNoTests(test);

		TestFilter filter = test.getFilter();
		assertEquals(java.util.Set.of("__no_tests_to_run__"), filter.getIncludePatterns());
		assertFalse(filter.isFailOnNoMatchingTests());
	}
}
