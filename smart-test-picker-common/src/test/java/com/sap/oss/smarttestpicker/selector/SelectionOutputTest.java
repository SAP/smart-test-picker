// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker.selector;

import java.util.List;
import java.util.Map;

import com.google.gson.Gson;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;


class SelectionOutputTest
{

	@Test
	void constructorAndGetters()
	{
		SelectionOutput output = new SelectionOutput("SELECTED", "3 tests selected",
				List.of("FooTest#test1", "BarTest#test2"),
				Map.of("NewTest", "Not in coverage map"));

		assertEquals("SELECTED", output.getStatus());
		assertEquals("3 tests selected", output.getReason());
		assertEquals(2, output.getSelectedTests().size());
		assertEquals("FooTest#test1", output.getSelectedTests().get(0));
		assertEquals(1, output.getUnmappedTests().size());
		assertEquals("Not in coverage map", output.getUnmappedTests().get("NewTest"));
	}

	@Test
	void setChangedClassesPersists()
	{
		SelectionOutput output = new SelectionOutput("NONE", "No changes", List.of(), Map.of());
		assertNull(output.getChangedClasses());

		output.setChangedClasses(List.of("com.example.Foo", "com.example.Bar"));
		assertEquals(2, output.getChangedClasses().size());
		assertEquals("com.example.Foo", output.getChangedClasses().get(0));
	}

	@Test
	void gsonRoundTrip()
	{
		SelectionOutput original = new SelectionOutput("FULL_SUITE", "Map missing",
				List.of("TestA#m1"), Map.of("TestB", "New test"));
		original.setChangedClasses(List.of("com.example.Changed"));

		Gson gson = new Gson();
		String json = gson.toJson(original);
		SelectionOutput deserialized = gson.fromJson(json, SelectionOutput.class);

		assertEquals("FULL_SUITE", deserialized.getStatus());
		assertEquals("Map missing", deserialized.getReason());
		assertEquals(List.of("TestA#m1"), deserialized.getSelectedTests());
		assertEquals("New test", deserialized.getUnmappedTests().get("TestB"));
		assertEquals(List.of("com.example.Changed"), deserialized.getChangedClasses());
	}

	@Test
	void nullSelectedTestsFromConstructor()
	{
		SelectionOutput output = new SelectionOutput("NONE", "reason", null, null);

		assertNull(output.getSelectedTests());
		assertNull(output.getUnmappedTests());
	}
}
