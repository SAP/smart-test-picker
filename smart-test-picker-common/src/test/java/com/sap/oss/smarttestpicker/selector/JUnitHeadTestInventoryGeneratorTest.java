// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.selector;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.io.TempDir;

class JUnitHeadTestInventoryGeneratorTest {
	@TempDir Path temporary;

	@Test void discoversExactLogicalDeclaredIdentitiesWithoutExecutingBodies() throws Exception {
		Path root = Path.of(InventoryFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		List<Path> classpath = Arrays.stream(System.getProperty("java.class.path").split(
				System.getProperty("path.separator"))).map(Path::of)
				.filter(path -> !path.getFileName().toString().startsWith("junit-platform-launcher-")).toList();
		String revision = "0123456789abcdef0123456789abcdef01234567";
		HeadTestInventory result = new JUnitHeadTestInventoryGenerator().generate(revision, classpath, List.of(root));
		assertEquals(revision, result.revision());
		Set<TestIdentity> fixture = result.runnableTests().stream()
				.filter(id -> id.className().startsWith(InventoryFixture.class.getName())).collect(java.util.stream.Collectors.toSet());
		assertEquals(Set.of(
				new TestIdentity(InventoryFixture.class.getName(), "ordinary"),
				new TestIdentity(InventoryFixture.class.getName(), "disabled"),
				new TestIdentity(InventoryFixture.class.getName(), "overloaded", "java.lang.String"),
				new TestIdentity(InventoryFixture.class.getName(), "overloaded", "int"),
				new TestIdentity(InventoryFixture.class.getName(), "parameterized", "java.lang.String"),
				new TestIdentity(InventoryFixture.NestedFixture.class.getName(), "nested")), fixture);
		assertFalse(java.nio.file.Files.exists(temporary.resolve("executed")));
	}

	@Disabled("fixture is discovered programmatically; Gradle must never execute it")
	static class InventoryFixture {
		@Test void ordinary() { fail("discovery executed a body"); }
		@Disabled @Test void disabled() { fail("discovery executed a disabled body"); }
		@Test void overloaded(String value) { fail("discovery executed a body"); }
		@Test void overloaded(int value) { fail("discovery executed a body"); }
		@ParameterizedTest @MethodSource("values") void parameterized(String value) { fail("discovery executed a body"); }
		static Stream<String> values() { throw new AssertionError("discovery invoked arguments"); }
		@Nested class NestedFixture { @Test void nested() { fail("discovery executed a body"); } }
	}
}
