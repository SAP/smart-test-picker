// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker.report;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.ljubisap.smarttestpicker.mapper.ClassCoverageMetrics;

import static org.junit.jupiter.api.Assertions.*;


/**
 * Tests for {@link HtmlReportGenerator} — verifies HTML output structure and content
 * for various selection scenarios (selected tests, full suite, no changes).
 */
class HtmlReportGeneratorTest
{

	private final HtmlReportGenerator generator = new HtmlReportGenerator();

	@Test
	void generatesValidHtmlDocument()
	{
		ReportData data = buildBasicData(Set.of("OwnerControllerTests#testShowOwner"));

		String html = generator.generate(data);

		assertTrue(html.startsWith("<!DOCTYPE html>"));
		assertTrue(html.contains("<html"));
		assertTrue(html.contains("</html>"));
		assertTrue(html.contains("<head>"));
		assertTrue(html.contains("</head>"));
		assertTrue(html.contains("<body>"));
		assertTrue(html.contains("</body>"));
	}

	@Test
	void containsStatCards()
	{
		ReportData data = buildBasicData(Set.of("OwnerControllerTests#testShowOwner"));

		String html = generator.generate(data);

		assertTrue(html.contains("1 / 3"));       // 1 selected of 3 total
		assertTrue(html.contains("Changed Classes"));
		assertTrue(html.contains("Changed Methods"));
		assertTrue(html.contains("Commit Distance"));
		assertTrue(html.contains("Reduction"));
	}

	@Test
	void containsSelectedTestBadge()
	{
		ReportData data = buildBasicData(Set.of("OwnerControllerTests#testShowOwner"));

		String html = generator.generate(data);

		assertTrue(html.contains("SELECTED"));
		assertTrue(html.contains("SKIPPED"));
		assertTrue(html.contains("testShowOwner"));
	}

	@Test
	void containsDonutChart()
	{
		ReportData data = buildBasicData(Set.of("OwnerControllerTests#testShowOwner"));

		String html = generator.generate(data);

		assertTrue(html.contains("<svg"));
		assertTrue(html.contains("</svg>"));
		assertTrue(html.contains("donut-chart"));
	}

	@Test
	void fullSuiteShowsWarningBanner()
	{
		ReportData data = buildBasicData(Set.of());
		data.setFullSuite(true);
		data.setFullSuiteReason("Coverage map has no metadata");

		String html = generator.generate(data);

		assertTrue(html.contains("Full Suite Required"));
		assertTrue(html.contains("Coverage map has no metadata"));
		assertTrue(html.contains("banner-warning"));
		// Should NOT show coverage matrix for full suite
		assertFalse(html.contains("Coverage Matrix"));
	}

	@Test
	void noneShowsSuccessBanner()
	{
		ReportData data = buildBasicData(Set.of());
		data.setNone(true);
		data.setChangedClasses(Set.of());
		data.setChangedMethods(Set.of());

		String html = generator.generate(data);

		assertTrue(html.contains("No Changes Detected"));
		assertTrue(html.contains("banner-success"));
		assertFalse(html.contains("Coverage Matrix"));
	}

	@Test
	void containsCoverageMatrix()
	{
		ReportData data = buildBasicData(Set.of("OwnerControllerTests#testShowOwner"));

		String html = generator.generate(data);

		assertTrue(html.contains("Coverage Matrix"));
		assertTrue(html.contains("matrix-table"));
		// Should show only the selected test, with Covered Classes and Covered Methods columns
		assertTrue(html.contains("Covered Classes"));
		assertTrue(html.contains("Covered Methods"));
		assertTrue(html.contains("testShowOwner"));
		// Skipped tests should NOT appear in the matrix (check matrix rows specifically)
		String matrixSection = html.substring(html.indexOf("Coverage Matrix"), html.indexOf("</section>", html.indexOf("Coverage Matrix")));
		assertFalse(matrixSection.contains("testCreatePet"));
		assertFalse(matrixSection.contains("testShowVets"));
	}

	@Test
	void containsSelectionReasons()
	{
		ReportData data = buildBasicData(Set.of("OwnerControllerTests#testShowOwner"));

		String html = generator.generate(data);

		assertTrue(html.contains("Per-Test Selection Details"));
		assertTrue(html.contains("covers changed method"));
		assertFalse(html.contains("No overlap with changed code"),
				"Skipped tests should not appear in Per-Test Selection Details");
	}

	@Test
	void noNullLiteralsInOutput()
	{
		ReportData data = buildBasicData(Set.of("OwnerControllerTests#testShowOwner"));

		String html = generator.generate(data);

		assertFalse(html.contains(">null<"));
		assertFalse(html.contains("\"null\""));
	}

	@Test
	void handlesAllTestsSelected()
	{
		Set<String> allTests = Set.of(
				"OwnerControllerTests#testShowOwner",
				"PetControllerTests#testCreatePet",
				"VetControllerTests#testShowVets"
		);
		ReportData data = buildBasicData(allTests);

		String html = generator.generate(data);

		assertTrue(html.contains("3 / 3"));
		// All should be SELECTED
		assertFalse(html.contains("SKIPPED"));
	}

	@Test
	void containsChangedCodeSection()
	{
		ReportData data = buildBasicData(Set.of("OwnerControllerTests#testShowOwner"));

		String html = generator.generate(data);

		assertTrue(html.contains("Changed Code"));
		assertTrue(html.contains("OwnerController"));
		assertTrue(html.contains("showOwner"));
	}

	@Test
	void containsFooter()
	{
		ReportData data = buildBasicData(Set.of());

		String html = generator.generate(data);

		assertTrue(html.contains("Smart Test Picker"));
		assertTrue(html.contains("Lightweight Regression Test Selection"));
	}

	@Test
	void escapesHtmlCharacters()
	{
		ReportData data = buildBasicData(Set.of());
		data.setBaseBranch("<script>alert('xss')</script>");

		String html = generator.generate(data);

		assertFalse(html.contains("<script>alert"));
		assertTrue(html.contains("&lt;script&gt;"));
	}

	@Test
	void containsFullCoverageMap()
	{
		ReportData data = buildBasicData(Set.of("OwnerControllerTests#testShowOwner"));

		String html = generator.generate(data);

		assertTrue(html.contains("Complete Test Coverage Map"));
		assertTrue(html.contains("coverage-map-search"));
		// All 3 tests should be listed
		assertTrue(html.contains("OwnerControllerTests#testShowOwner"));
		assertTrue(html.contains("PetControllerTests#testCreatePet"));
		assertTrue(html.contains("VetControllerTests#testShowVets"));
	}

	@Test
	void coverageMapSearchInputExists()
	{
		ReportData data = buildBasicData(Set.of("OwnerControllerTests#testShowOwner"));

		String html = generator.generate(data);

		assertTrue(html.contains("id=\"coverage-map-search\""));
		assertTrue(html.contains("data-search-test=\""));
		assertTrue(html.contains("data-search-classes=\""));
		assertTrue(html.contains("data-search-methods=\""));
		assertTrue(html.contains("coverage-map-filter-tests"));
		assertTrue(html.contains("coverage-map-filter-classes"));
		assertTrue(html.contains("coverage-map-filter-methods"));
		assertTrue(html.contains("coverage-map-count"));
	}

	@Test
	void coverageMapShowsClassesAndMethods()
	{
		ReportData data = buildBasicData(Set.of("OwnerControllerTests#testShowOwner"));

		String html = generator.generate(data);

		// Classes should be listed
		assertTrue(html.contains("OwnerController"));
		assertTrue(html.contains("PetController"));
		assertTrue(html.contains("VetController"));
		// Methods should be listed
		assertTrue(html.contains("showOwner"));
		assertTrue(html.contains("createPet"));
		assertTrue(html.contains("showVetList"));
	}

	@Test
	void containsMetadataSection()
	{
		ReportData data = buildBasicData(Set.of("OwnerControllerTests#testShowOwner"));

		String html = generator.generate(data);

		assertTrue(html.contains("Coverage Map Info"));
		assertTrue(html.contains("Base Branch"));
		assertTrue(html.contains("main"));
		assertTrue(html.contains("Coverage Map Commit"));
		assertTrue(html.contains("abc1234"));  // short commit
		assertTrue(html.contains("Map Generated"));
		assertTrue(html.contains("2026-04-11T10:00:00Z"));
		assertTrue(html.contains("Current Branch"));
		assertTrue(html.contains("feature/test"));
		assertTrue(html.contains("Commit Distance"));
	}

	@Test
	void handlesUnmappedTests()
	{
		ReportData data = buildBasicData(Set.of("OwnerControllerTests#testShowOwner"));
		// Set unmapped tests with FQN and reason (as the JSON pipeline would)
		data.setUnmappedTests(Map.of(
				"org.example.DummyTest", "Not in coverage map",
				"org.example.NewFeatureTest", "New test \u2014 added after coverage map"
		));

		String html = generator.generate(data);

		assertTrue(html.contains("Tests Without Coverage Data"));
		assertTrue(html.contains("Package"));
		assertTrue(html.contains("Reason"));
		assertTrue(html.contains("DummyTest"));
		assertTrue(html.contains("NewFeatureTest"));
		assertTrue(html.contains("org.example."));
		assertTrue(html.contains("should be reviewed"));
		assertTrue(html.contains("Not in coverage map"));
		assertTrue(html.contains("added after coverage map"));
	}

	@Test
	void noUnmappedSectionWhenAllTestsInMap()
	{
		ReportData data = buildBasicData(Set.of("OwnerControllerTests#testShowOwner"));

		String html = generator.generate(data);

		assertFalse(html.contains("Tests Without Coverage Data"));
	}

	@Test
	void changedClassesShowCoverageColumn()
	{
		ReportData data = buildBasicData(Set.of("OwnerControllerTests#testShowOwner"));
		data.setClassCoveragePercentages(Map.of(
				"org.example.controller.OwnerController", 82.5
		));

		String html = generator.generate(data);

		assertTrue(html.contains("Line Coverage"));
		assertTrue(html.contains("cov-bar"));
		assertTrue(html.contains("82.5%"));
		assertTrue(html.contains("cov-green"));
	}

	@Test
	void coverageBarColorZones()
	{
		ReportData data = buildBasicData(Set.of("OwnerControllerTests#testShowOwner"));
		data.setChangedClasses(Set.of(
				"org.example.controller.OwnerController",
				"org.example.model.Owner",
				"org.example.model.Pet"
		));
		data.setClassCoveragePercentages(Map.of(
				"org.example.controller.OwnerController", 80.0,
				"org.example.model.Owner", 60.0,
				"org.example.model.Pet", 30.0
		));

		String html = generator.generate(data);

		// Green for 80%
		assertTrue(html.contains("cov-green"));
		// Yellow for 60%
		assertTrue(html.contains("cov-yellow"));
		// Red for 30%
		assertTrue(html.contains("cov-red"));
	}

	@Test
	void coverageBarShowsNaWhenNoCoverageData()
	{
		ReportData data = buildBasicData(Set.of("OwnerControllerTests#testShowOwner"));
		data.setClassCoveragePercentages(Map.of());

		String html = generator.generate(data);

		assertTrue(html.contains("N/A"));
		assertTrue(html.contains("cov-na"));
	}

	@Test
	void coverageMatrixShowsBadges()
	{
		ReportData data = buildBasicData(Set.of("OwnerControllerTests#testShowOwner"));
		data.setClassCoveragePercentages(Map.of(
				"org.example.controller.OwnerController", 85.0,
				"org.example.model.Owner", 45.0
		));

		String html = generator.generate(data);

		assertTrue(html.contains("cov-badge"));
		assertTrue(html.contains("85%"));
		assertTrue(html.contains("45%"));
	}

	@Test
	void showsClassLevelSelectionIndicator()
	{
		ReportData data = buildBasicData(Set.of("OwnerControllerTests#testShowOwner"));
		data.setClassLevelSelection(true);

		String html = generator.generate(data);

		assertTrue(html.contains("Selection Mode"));
		assertTrue(html.contains("Class-level"));
	}

	@Test
	void hidesClassLevelIndicatorWhenDisabled()
	{
		ReportData data = buildBasicData(Set.of("OwnerControllerTests#testShowOwner"));
		data.setClassLevelSelection(false);

		String html = generator.generate(data);

		assertFalse(html.contains("Selection Mode"));
	}

	@Test
	void classLevelExpansionShowsSummary()
	{
		ReportData data = buildExpandedData();
		data.setClassLevelSelection(true);

		String html = generator.generate(data);

		assertTrue(html.contains("Class-Level Expansion"));
		// 1 selected → 3 will execute (3 methods in OwnerControllerTests)
		assertTrue(html.contains("1 methods selected"));
		assertTrue(html.contains("3 methods will execute"));
		assertTrue(html.contains("1 test class"));
	}

	@Test
	void classLevelExpansionShowsPerClassBreakdown()
	{
		ReportData data = buildExpandedData();
		data.setClassLevelSelection(true);

		String html = generator.generate(data);

		assertTrue(html.contains("OwnerControllerTests"));
		assertTrue(html.contains("3 methods"));
		// The directly selected method
		assertTrue(html.contains("testShowOwner"));
		// The added methods from the same class
		assertTrue(html.contains("testUpdateOwner"));
		assertTrue(html.contains("testDeleteOwner"));
	}

	@Test
	void classLevelExpansionMarksTags()
	{
		ReportData data = buildExpandedData();
		data.setClassLevelSelection(true);

		String html = generator.generate(data);

		assertTrue(html.contains("expansion-tag-selected"));
		assertTrue(html.contains("expansion-tag-added"));
		assertTrue(html.contains(">selected<"));
		assertTrue(html.contains(">added<"));
	}

	@Test
	void classLevelExpansionHiddenWhenDisabled()
	{
		ReportData data = buildExpandedData();
		data.setClassLevelSelection(false);

		String html = generator.generate(data);

		assertFalse(html.contains("Class-Level Expansion"));
	}

	@Test
	void classLevelExpansionMultipleClasses()
	{
		ReportData data = buildExpandedData();
		data.setClassLevelSelection(true);
		// Select from two different classes
		data.setSelectedTests(Set.of(
				"OwnerControllerTests#testShowOwner",
				"PetControllerTests#testCreatePet"
		));

		String html = generator.generate(data);

		// Should show both classes
		assertTrue(html.contains("OwnerControllerTests"));
		assertTrue(html.contains("PetControllerTests"));
		assertTrue(html.contains("2 test classes"));
		// 2 selected → 3 + 2 = 5 will execute
		assertTrue(html.contains("2 methods selected"));
		assertTrue(html.contains("5 methods will execute"));
	}

	@Test
	void classLevelDonutShowsThreeSegments()
	{
		ReportData data = buildExpandedData();
		data.setClassLevelSelection(true);
		// 1 selected from OwnerControllerTests (3 total in class) → 2 added
		// total in map: 5 tests

		String html = generator.generate(data);

		assertTrue(html.contains("Selected (1)"));
		assertTrue(html.contains("Added by class (2)"));
		assertTrue(html.contains("Skipped (2)"));
	}

	@Test
	void classLevelStatCardShowsAddedTests()
	{
		ReportData data = buildExpandedData();
		data.setClassLevelSelection(true);
		// 1 selected + 2 added from same class = "1 + 2 / 5"

		String html = generator.generate(data);

		assertTrue(html.contains("1 + 2 / 5"));
		assertTrue(html.contains("Tests Will Execute"));
	}

	@Test
	void nonClassLevelDonutHasNoAddedSegment()
	{
		ReportData data = buildExpandedData();
		data.setClassLevelSelection(false);

		String html = generator.generate(data);

		assertFalse(html.contains("Added by class"));
		assertTrue(html.contains("Selected (1)"));
		assertTrue(html.contains("Skipped (4)"));
	}

	// --- Chunk mode tests ---

	@Test
	void chunkModeRendersContainerWithLoadingJs()
	{
		ReportData data = buildBasicData(Set.of("OwnerControllerTests#testShowOwner"));
		data.setChunkCount(3);
		data.setTotalTestCount(150);

		String html = generator.generate(data);

		assertTrue(html.contains("coverage-map-entries"));
		assertTrue(html.contains("data-total=\"3\""));
		assertTrue(html.contains("loadChunk"));
		assertTrue(html.contains("150 tests across 3 pages"));
		// Inline mode produces static "X classes, Y methods" counts — chunk mode should not
		assertFalse(html.contains("2 classes, 2 methods"));
	}

	@Test
	void chunkModeHasExpandCollapseButtons()
	{
		ReportData data = buildBasicData(Set.of("OwnerControllerTests#testShowOwner"));
		data.setChunkCount(1);
		data.setTotalTestCount(50);

		String html = generator.generate(data);

		assertTrue(html.contains("Expand All"));
		assertTrue(html.contains("Collapse All"));
		assertTrue(html.contains("expandAllEntries()"));
		assertTrue(html.contains("collapseAllEntries()"));
	}

	@Test
	void inlineModeHasExpandCollapseButtons()
	{
		ReportData data = buildBasicData(Set.of("OwnerControllerTests#testShowOwner"));

		String html = generator.generate(data);

		assertTrue(html.contains("Expand All"));
		assertTrue(html.contains("toggle-btn"));
		assertTrue(html.contains("coverage-map-body"));
	}

	@Test
	void inlineModeShowsCollapsedByDefault()
	{
		ReportData data = buildBasicData(Set.of("OwnerControllerTests#testShowOwner"));

		String html = generator.generate(data);

		assertTrue(html.contains("style=\"display:none\""));
		assertTrue(html.contains("entry-counts"));
		assertTrue(html.contains("2 classes, 2 methods"));
	}

	// --- Helpers ---

	/**
	 * Builds a ReportData with the same 3-test structure as coverage-map-with-metadata.json.
	 */
	private ReportData buildBasicData(Set<String> selectedTests)
	{
		ReportData data = new ReportData();
		data.setBaseBranch("main");
		data.setCommitId("abc1234567890abcdef1234567890abcdef123456");
		data.setTimestamp("2026-04-11T10:00:00Z");
		data.setCurrentBranch("feature/test");
		data.setCommitDistance(3);
		data.setChangedClasses(Set.of("org.example.controller.OwnerController"));
		data.setChangedMethods(Set.of("org.example.controller.OwnerController#showOwner"));
		data.setSelectedTests(selectedTests);
		data.setFullSuite(false);
		data.setNone(false);
		data.setFullSuiteReason(null);

		// Same data as coverage-map-with-metadata.json fixture
		Map<String, Map<String, List<String>>> testMappings = new HashMap<>();

		Map<String, List<String>> ownerCoverage = new HashMap<>();
		ownerCoverage.put("classes", List.of("org.example.controller.OwnerController", "org.example.model.Owner"));
		ownerCoverage.put("methods", List.of("org.example.controller.OwnerController#showOwner", "org.example.model.Owner#getName"));
		testMappings.put("OwnerControllerTests#testShowOwner", ownerCoverage);

		Map<String, List<String>> petCoverage = new HashMap<>();
		petCoverage.put("classes", List.of("org.example.controller.PetController", "org.example.model.Pet"));
		petCoverage.put("methods", List.of("org.example.controller.PetController#createPet", "org.example.model.Pet#setName"));
		testMappings.put("PetControllerTests#testCreatePet", petCoverage);

		Map<String, List<String>> vetCoverage = new HashMap<>();
		vetCoverage.put("classes", List.of("org.example.controller.VetController", "org.example.model.Vet"));
		vetCoverage.put("methods", List.of("org.example.controller.VetController#showVetList", "org.example.model.Vet#getSpecialties"));
		testMappings.put("VetControllerTests#testShowVets", vetCoverage);

		data.setTestMappings(testMappings);
		return data;
	}

	/**
	 * Builds a ReportData with multiple methods per test class
	 * to test class-level expansion.
	 */
	private ReportData buildExpandedData()
	{
		ReportData data = new ReportData();
		data.setBaseBranch("main");
		data.setCommitId("abc1234567890abcdef1234567890abcdef123456");
		data.setTimestamp("2026-04-11T10:00:00Z");
		data.setCurrentBranch("feature/test");
		data.setCommitDistance(3);
		data.setChangedClasses(Set.of("org.example.controller.OwnerController"));
		data.setChangedMethods(Set.of("org.example.controller.OwnerController#showOwner"));
		data.setSelectedTests(Set.of("OwnerControllerTests#testShowOwner"));
		data.setFullSuite(false);
		data.setNone(false);

		Map<String, Map<String, List<String>>> testMappings = new HashMap<>();

		// 3 methods in OwnerControllerTests
		Map<String, List<String>> ownerShow = new HashMap<>();
		ownerShow.put("classes", List.of("org.example.controller.OwnerController"));
		ownerShow.put("methods", List.of("org.example.controller.OwnerController#showOwner"));
		testMappings.put("OwnerControllerTests#testShowOwner", ownerShow);

		Map<String, List<String>> ownerUpdate = new HashMap<>();
		ownerUpdate.put("classes", List.of("org.example.controller.OwnerController"));
		ownerUpdate.put("methods", List.of("org.example.controller.OwnerController#updateOwner"));
		testMappings.put("OwnerControllerTests#testUpdateOwner", ownerUpdate);

		Map<String, List<String>> ownerDelete = new HashMap<>();
		ownerDelete.put("classes", List.of("org.example.controller.OwnerController"));
		ownerDelete.put("methods", List.of("org.example.controller.OwnerController#deleteOwner"));
		testMappings.put("OwnerControllerTests#testDeleteOwner", ownerDelete);

		// 2 methods in PetControllerTests
		Map<String, List<String>> petCreate = new HashMap<>();
		petCreate.put("classes", List.of("org.example.controller.PetController"));
		petCreate.put("methods", List.of("org.example.controller.PetController#createPet"));
		testMappings.put("PetControllerTests#testCreatePet", petCreate);

		Map<String, List<String>> petUpdate = new HashMap<>();
		petUpdate.put("classes", List.of("org.example.controller.PetController"));
		petUpdate.put("methods", List.of("org.example.controller.PetController#updatePet"));
		testMappings.put("PetControllerTests#testUpdatePet", petUpdate);

		data.setTestMappings(testMappings);
		return data;
	}

	@Test
	void coverageMetricsTabRendersWhenDataPresent()
	{
		ReportData data = buildBasicData(Set.of("OwnerControllerTests#testShowOwner"));
		Map<String, ClassCoverageMetrics> metrics = new HashMap<>();
		metrics.put("org.example.controller.OwnerController",
				new ClassCoverageMetrics(5, 15, 2, 8));
		data.setClassMetrics(metrics);

		String html = generator.generate(data);

		assertTrue(html.contains("Coverage Metrics"));
		assertTrue(html.contains("metrics-table"));
		assertTrue(html.contains("OwnerController"));
		assertTrue(html.contains("sort-arrow"));
		assertTrue(html.contains("sortMetrics"));
		assertTrue(html.contains("filter-btn"));
	}
}
