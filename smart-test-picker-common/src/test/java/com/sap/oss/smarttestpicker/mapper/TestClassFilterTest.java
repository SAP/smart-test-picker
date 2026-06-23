// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker.mapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;


class TestClassFilterTest
{

	@Test
	void detectsStandardTestSuffixes()
	{
		assertTrue(TestClassFilter.isTestClass("org.example.FooTest"));
		assertTrue(TestClassFilter.isTestClass("org.example.FooTests"));
		assertTrue(TestClassFilter.isTestClass("org.example.FooIT"));
		assertTrue(TestClassFilter.isTestClass("org.example.FooTestCase"));
	}

	@Test
	void detectsTestPrefix()
	{
		assertTrue(TestClassFilter.isTestClass("com.sap.adapters.TestDefaultAdapter"));
		assertTrue(TestClassFilter.isTestClass("com.sap.adapters.TestNoSendAdapter"));
	}

	@Test
	void detectsTestPackages()
	{
		assertTrue(TestClassFilter.isTestClass("com.example.platform.testframework.jacoco.JacocoTestListener"));
		assertTrue(TestClassFilter.isTestClass("com.example.platform.testframework.performance.PerformanceListener"));
		assertTrue(TestClassFilter.isTestClass("org.example.test.Helper"));
		assertTrue(TestClassFilter.isTestClass("org.example.tests.Utilities"));
		assertTrue(TestClassFilter.isTestClass("com.sap.testsrc.Helper"));
	}

	@Test
	void detectsNestedTestClasses()
	{
		assertTrue(TestClassFilter.isTestClass("org.example.FooTest$InnerHelper"));
		assertTrue(TestClassFilter.isTestClass("org.example.FooTest$1"));
	}

	@Test
	void doesNotMatchProductionClasses()
	{
		assertFalse(TestClassFilter.isTestClass("org.example.FooService"));
		assertFalse(TestClassFilter.isTestClass("org.example.UserController"));
		assertFalse(TestClassFilter.isTestClass("org.example.Contest"));
		assertFalse(TestClassFilter.isTestClass("org.example.Latest"));
		assertFalse(TestClassFilter.isTestClass("org.example.Attestation"));
		assertFalse(TestClassFilter.isTestClass("com.example.platform.core.Registry"));
	}

	@Test
	void handlesNullAndEmpty()
	{
		assertFalse(TestClassFilter.isTestClass(null));
		assertFalse(TestClassFilter.isTestClass(""));
	}

	@Test
	void handlesSimpleNameWithoutPackage()
	{
		assertTrue(TestClassFilter.isTestClass("FooTest"));
		assertFalse(TestClassFilter.isTestClass("FooService"));
	}

	@Test
	void filterTestClassesRemovesTestClassesAndMethods()
	{
		Map<String, List<String>> coverage = new HashMap<>();
		coverage.put("classes", new ArrayList<>(List.of(
				"org.example.FooService",
				"org.example.FooTest",
				"org.example.BarController")));
		coverage.put("methods", new ArrayList<>(List.of(
				"org.example.FooService#doStuff",
				"org.example.FooTest#setUp",
				"org.example.FooTest#testDoStuff",
				"org.example.BarController#handleRequest")));

		TestClassFilter.filterTestClasses(coverage);

		assertEquals(List.of("org.example.FooService", "org.example.BarController"),
				coverage.get("classes"));
		assertEquals(List.of("org.example.FooService#doStuff", "org.example.BarController#handleRequest"),
				coverage.get("methods"));
	}

	@Test
	void filterTestClassesLeavesProductionOnlyEntryUntouched()
	{
		Map<String, List<String>> coverage = new HashMap<>();
		coverage.put("classes", new ArrayList<>(List.of("org.example.FooService")));
		coverage.put("methods", new ArrayList<>(List.of("org.example.FooService#doStuff")));

		TestClassFilter.filterTestClasses(coverage);

		assertEquals(List.of("org.example.FooService"), coverage.get("classes"));
		assertEquals(List.of("org.example.FooService#doStuff"), coverage.get("methods"));
	}

	@Test
	void filterTestClassesHandlesNullCoverage()
	{
		assertDoesNotThrow(() -> TestClassFilter.filterTestClasses(null));
	}

	@Test
	void filterAllAppliesToAllEntries()
	{
		Map<String, Map<String, List<String>>> testMappings = new HashMap<>();

		Map<String, List<String>> cov1 = new HashMap<>();
		cov1.put("classes", new ArrayList<>(List.of("org.example.Foo", "org.example.FooTest")));
		cov1.put("methods", new ArrayList<>(List.of("org.example.Foo#run", "org.example.FooTest#testRun")));
		testMappings.put("FooTest#testRun", cov1);

		Map<String, List<String>> cov2 = new HashMap<>();
		cov2.put("classes", new ArrayList<>(List.of("org.example.Bar", "org.example.BarIT")));
		cov2.put("methods", new ArrayList<>(List.of("org.example.Bar#exec", "org.example.BarIT#testExec")));
		testMappings.put("BarIT#testExec", cov2);

		TestClassFilter.filterAll(testMappings);

		assertEquals(List.of("org.example.Foo"), cov1.get("classes"));
		assertEquals(List.of("org.example.Foo#run"), cov1.get("methods"));
		assertEquals(List.of("org.example.Bar"), cov2.get("classes"));
		assertEquals(List.of("org.example.Bar#exec"), cov2.get("methods"));
	}
}
