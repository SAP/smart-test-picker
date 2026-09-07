// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import com.sap.oss.smarttestpicker.coverage.model.CoverageMap;
import com.sap.oss.smarttestpicker.coverage.model.PublishedTestInventory;
import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;
import com.sap.oss.smarttestpicker.coverage.model.TestInventory;
import com.sap.oss.smarttestpicker.coverage.serialization.CoverageMapCodec;
import com.sap.oss.smarttestpicker.selector.HeadTestInventory;
import com.sap.oss.smarttestpicker.selector.HeadTestInventoryCodec;

class Task40PetClinicFixtureTest
{
	private static final Path FIXTURE = Path.of("../stp-petclinic-validation/task40/petclinic-coverage-map-v2.json");
	private static final Path HEAD_FIXTURE = Path.of("../stp-petclinic-validation/task40/petclinic-head-test-inventory.json");
	private static final Set<TestIdentity> NON_EXECUTABLE = Set.of(
			TestIdentity.parse("org.springframework.samples.petclinic.MySqlIntegrationTests#findAll"),
			TestIdentity.parse("org.springframework.samples.petclinic.MySqlIntegrationTests#ownerDetails"),
			TestIdentity.parse("org.springframework.samples.petclinic.PostgresIntegrationTests#findAll"),
			TestIdentity.parse("org.springframework.samples.petclinic.PostgresIntegrationTests#ownerDetails"));

	@BeforeAll static void regenerateOnlyFromCodecAndAuthoritativeInventoryWhenExplicitlyRequested() throws Exception
	{
		String source = System.getProperty("task40.sourceMap");
		String head = System.getProperty("task40.headInventory");
		if (source == null || head == null) return;
		CoverageMapCodec codec = new CoverageMapCodec();
		CoverageMap old = codec.deserialize(Files.readAllBytes(Path.of(source)));
		HeadTestInventory authoritative = new HeadTestInventoryCodec().read(Path.of(head).toFile());
		CoverageMap aligned = CoverageMapPublication.alignExpectedInventory(old,
				new TestInventory(old.revision(), authoritative.runnableTests()), NON_EXECUTABLE);
		Files.createDirectories(FIXTURE.getParent());
		Files.write(FIXTURE, codec.serialize(aligned));
		new HeadTestInventoryCodec().write(HEAD_FIXTURE.toFile(), authoritative);
	}

	@Test void canonicalAlignedFixtureHasExactLogicalPartitionAndPreservedCoverage() throws Exception
	{
		CoverageMap map = new CoverageMapCodec().deserialize(Files.readAllBytes(FIXTURE));
		assertEquals("88e37c15cf6fc8490b01bc3e8e2c800cec1ac272", map.revision().value());
		assertTrue(map.completeness().isComplete());
		assertEquals(73, PublishedTestInventory.logical(map).size());
		assertEquals(69, map.tests().size());
		assertTrue(map.unmapped().isEmpty());
		assertEquals(NON_EXECUTABLE, PublishedTestInventory.intentionallyNonExecutable(map));
		assertEquals(PublishedTestInventory.logical(map),
				new HeadTestInventoryCodec().read(HEAD_FIXTURE.toFile()).runnableTests());
		assertEquals(418, map.statistics().classEdges());
		assertEquals(1343, map.statistics().methodEdges());
		assertEquals(10, map.statistics().setupScopes());
	}
}
