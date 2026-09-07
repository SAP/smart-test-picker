// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HeadInventoryFunctionalTest {
	@TempDir Path project;
	@Test void generatesExactInventoryWithoutExecutingTests() throws Exception {
		Files.writeString(project.resolve("settings.gradle"), "rootProject.name='inventory-fixture'\n");
		Files.writeString(project.resolve("build.gradle"), """
			plugins { id 'java'; id 'com.sap.oss.smart-test-picker' }
			repositories { mavenCentral() }
			dependencies {
			  testImplementation 'org.junit.jupiter:junit-jupiter:5.9.3'
			  testImplementation 'org.junit.jupiter:junit-jupiter-params:5.9.3'
			}
			test { useJUnitPlatform() }
			""");
		Path source = Files.createDirectories(project.resolve("src/test/java/example"));
		Files.writeString(source.resolve("InventoryTests.java"), """
			package example;
			import org.junit.jupiter.api.*;
			import org.junit.jupiter.params.ParameterizedTest;
			import org.junit.jupiter.params.provider.ValueSource;
			public class InventoryTests {
			  @Test void ordinary() { throw new AssertionError("executed"); }
			  @Disabled @Test void disabled() { throw new AssertionError("executed"); }
			  @Test void overloaded(String value) { throw new AssertionError("executed"); }
			  @Test void overloaded(int value) { throw new AssertionError("executed"); }
			  @ParameterizedTest @ValueSource(strings={"a","b"}) void parameterized(String value) { throw new AssertionError("executed"); }
			  @Nested class NestedGroup { @Test void nested() { throw new AssertionError("executed"); } }
			}
			""");
		var result = GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
				.withArguments("generateHeadTestInventory", "--info", "--stacktrace").forwardOutput().build();
		assertEquals(SUCCESS, result.task(":generateHeadTestInventory").getOutcome());
		assertFalse(Files.exists(project.resolve("executed")));
		assertEquals("[\"example.InventoryTests#disabled\",\"example.InventoryTests#ordinary\","
				+ "\"example.InventoryTests#overloaded(int)\",\"example.InventoryTests#overloaded(java.lang.String)\","
				+ "\"example.InventoryTests#parameterized(java.lang.String)\",\"example.InventoryTests$NestedGroup#nested\"]\n",
				Files.readString(project.resolve("build/head-test-inventory.json")));
	}
}
