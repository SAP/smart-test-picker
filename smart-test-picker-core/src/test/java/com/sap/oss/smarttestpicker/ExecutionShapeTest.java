// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import java.util.List;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ExecutionShapeTest
{
	@RunWith(Parameterized.class)
	static class Indexed
	{
		@Parameterized.Parameters(name = "row-{index}") public static List<Object[]> data() { return java.util.Collections.singletonList(new Object[] { 1 }); }
		public Indexed(int ignored) {}
		@org.junit.Test public void value() {}
	}
	@RunWith(Parameterized.class)
	static class Named
	{
		@Parameterized.Parameters(name = "{0}") public static List<Object[]> data() { return java.util.Collections.singletonList(new Object[] { 1 }); }
		public Named(int ignored) {}
		@org.junit.Test public void value() {}
	}
	static class Jupiter
	{
		@ParameterizedTest @ValueSource(ints = 1) void value(int ignored) {}
		@Test void ordinary() {}
	}

	@Test void recognizesOnlyEmpiricallySupportedShapes()
	{
		assertEquals("JUNIT4_PARAMETERIZED_INDEXED", JacocoPerTestListener.executionShape(Indexed.class, "value", "junit-vintage"));
		assertEquals("JUNIT4_PARAMETERIZED_NAMED", JacocoPerTestListener.executionShape(Named.class, "value", "junit-vintage"));
		assertEquals("JUPITER_PARAMETERIZED", JacocoPerTestListener.executionShape(Jupiter.class, "value", "junit-jupiter"));
		assertEquals("ORDINARY", JacocoPerTestListener.executionShape(Jupiter.class, "ordinary", "junit-jupiter"));
	}
}
