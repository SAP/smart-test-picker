// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RemoteAgentOutputCrashRecoveryIntegrationTest {
	@Test void hardCrashLosesOnlyObservationsNotIncludedInTheLastSuccessfulCheckpoint() throws Exception {
		Path directory = Files.createTempDirectory("remote-output-checkpoint-crash");
		Path output = directory.resolve("observations.json");
		Process crashed = launchCrash(output, "checkpoint", directory.resolve("crash.log"));
		awaitReady(crashed, directory.resolve("crash.log"));
		crashed.destroyForcibly();
		assertTrue(crashed.waitFor(10, TimeUnit.SECONDS));
		String json = Files.readString(output);
		assertTrue(RemoteObservationJson.isValidSchemaV2(json), json);
		assertTrue(json.contains("persistedBeforeCrash"), json);
		assertFalse(json.contains("lostBeforeCrash"), "uncheckpointed in-memory observations are lost on hard crash");
		assertTrue(Files.exists(output.resolveSibling(output.getFileName() + ".inprogress")), "crash marker remains for restart diagnosis");
	}

	@Test void forcedCrashBeforeAndAfterRecordingLeavesRecoverableStateAndDoesNotRecoverMemoryOnlyData() throws Exception {
		for (String mode : new String[] {"reserve", "record"}) {
			Path directory = Files.createTempDirectory("remote-output-crash-" + mode);
			Path output = directory.resolve("observations.json");
			Path marker = output.resolveSibling(output.getFileName() + ".inprogress");
			Path lease = output.resolveSibling(output.getFileName() + ".lock");
			Process crashed = launchCrash(output, mode, directory.resolve("crash.log"));
			awaitReady(crashed, directory.resolve("crash.log"));
			assertTrue(Files.exists(marker));
			assertTrue(Files.exists(lease));
			assertTrue(Files.isRegularFile(output));
			assertEquals(0, Files.size(output));
			Process competing = launchClean(output, directory.resolve("competing.log"));
			assertTrue(competing.waitFor(20, TimeUnit.SECONDS));
			String competingLog = Files.readString(directory.resolve("competing.log"));
			assertNotEquals(0, competing.exitValue(), competingLog);
			assertTrue(competingLog.contains("currently owns this output path"), competingLog);
			assertTrue(Files.exists(marker), "a competing JVM must not clean an active run");
			crashed.destroyForcibly();
			assertTrue(crashed.waitFor(10, TimeUnit.SECONDS), "forced process termination did not complete");
			assertTrue(Files.exists(marker), "SIGKILL-like termination must bypass marker cleanup");

			String ownedPrefix = RemoteOutputFile.temporaryPrefixFor(output);
			Path staleTemporary = directory.resolve(ownedPrefix + UUID.randomUUID() + ".tmp");
			Path unrelatedTemporary = directory.resolve("unrelated-output.tmp");
			Files.writeString(staleTemporary, "partial JSON from interrupted move");
			Files.writeString(unrelatedTemporary, "must remain untouched");

			Path recoveryLog = directory.resolve("recovery.log");
			Process recovered = launchClean(output, recoveryLog);
			assertTrue(recovered.waitFor(20, TimeUnit.SECONDS), "recovery JVM did not stop");
			String recoveryOutput = Files.readString(output);
			assertEquals(0, recovered.exitValue(), Files.readString(recoveryLog));
			assertTrue(RemoteObservationJson.isValidSchemaV2(recoveryOutput), recoveryOutput);
			assertTrue(recoveryOutput.contains("shutdown-test"), recoveryOutput);
			assertFalse(recoveryOutput.contains("lost-test"), "in-memory observations are not recoverable after hard crash");
			assertFalse(Files.exists(marker), "clean recovery removes in-progress marker");
			assertFalse(Files.exists(staleTemporary), "recovery removes only temp files for this output");
			assertEquals("must remain untouched", Files.readString(unrelatedTemporary));
		}
	}

	@Test void malformedMarkerAndOrphanTempsFailWithoutDeletingUnprovenArtifacts() throws Exception {
		Path directory = Files.createTempDirectory("remote-output-malformed-state");
		Path output = directory.resolve("observations.json");
		Path marker = output.resolveSibling(output.getFileName() + ".inprogress");
		Files.writeString(marker, "not an STP marker");
		Process malformedMarker = launchClean(output, directory.resolve("bad-marker.log"));
		assertTrue(malformedMarker.waitFor(20, TimeUnit.SECONDS));
		String markerLog = Files.readString(directory.resolve("bad-marker.log"));
		assertNotEquals(0, malformedMarker.exitValue(), markerLog);
		assertTrue(markerLog.contains("malformed in-progress marker"), markerLog);
		assertTrue(Files.exists(marker), "malformed marker is preserved for inspection");
		assertFalse(Files.exists(output));

		Files.delete(marker);
		Path orphan = directory.resolve(RemoteOutputFile.temporaryPrefixFor(output) + UUID.randomUUID() + ".tmp");
		Files.writeString(orphan, "orphan");
		Process orphanTemp = launchClean(output, directory.resolve("orphan-temp.log"));
		assertTrue(orphanTemp.waitFor(20, TimeUnit.SECONDS));
		String orphanLog = Files.readString(directory.resolve("orphan-temp.log"));
		assertNotEquals(0, orphanTemp.exitValue(), orphanLog);
		assertTrue(orphanLog.contains("orphan temporary artifacts"), orphanLog);
		assertTrue(Files.exists(orphan), "temp without a valid stale marker is not deleted");
		assertFalse(Files.exists(output));
	}

	@Test void corruptFinalWithValidInterruptedMarkerIsRecoveredAsIncomplete() throws Exception {
		Path directory = Files.createTempDirectory("remote-output-corrupt-final");
		Path output = directory.resolve("observations.json");
		Path marker = output.resolveSibling(output.getFileName() + ".inprogress");
		Files.createFile(marker);
		Files.write(output, new byte[] {(byte) 0xc3, (byte) 0x28}); // Invalid UTF-8 from an interrupted non-atomic move.
		Path stale = directory.resolve(RemoteOutputFile.temporaryPrefixFor(output) + UUID.randomUUID() + ".tmp");
		Files.writeString(stale, "partially moved fallback file");

		Path log = directory.resolve("restart.log");
		Process restarted = launchClean(output, log);
		assertTrue(restarted.waitFor(20, TimeUnit.SECONDS));
		assertEquals(0, restarted.exitValue(), Files.readString(log));
		String json = Files.readString(output);
		assertTrue(RemoteObservationJson.isValidSchemaV2(json), json);
		assertFalse(Files.exists(marker));
		assertFalse(Files.exists(stale));
	}

	@Test void recoversLegacyEmptyReservationAndExactLegacyTempButLeavesOtherTemps() throws Exception {
		Path directory = Files.createTempDirectory("remote-output-legacy-state");
		Path output = directory.resolve("observations.json");
		Path legacyTemporary = output.resolveSibling(output.getFileName() + ".tmp");
		Path unrelatedTemporary = directory.resolve("different-output.tmp");
		Files.createFile(output);
		Files.writeString(legacyTemporary, "old incomplete document");
		Files.writeString(unrelatedTemporary, "unrelated");

		Path log = directory.resolve("recovery.log");
		Process recovered = launchClean(output, log);
		assertTrue(recovered.waitFor(20, TimeUnit.SECONDS));
		assertEquals(0, recovered.exitValue(), Files.readString(log));
		assertTrue(RemoteObservationJson.isValidSchemaV2(Files.readString(output)));
		assertFalse(Files.exists(legacyTemporary));
		assertEquals("unrelated", Files.readString(unrelatedTemporary));
	}

	@Test void validCompletedOutputWithStaleMarkerIsPreservedAndStartupFails() throws Exception {
		Path directory = Files.createTempDirectory("remote-output-moved-before-crash");
		Path output = directory.resolve("observations.json");
		Path log = directory.resolve("clean.log");
		Process first = launchClean(output, log);
		assertTrue(first.waitFor(20, TimeUnit.SECONDS));
		assertEquals(0, first.exitValue(), Files.readString(log));
		String completed = Files.readString(output);

		Path marker = output.resolveSibling(output.getFileName() + ".inprogress");
		Files.createFile(marker); // Crash after final move but before marker cleanup.
		Path staleTemporary = directory.resolve(RemoteOutputFile.temporaryPrefixFor(output) + UUID.randomUUID() + ".tmp");
		Path unrelatedTemporary = directory.resolve("other.tmp");
		Files.writeString(staleTemporary, "stale");
		Files.writeString(unrelatedTemporary, "unrelated");

		Process restart = launchClean(output, directory.resolve("restart.log"));
		assertTrue(restart.waitFor(20, TimeUnit.SECONDS));
		String restartLog = Files.readString(directory.resolve("restart.log"));
		assertNotEquals(0, restart.exitValue(), restartLog);
		assertTrue(restartLog.contains(output.toAbsolutePath().normalize().toString()), restartLog);
		assertTrue(RemoteObservationJson.isValidSchemaV2(Files.readString(output)));
		assertEquals(completed, Files.readString(output), "completed output must never be overwritten");
		assertFalse(Files.exists(marker));
		assertFalse(Files.exists(staleTemporary));
		assertEquals("unrelated", Files.readString(unrelatedTemporary));
	}

	private static Process launchCrash(Path output, String mode, Path log) throws Exception {
		return launch(output, log, RemoteOutputCrashFixtureMain.class.getName(), mode);
	}

	private static Process launchClean(Path output, Path log) throws Exception {
		return launch(output, log, RemoteOutputLifecycleFixtureMain.class.getName());
	}

	private static Process launch(Path output, Path log, String main, String... arguments) throws Exception {
		Files.createDirectories(output.getParent());
		String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		Path agent = Path.of(System.getProperty("stp.remote.agent.jar"));
		java.util.List<String> command = new java.util.ArrayList<>(java.util.List.of(javaExecutable,
				"-javaagent:" + agent + "=output=" + output + ";includes=example.remote.",
				"-cp", System.getProperty("java.class.path"), main));
		command.addAll(java.util.List.of(arguments));
		return new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
	}

	private static void awaitReady(Process process, Path log) throws Exception {
		long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		while (System.nanoTime() < deadline && process.isAlive()) {
			if (Files.exists(log) && Files.readString(log).contains("CRASH_FIXTURE_READY:")) return;
			Thread.sleep(25);
		}
		throw new AssertionError("crash fixture did not become ready: " + (Files.exists(log) ? Files.readString(log) : "no log"));
	}
}
