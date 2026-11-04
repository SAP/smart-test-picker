// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StpCollectorSelectionTest
{
	@Test void selectionKeepsLegacyDefaultAndRejectsUnknownCollectors()
	{
		assertEquals(StpCollectorSelection.JACOCO, StpCollectorSelection.parse(null));
		assertEquals(StpCollectorSelection.JACOCO, StpCollectorSelection.parse(""));
		assertEquals(StpCollectorSelection.ASM, StpCollectorSelection.parse(" asm "));
		assertEquals(StpCollectorSelection.JACOCO, StpCollectorSelection.parse("jacoco"));
		assertThrows(IllegalArgumentException.class, () -> StpCollectorSelection.parse("both"));
	}

	@Test void unselectedComponentsIgnoreEveryCallback()
	{
		String previous = System.getProperty(StpCollectorSelection.PROPERTY);
		System.setProperty(StpCollectorSelection.PROPERTY, "ASM");
		try {
			boolean active = JacocoPerTestListener.active;
			var listener = new JacocoPerTestListener();
			listener.testPlanExecutionStarted(null);
			listener.executionStarted(null);
			listener.executionFinished(null, null);
			listener.executionSkipped(null, "ignored");
			var extension = new TestLifecycleExtension();
			extension.beforeTestExecution(null);
			extension.afterTestExecution(null);
			assertEquals(active, JacocoPerTestListener.active);
		} finally {
			if (previous == null) System.clearProperty(StpCollectorSelection.PROPERTY);
			else System.setProperty(StpCollectorSelection.PROPERTY, previous);
		}
	}

	@Test void spiFailsStartupForSelectedJacocoButIsSilentForAsm(@TempDir Path temporary) throws Exception
	{
		for (String collector : new String[] { "ASM", "JACOCO" }) {
			Path marker = temporary.resolve(collector + ".executed");
			Path log = temporary.resolve(collector + ".log");
			Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
					"-D" + StpCollectorSelection.PROPERTY + "=" + collector,
					"-Dstp.probe.marker=" + marker, "-Dstp.exec.dir=" + temporary.resolve("exec"),
					"-cp", System.getProperty("java.class.path"), CollectorSelectionForkMain.class.getName())
					.redirectErrorStream(true).redirectOutput(log.toFile()).start();
			try {
				assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Collector probe timed out");
				String output = Files.readString(log);
				if (collector.equals("ASM")) {
					assertEquals(0, process.exitValue(), output);
					assertTrue(Files.exists(marker));
					assertFalse(Files.exists(temporary.resolve("exec")));
					assertFalse(output.contains("JacocoPerTestListener"), output);
					assertFalse(output.contains("Active JaCoCo runtime"), output);
				} else {
					assertNotEquals(0, process.exitValue(), output);
					assertFalse(Files.exists(marker), "Missing selected runtime must stop test execution");
					assertTrue(output.contains("Active JaCoCo runtime is unavailable for STP mapping"), output);
					assertTrue(output.contains("ServiceConfigurationError"), output);
				}
			} finally { if (process.isAlive()) process.destroyForcibly(); }
		}
	}
}
