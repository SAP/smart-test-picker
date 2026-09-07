// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.gradle.api.Project;
import org.gradle.api.logging.Logger;
import org.gradle.api.tasks.testing.Test;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import com.google.gson.Gson;
import com.sap.oss.smarttestpicker.selector.SelectionOutput;

class SmartTestExecutionFilterTest {
	@TempDir Path temporary;
	private Project project; private Test task; private Logger logger;
	@BeforeEach void setUp() { project = ProjectBuilder.builder().build(); task = project.getTasks().create("selected", Test.class); logger = project.getLogger(); }

	@org.junit.jupiter.api.Test void selectedUsesSelectedTestsOnlyWithConservativeClassWidening() throws Exception {
		SelectionOutput output = new SelectionOutput("SELECTED", "selected", List.of("com.example.FooTest#a(java.lang.String)"), Map.of("com.example.DiagnosticTest#b", "FAILED"));
		SmartTestPickerPlugin.applySmartTestFilters(task, write(output).toFile(), logger);
		assertEquals(Set.of("com.example.FooTest.*"), task.getFilter().getIncludePatterns());
	}
	@org.junit.jupiter.api.Test void noneUsesExplicitNoTestSentinelEvenWithDiagnostics() throws Exception {
		SelectionOutput output = new SelectionOutput("NONE", "none", List.of(), Map.of("com.example.DiagnosticTest#b", "FAILED"));
		SmartTestPickerPlugin.applySmartTestFilters(task, write(output).toFile(), logger);
		assertEquals(Set.of("__no_tests_to_run__"), task.getFilter().getIncludePatterns());
	}
	@org.junit.jupiter.api.Test void fullSuiteMissingCorruptAndUnknownLeaveFilterUnrestricted() throws Exception {
		for (SelectionOutput output : List.of(new SelectionOutput("FULL_SUITE", "all", List.of(), Map.of()), new SelectionOutput("FUTURE", "unknown", List.of(), Map.of()))) {
			task.getFilter().setIncludePatterns(); SmartTestPickerPlugin.applySmartTestFilters(task, write(output).toFile(), logger); assertTrue(task.getFilter().getIncludePatterns().isEmpty());
		}
		SmartTestPickerPlugin.applySmartTestFilters(task, temporary.resolve("missing.json").toFile(), logger); assertTrue(task.getFilter().getIncludePatterns().isEmpty());
		Path corrupt = temporary.resolve("corrupt.json"); Files.writeString(corrupt, "{"); SmartTestPickerPlugin.applySmartTestFilters(task, corrupt.toFile(), logger); assertTrue(task.getFilter().getIncludePatterns().isEmpty());
	}
	@org.junit.jupiter.api.Test void smartTestMirrorsStandardExecutionEnvironment() {
		Test standard = project.getTasks().create("standard", Test.class);
		standard.jvmArgs("-Dstp.fixture=true");
		standard.setMinHeapSize("128m"); standard.setMaxHeapSize("512m");
		standard.setEnableAssertions(false);
		standard.systemProperty("stp.property", "present");
		standard.include("**/*Tests.class"); standard.exclude("**/ExcludedTests.class");

		SmartTestPickerPlugin.mirrorExecutionEnvironment(standard, task);

		assertEquals(standard.getJvmArgs(), task.getJvmArgs());
		assertEquals("128m", task.getMinHeapSize()); assertEquals("512m", task.getMaxHeapSize());
		assertFalse(task.getEnableAssertions());
		assertEquals("present", task.getSystemProperties().get("stp.property"));
		assertEquals(standard.getIncludes(), task.getIncludes());
		assertEquals(standard.getExcludes(), task.getExcludes());
	}
	private Path write(SelectionOutput output) throws Exception { Path file = temporary.resolve(UUID.randomUUID() + ".json"); Files.writeString(file, new Gson().toJson(output)); return file; }
}
