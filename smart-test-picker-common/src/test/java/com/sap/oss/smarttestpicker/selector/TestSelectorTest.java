// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.selector;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;


/**
 * Tests for {@link TestSelector} - verifies test selection logic against a fixture
 * coverage map with three test entries (OwnerController, PetController, VetController).
 *
 * <p>Covers class-level matching, method-level matching, combined matching,
 * the method-level-prevents-class-fallback optimization, and edge cases
 * (empty changes, missing map, no metadata).</p>
 */
class TestSelectorTest
{

	@Test
	void selectsTestsThatCoverChangedClass()
	{
		File mapFile = getFixture("coverage-map-with-metadata.json");
		Set<String> changed = Set.of("org.example.controller.OwnerController");

		TestSelector selector = new TestSelector();
		SelectionResult result = selector.selectTests(mapFile, changed);

		assertFalse(result.isFullSuiteRequired());
		assertTrue(result.getSelectedTests().contains("OwnerControllerTests#testShowOwner"));
		assertFalse(result.getSelectedTests().contains("PetControllerTests#testCreatePet"));
		assertFalse(result.getSelectedTests().contains("VetControllerTests#testShowVets"));
	}

	@Test
	void selectsMultipleTestsForSharedClass()
	{
		File mapFile = getFixture("coverage-map-with-metadata.json");
		// Owner is covered by OwnerControllerTests, Pet by PetControllerTests
		Set<String> changed = Set.of("org.example.model.Owner", "org.example.model.Pet");

		TestSelector selector = new TestSelector();
		SelectionResult result = selector.selectTests(mapFile, changed);

		assertFalse(result.isFullSuiteRequired());
		assertTrue(result.getSelectedTests().contains("OwnerControllerTests#testShowOwner"));
		assertTrue(result.getSelectedTests().contains("PetControllerTests#testCreatePet"));
		assertFalse(result.getSelectedTests().contains("VetControllerTests#testShowVets"));
	}

	@Test
	void returnsEmptySetWhenNoClassesChanged()
	{
		File mapFile = getFixture("coverage-map-with-metadata.json");
		Set<String> changed = Set.of();

		TestSelector selector = new TestSelector();
		SelectionResult result = selector.selectTests(mapFile, changed);

		assertFalse(result.isFullSuiteRequired());
		assertTrue(result.getSelectedTests().isEmpty());
	}

	@Test
	void returnsEmptySetWhenChangedClassNotInMapping()
	{
		File mapFile = getFixture("coverage-map-with-metadata.json");
		Set<String> changed = Set.of("org.example.util.SomeUtility");

		TestSelector selector = new TestSelector();
		SelectionResult result = selector.selectTests(mapFile, changed);

		assertFalse(result.isFullSuiteRequired());
		assertTrue(result.getSelectedTests().isEmpty());
	}

	@Test
	void returnsFullSuiteWhenMapFileMissing()
	{
		File nonexistent = new File("/nonexistent/path/map.json");

		TestSelector selector = new TestSelector();
		SelectionResult result = selector.selectTests(nonexistent, Set.of("org.example.Foo"));

		assertTrue(result.isFullSuiteRequired());
		assertNotNull(result.getReason());
	}

	@Test
	void returnsFullSuiteWhenMapHasNoMetadata(@TempDir Path tempDir) throws IOException
	{
		// Map without metadata
		File mapFile = tempDir.resolve("map.json").toFile();
		try (FileWriter w = new FileWriter(mapFile))
		{
			w.write("{\"testMappings\": {}}");
		}

		TestSelector selector = new TestSelector();
		SelectionResult result = selector.selectTests(mapFile, Set.of("org.example.Foo"));

		assertTrue(result.isFullSuiteRequired());
	}

	@Test
	void selectsTestByMethodMatch()
	{
		File mapFile = getFixture("coverage-map-with-metadata.json");
		// No class match, but method match
		Set<String> changedClasses = Set.of();
		Set<String> changedMethods = Set.of("org.example.controller.OwnerController#showOwner");

		TestSelector selector = new TestSelector();
		SelectionResult result = selector.selectTests(mapFile, changedClasses, changedMethods);

		assertFalse(result.isFullSuiteRequired());
		assertTrue(result.getSelectedTests().contains("OwnerControllerTests#testShowOwner"));
		assertFalse(result.getSelectedTests().contains("PetControllerTests#testCreatePet"));
		assertFalse(result.getSelectedTests().contains("VetControllerTests#testShowVets"));
	}

	@Test
	void methodMatchDoesNotSelectUnrelatedTests()
	{
		File mapFile = getFixture("coverage-map-with-metadata.json");
		Set<String> changedClasses = Set.of();
		Set<String> changedMethods = Set.of("org.example.controller.PetController#createPet");

		TestSelector selector = new TestSelector();
		SelectionResult result = selector.selectTests(mapFile, changedClasses, changedMethods);

		assertFalse(result.isFullSuiteRequired());
		assertTrue(result.getSelectedTests().contains("PetControllerTests#testCreatePet"));
		assertEquals(1, result.getSelectedTests().size());
	}

	@Test
	void combinesClassAndMethodMatches()
	{
		File mapFile = getFixture("coverage-map-with-metadata.json");
		// Class match hits OwnerControllerTests, method match hits PetControllerTests
		Set<String> changedClasses = Set.of("org.example.model.Owner");
		Set<String> changedMethods = Set.of("org.example.controller.PetController#createPet");

		TestSelector selector = new TestSelector();
		SelectionResult result = selector.selectTests(mapFile, changedClasses, changedMethods);

		assertFalse(result.isFullSuiteRequired());
		assertTrue(result.getSelectedTests().contains("OwnerControllerTests#testShowOwner"));
		assertTrue(result.getSelectedTests().contains("PetControllerTests#testCreatePet"));
		assertFalse(result.getSelectedTests().contains("VetControllerTests#testShowVets"));
	}

	@Test
	void methodLevelPreventsClassLevelFallbackForSameClass()
	{
		File mapFile = getFixture("coverage-map-with-metadata.json");
		// OwnerController is changed at class level, but we also have method-level info for it.
		// Only the test covering the specific method (showOwner) should be selected,
		// NOT all tests covering the class.
		Set<String> changedClasses = Set.of("org.example.controller.OwnerController");
		Set<String> changedMethods = Set.of("org.example.controller.OwnerController#showOwner");

		TestSelector selector = new TestSelector();
		SelectionResult result = selector.selectTests(mapFile, changedClasses, changedMethods);

		assertFalse(result.isFullSuiteRequired());
		// Only the test that covers showOwner method should be selected
		assertTrue(result.getSelectedTests().contains("OwnerControllerTests#testShowOwner"));
		// PetControllerTests does NOT cover showOwner, so it should NOT be selected
		assertFalse(result.getSelectedTests().contains("PetControllerTests#testCreatePet"));
		assertFalse(result.getSelectedTests().contains("VetControllerTests#testShowVets"));
		assertEquals(1, result.getSelectedTests().size());
	}

	@Test
	void methodLevelZeroHitsEscalatesToClassLevel()
	{
		File mapFile = getFixture("coverage-map-with-metadata.json");
		// OwnerController#toString is NOT in any test's method coverage.
		// Method-level matching will produce 0 hits for OwnerController.
		// Fallback should escalate to class-level and select all tests covering OwnerController.
		Set<String> changedClasses = Set.of("org.example.controller.OwnerController");
		Set<String> changedMethods = Set.of("org.example.controller.OwnerController#toString");

		TestSelector selector = new TestSelector();
		SelectionResult result = selector.selectTests(mapFile, changedClasses, changedMethods);

		assertFalse(result.isFullSuiteRequired());
		// OwnerControllerTests covers OwnerController at class level - should be selected via escalation
		assertTrue(result.getSelectedTests().contains("OwnerControllerTests#testShowOwner"),
				"Expected class-level fallback to select OwnerControllerTests");
		// Other tests don't cover OwnerController
		assertFalse(result.getSelectedTests().contains("PetControllerTests#testCreatePet"));
		assertFalse(result.getSelectedTests().contains("VetControllerTests#testShowVets"));
	}

	@Test
	void methodLevelPartialHitsDoesNotEscalate()
	{
		File mapFile = getFixture("coverage-map-with-metadata.json");
		// showOwner IS in coverage map - method-level match will find it.
		// No escalation needed because we got a hit.
		Set<String> changedClasses = Set.of("org.example.controller.OwnerController");
		Set<String> changedMethods = Set.of("org.example.controller.OwnerController#showOwner");

		TestSelector selector = new TestSelector();
		SelectionResult result = selector.selectTests(mapFile, changedClasses, changedMethods);

		assertFalse(result.isFullSuiteRequired());
		assertTrue(result.getSelectedTests().contains("OwnerControllerTests#testShowOwner"));
		assertEquals(1, result.getSelectedTests().size());
	}

	@Test
	void methodLevelZeroHitsForUnknownClassDoesNotEscalate()
	{
		File mapFile = getFixture("coverage-map-with-metadata.json");
		// SomeUtility is NOT in the coverage map at all (neither class nor method level).
		// Even after escalation to class-level, no test covers it - result is 0 tests.
		Set<String> changedClasses = Set.of("org.example.util.SomeUtility");
		Set<String> changedMethods = Set.of("org.example.util.SomeUtility#doSomething");

		TestSelector selector = new TestSelector();
		SelectionResult result = selector.selectTests(mapFile, changedClasses, changedMethods);

		assertFalse(result.isFullSuiteRequired());
		assertTrue(result.getSelectedTests().isEmpty());
	}

	private File getFixture(String name)
	{
		return new File(getClass().getClassLoader().getResource(name).getFile());
	}
}
