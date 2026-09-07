// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.sap.oss.smarttestpicker.coverage.model.CoverageFragment;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMapRevision;
import com.sap.oss.smarttestpicker.coverage.model.ShardId;
import com.sap.oss.smarttestpicker.coverage.serialization.CoverageFragmentCodec;
import org.gradle.api.GradleException;

import static org.junit.jupiter.api.Assertions.*;

class CoverageCollectorBackendTest {
	@TempDir Path temporary;
	@Test
	void capabilitiesDescribeCurrentSemantics() {
		CollectorCapabilities asm = new AsmCoverageCollectorBackend(null).capabilities();
		assertTrue(asm.classCoverage());
		assertTrue(asm.exactMethodCoverage());
		assertTrue(asm.setupScopes());
		assertTrue(asm.descriptorAwareMethods());
		assertTrue(asm.directSchemaV2Fragment());

		CollectorCapabilities jacoco = new JacocoCoverageCollectorBackend(null).capabilities();
		assertTrue(jacoco.classCoverage());
		assertFalse(jacoco.exactMethodCoverage());
		assertFalse(jacoco.setupScopes());
		assertFalse(jacoco.descriptorAwareMethods());
		assertFalse(jacoco.directSchemaV2Fragment());
	}

	@Test
	void mappingVerificationRejectsMissingMalformedStaleAndIncompleteFragments() throws Exception {
		Path fragment = temporary.resolve("fragment.json");
		assertThrows(GradleException.class,
				() -> AsmCoverageCollectorBackend.verifyFragment(fragment.toFile(), "revision", "shard"));
		Files.writeString(fragment, "not-json");
		assertThrows(GradleException.class,
				() -> AsmCoverageCollectorBackend.verifyFragment(fragment.toFile(), "revision", "shard"));

		write(fragment, "old-revision", "shard", true);
		assertThrows(GradleException.class,
				() -> AsmCoverageCollectorBackend.verifyFragment(fragment.toFile(), "revision", "shard"));
		write(fragment, "revision", "wrong-shard", true);
		assertThrows(GradleException.class,
				() -> AsmCoverageCollectorBackend.verifyFragment(fragment.toFile(), "revision", "shard"));
		write(fragment, "revision", "shard", false);
		assertThrows(GradleException.class,
				() -> AsmCoverageCollectorBackend.verifyFragment(fragment.toFile(), "revision", "shard"));
		write(fragment, "revision", "shard", true);
		AsmCoverageCollectorBackend.verifyFragment(fragment.toFile(), "revision", "shard");
	}

	private static void write(Path path, String revision, String shard, boolean completed) throws Exception {
		var fragment = new CoverageFragment(2, new CoverageMapRevision(revision), new ShardId(shard),
				Map.of(), List.of(), List.of(), completed);
		Files.write(path, new CoverageFragmentCodec().serialize(fragment));
	}
}
