// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.mapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;


class TestClassFilterTest
{

	@Test
	void packageNamesDoNotClassifyProductionCodeAsTests()
	{
		Set<String> testOutput = Set.of("org.example.RealTest");
		assertFalse(TestClassFilter.isTestClass("com.example.test.Foo", testOutput));
		assertFalse(TestClassFilter.isTestClass("com.example.tests.Bar", testOutput));
		assertFalse(TestClassFilter.isTestClass("org.acme.testsupport.Baz", testOutput));
		assertFalse(TestClassFilter.isTestClass("com.annimon.stream.test.SomeProductionClass", testOutput));
	}

	@Test
	void exactTestOutputOwnershipClassifiesTestsAndHelpers()
	{
		Set<String> testOutput = Set.of("org.example.RealTest", "org.example.Helper");
		assertTrue(TestClassFilter.isTestClass("org.example.RealTest", testOutput));
		assertTrue(TestClassFilter.isTestClass("org.example.Helper", testOutput));
		assertFalse(TestClassFilter.isTestClass("org.example.RealTest", Set.of()));
	}

	@Test
	void testOutputFilteringRemovesOnlyStructurallyOwnedClasses()
	{
		Map<String, List<String>> coverage = coverage(
				List.of("com.example.test.ProductionTest", "org.example.RealTest", "org.example.Helper"),
				List.of("com.example.test.ProductionTest#run", "org.example.RealTest#testRun",
						"org.example.Helper#setUp"));

		TestClassFilter.filterTestClasses(coverage, Set.of("org.example.RealTest", "org.example.Helper"));

		assertEquals(List.of("com.example.test.ProductionTest"), coverage.get("classes"));
		assertEquals(List.of("com.example.test.ProductionTest#run"), coverage.get("methods"));
	}

	@Test
	void productionOutputRetainsGeneratedAndTestNamedProductionClasses()
	{
		Map<String, List<String>> coverage = coverage(
				List.of("com.example.test.Foo", "com.example.generated.tests.BeanDefinition", "org.example.Helper"),
				List.of("com.example.test.Foo#run", "com.example.generated.tests.BeanDefinition#build",
						"org.example.Helper#setUp"));

		TestClassFilter.retainProductionClasses(coverage,
				Set.of("com.example.test.Foo", "com.example.generated.tests.BeanDefinition"));

		assertEquals(List.of("com.example.test.Foo", "com.example.generated.tests.BeanDefinition"),
				coverage.get("classes"));
		assertEquals(List.of("com.example.test.Foo#run", "com.example.generated.tests.BeanDefinition#build"),
				coverage.get("methods"));
	}

	@Test
	void absentLegacyManifestLeavesAlreadyScopedCoverageUnchanged()
	{
		Map<String, List<String>> coverage = coverage(
				List.of("org.example.FooService", "org.example.FooTest"),
				List.of("org.example.FooService#run", "org.example.FooTest#utility"));

		TestClassFilter.retainProductionClasses(coverage, Set.of());

		assertEquals(List.of("org.example.FooService", "org.example.FooTest"), coverage.get("classes"));
		assertEquals(List.of("org.example.FooService#run", "org.example.FooTest#utility"),
				coverage.get("methods"));
	}

	@Test
	void handlesNullCoverage()
	{
		assertDoesNotThrow(() -> TestClassFilter.filterTestClasses(null, Set.of("org.example.RealTest")));
		assertDoesNotThrow(() -> TestClassFilter.retainProductionClasses(null, Set.of("org.example.Main")));
	}

	private static Map<String, List<String>> coverage(List<String> classes, List<String> methods)
	{
		Map<String, List<String>> coverage = new HashMap<>();
		coverage.put("classes", new ArrayList<>(classes));
		coverage.put("methods", new ArrayList<>(methods));
		return coverage;
	}
}
