// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RemoteFragmentMetadataTest {
	@Test void explicitMetadataWinsAndIsRetained() {
		RemoteAgentConfiguration configuration = config("serviceId=pricing;instanceId=instance-123;revision=abc123",
				Map.of(RemoteFragmentMetadata.SERVICE_ENV, "env-service", RemoteFragmentMetadata.INSTANCE_ENV, "env-instance",
						RemoteFragmentMetadata.REVISION_ENV, "env-revision"), "host-name");
		assertEquals(new RemoteFragmentMetadata("pricing", "instance-123", "abc123"), configuration.source());
	}

	@Test void configuredEnvironmentProvidesServiceRevisionAndInstance() {
		RemoteAgentConfiguration configuration = config("instanceIdEnv=CUSTOM_INSTANCE",
				Map.of(RemoteFragmentMetadata.SERVICE_ENV, "catalog", "CUSTOM_INSTANCE", "pod-7",
						RemoteFragmentMetadata.REVISION_ENV, "rev-42"), "host-name");
		assertEquals(new RemoteFragmentMetadata("catalog", "pod-7", "rev-42"), configuration.source());
	}

	@Test void instanceFallbackUsesHostnameThenUniqueJvmRunId() {
		RemoteAgentConfiguration host = config("", Map.of(RemoteFragmentMetadata.SERVICE_ENV, "svc",
				RemoteFragmentMetadata.REVISION_ENV, "rev"), "host-a");
		assertEquals("host-a", host.source().instanceId());

		RemoteAgentConfiguration first = RemoteAgentConfiguration.parse(baseArgs("") ,
				Map.of(RemoteFragmentMetadata.SERVICE_ENV, "svc", RemoteFragmentMetadata.REVISION_ENV, "rev"),
				() -> null, () -> UUID.randomUUID().toString());
		RemoteAgentConfiguration second = RemoteAgentConfiguration.parse(baseArgs("") ,
				Map.of(RemoteFragmentMetadata.SERVICE_ENV, "svc", RemoteFragmentMetadata.REVISION_ENV, "rev"),
				() -> { throw new IllegalStateException("hostname unavailable"); }, () -> UUID.randomUUID().toString());
		assertTrue(first.source().instanceId().startsWith("jvm-"));
		assertTrue(second.source().instanceId().startsWith("jvm-"));
		assertNotEquals(first.source().instanceId(), second.source().instanceId());
	}

	@Test void missingOrInvalidRequiredMetadataFailsClearly() {
		IllegalArgumentException missingService = assertThrows(IllegalArgumentException.class,
				() -> RemoteAgentConfiguration.parse(baseArgs(""), Map.of(RemoteFragmentMetadata.REVISION_ENV, "rev"), () -> "host", () -> "uuid"));
		assertTrue(missingService.getMessage().contains("serviceId"));
		IllegalArgumentException invalid = assertThrows(IllegalArgumentException.class,
				() -> RemoteAgentConfiguration.parse(baseArgs("serviceId=svc;revision=" + "x".repeat(257)), Map.of(), () -> "host", () -> "uuid"));
		assertTrue(invalid.getMessage().contains("revision"));
	}

	@Test void metadataAppearsInPersistedCheckpointAndDebugViewsAndSurvivesFurtherCheckpoints() throws Exception {
		var output = Files.createTempDirectory("remote-fragment-metadata").resolve("observations.json");
		RemoteRecorder.clearForTests();
		RemoteFragmentMetadata metadata = new RemoteFragmentMetadata("pricing", "instance-123", "abc123");
		RemoteRecorder.install(output, metadata);
		RemoteRequestIdentity identity = new RemoteRequestIdentity("suite", "test", "request");
		try (var scope = RemoteTestContext.enter(identity)) { RemoteRecorder.methodHit("example.Service", "first", "()V"); }
		String live = RemoteRecorder.logicalSnapshot();
		assertTrue(live.contains("\"serviceId\":\"pricing\""), live);
		assertTrue(live.contains("\"instanceId\":\"instance-123\""), live);
		assertTrue(live.contains("\"revision\":\"abc123\""), live);
		RemoteRecorder.checkpointNow();
		String persisted = Files.readString(output);
		assertTrue(persisted.contains("\"serviceId\":\"pricing\""), persisted);
		try (var scope = RemoteTestContext.enter(identity)) { RemoteRecorder.methodHit("example.Service", "second", "()V"); }
		RemoteRecorder.checkpointNow();
		assertTrue(Files.readString(output).contains("\"revision\":\"abc123\""));
		RemoteRecorder.writeOutput();
	}

	private static RemoteAgentConfiguration config(String extras, Map<String, String> environment, String host) {
		return RemoteAgentConfiguration.parse(baseArgs(extras), environment, () -> host, () -> "run-id");
	}

	private static String baseArgs(String extras) {
		return "output=" + FilesPath.OUTPUT + ";includes=example." + (extras.isEmpty() ? "" : ";" + extras);
	}

	private static final class FilesPath {
		private static final java.nio.file.Path OUTPUT = java.nio.file.Path.of("build", "metadata-test.json").toAbsolutePath().normalize();
	}
}
