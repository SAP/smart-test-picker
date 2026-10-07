// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RemoteAgentConfigurationTest {
	@Test void flushIntervalDefaultsToOneMinuteAndCanBeConfigured() {
		assertEquals(60, RemoteAgentConfiguration.parse("output=out.json;includes=example.;serviceId=fixture-service;revision=test-revision").flushIntervalSeconds());
		assertEquals(7, RemoteAgentConfiguration.parse("output=out.json;includes=example.;serviceId=fixture-service;revision=test-revision;flushIntervalSeconds=7").flushIntervalSeconds());
		assertEquals(1, RemoteAgentConfiguration.parse("output=out.json;includes=example.;serviceId=fixture-service;revision=test-revision;flushIntervalSeconds=1").flushIntervalSeconds());
	}

	@Test void invalidFlushIntervalsFailClearly() {
		for (String value : new String[] {"0", "-1", "abc", "999999999999999999"}) {
			IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
					() -> RemoteAgentConfiguration.parse("output=out.json;includes=example.;serviceId=fixture-service;revision=test-revision;flushIntervalSeconds=" + value));
			assertTrue(failure.getMessage().contains("flushIntervalSeconds"));
		}
	}

	@Test void relativeOutputIsResolvedAgainstWorkingDirectoryAndNormalized() {
		Path configured = Path.of("build", "remote", "..", "observations.json").toAbsolutePath().normalize();
		RemoteAgentConfiguration parsed = RemoteAgentConfiguration.parse("output=build/remote/../observations.json;includes=example.;serviceId=fixture-service;revision=test-revision");
		assertEquals(configured, parsed.output());
		assertTrue(parsed.output().isAbsolute());
		assertNull(parsed.debugPort());
	}

	@Test void debugPortIsOptionalAndValidated() {
		assertEquals(9465, RemoteAgentConfiguration.parse("output=out.json;includes=example.;serviceId=fixture-service;revision=test-revision;debugPort=9465").debugPort());
		for (String value : new String[] {"0", "-1", "65536", "abc"}) {
			IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
					() -> RemoteAgentConfiguration.parse("output=out.json;includes=example.;serviceId=fixture-service;revision=test-revision;debugPort=" + value));
			assertTrue(failure.getMessage().contains("debugPort"));
		}
	}

	@Test void emptyAndInvalidOutputPathsFailWithUsefulErrors() {
		IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
				() -> RemoteAgentConfiguration.parse("output= ;includes=example.;serviceId=fixture-service;revision=test-revision"));
		assertTrue(empty.getMessage().contains("output"));
		IllegalArgumentException invalid = assertThrows(IllegalArgumentException.class,
				() -> RemoteAgentConfiguration.parse("output=bad" + (char) 0 + "path.json;includes=example.;serviceId=fixture-service;revision=test-revision"));
		assertTrue(invalid.getMessage().contains("bad"));
		assertTrue(invalid.getMessage().contains("output path"));
	}

	@Test void startupCreatesMissingParentsAndOutputFile() throws Exception {
		Path output = Files.createTempDirectory("remote-output-create").resolve("missing/nested/observations.json");
		RemoteRecorder.clearForTests();
		RemoteRecorder.install(output);
		assertTrue(Files.isRegularFile(output));
		assertEquals(0, Files.size(output));
	}

	@Test void existingOutputIsRejectedWithoutChangingItsContents() throws Exception {
		Path output = Files.createTempFile("remote-output-existing", ".json");
		Files.writeString(output, "previous run");
		RemoteRecorder.clearForTests();
		IllegalStateException failure = assertThrows(IllegalStateException.class, () -> RemoteRecorder.install(output));
		assertTrue(failure.getMessage().contains(output.toAbsolutePath().normalize().toString()), failure.getMessage());
		assertEquals("previous run", Files.readString(output));
	}

	@Test void outputDirectoryAndParentThatIsAFileFailAtStartupWithPath() throws Exception {
		Path root = Files.createTempDirectory("remote-output-invalid");
		Path directory = Files.createDirectory(root.resolve("is-directory"));
		RemoteRecorder.clearForTests();
		IllegalStateException directoryFailure = assertThrows(IllegalStateException.class, () -> RemoteRecorder.install(directory));
		assertTrue(directoryFailure.getMessage().contains(directory.toString()), directoryFailure.getMessage());

		Path parentFile = Files.writeString(root.resolve("parent-file"), "not a directory");
		Path child = parentFile.resolve("observations.json");
		RemoteRecorder.clearForTests();
		IllegalStateException parentFailure = assertThrows(IllegalStateException.class, () -> RemoteRecorder.install(child));
		assertTrue(parentFailure.getMessage().contains(child.toAbsolutePath().normalize().toString()), parentFailure.getMessage());
	}

	@Test void unwritableParentFailsAtStartupWhenFilesystemEnforcesPermissions() throws Exception {
		Path parent = Files.createTempDirectory("remote-output-read-only");
		boolean posixPermissionsSet = false;
		try {
			Files.setPosixFilePermissions(parent, java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x"));
			posixPermissionsSet = true;
			if (Files.isWritable(parent)) return; // Some privileged test users bypass POSIX mode bits.
			Path output = parent.resolve("observations.json");
			RemoteRecorder.clearForTests();
			IllegalStateException failure = assertThrows(IllegalStateException.class, () -> RemoteRecorder.install(output));
			assertTrue(failure.getMessage().contains(output.toString()), failure.getMessage());
		} catch (UnsupportedOperationException ignored) {
			// The target filesystem has no POSIX permission model.
		} finally {
			if (posixPermissionsSet) Files.setPosixFilePermissions(parent, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
			Files.deleteIfExists(parent.resolve("observations.json"));
			Files.deleteIfExists(parent);
		}
	}
}
