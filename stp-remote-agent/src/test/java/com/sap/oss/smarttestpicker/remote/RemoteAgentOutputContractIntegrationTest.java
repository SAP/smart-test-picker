// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RemoteAgentOutputContractIntegrationTest {
	@Test void cleanShutdownPersistsSchemaV2AndRestartWithSamePathFailsWithoutMerging() throws Exception {
		Path output = Files.createTempDirectory("remote-output-lifecycle").resolve("nested/observations.json");
		Path agentJar = Path.of(System.getProperty("stp.remote.agent.jar"));
		Path log = output.resolveSibling("agent.log");
		String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		Process first = launch(java, agentJar, output, log);
		assertTrue(first.waitFor(20, TimeUnit.SECONDS), "first agent JVM did not stop");
		String firstLog = Files.readString(log);
		assertEquals(0, first.exitValue(), firstLog);
		assertTrue(Files.isRegularFile(output), "agent must produce final output on clean JVM shutdown");
		String firstOutput = Files.readString(output);
		assertTrue(firstOutput.contains("\"schemaVersion\": 2"), firstOutput);
		assertTrue(firstOutput.contains("\"testSuiteId\":\"shutdown-suite\""), firstOutput);
		assertTrue(firstOutput.contains("\"testId\":\"shutdown-test\""), firstOutput);
		assertTrue(firstOutput.contains("\"requestId\":\"shutdown-request\""), firstOutput);
		assertTrue(firstOutput.contains("VetController#showResourcesVetList"), firstOutput);

		Process second = launch(java, agentJar, output, log);
		assertTrue(second.waitFor(20, TimeUnit.SECONDS), "second agent JVM did not fail during startup");
		String secondLog = Files.readString(log);
		assertNotEquals(0, second.exitValue(), secondLog);
		assertTrue(secondLog.contains(output.toAbsolutePath().normalize().toString()), secondLog);
		assertFalse(secondLog.contains("OUTPUT_FIXTURE_READY"), secondLog);
		assertEquals(firstOutput, Files.readString(output), "second JVM must not truncate, append, or merge the previous run");
	}

	private static Process launch(String java, Path agentJar, Path output, Path log) throws Exception {
		Files.createDirectories(output.getParent());
		return new ProcessBuilder(java,
				"-javaagent:" + agentJar + "=output=" + output + ";includes=example.remote.;serviceId=fixture-service;revision=test-revision",
				"-cp", System.getProperty("java.class.path"), RemoteOutputLifecycleFixtureMain.class.getName())
				.redirectErrorStream(true).redirectOutput(log.toFile()).start();
	}
}
