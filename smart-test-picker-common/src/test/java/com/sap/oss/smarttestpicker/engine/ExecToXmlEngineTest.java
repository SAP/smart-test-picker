// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker.engine;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.data.SessionInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;


class ExecToXmlEngineTest
{

	@Test
	void emptyExecDirProducesNoReports(@TempDir Path tempDir) throws IOException
	{
		File execDir = tempDir.resolve("exec").toFile();
		execDir.mkdirs();
		File classesDir = tempDir.resolve("classes").toFile();
		classesDir.mkdirs();
		File reportDir = tempDir.resolve("reports").toFile();

		ExecToXmlEngine engine = new ExecToXmlEngine();
		engine.generateReports(execDir, classesDir, null, reportDir, new NoOpLogger());

		if (reportDir.exists())
		{
			File[] files = reportDir.listFiles();
			assertTrue(files == null || files.length == 0);
		}
	}

	@Test
	void nonSessionFilesAreSkipped(@TempDir Path tempDir) throws IOException
	{
		File execDir = tempDir.resolve("exec").toFile();
		execDir.mkdirs();
		Files.write(execDir.toPath().resolve("other_report.exec"), new byte[]{0});

		File classesDir = tempDir.resolve("classes").toFile();
		classesDir.mkdirs();
		File reportDir = tempDir.resolve("reports").toFile();

		ExecToXmlEngine engine = new ExecToXmlEngine();
		engine.generateReports(execDir, classesDir, null, reportDir, new NoOpLogger());

		if (reportDir.exists())
		{
			File[] files = reportDir.listFiles();
			assertTrue(files == null || files.length == 0);
		}
	}

	@Test
	void allExecFilterAcceptsAllExecFiles()
	{
		FilenameFilter filter = ExecToXmlEngine.allExecFilter();

		assertTrue(filter.accept(new File("."), "session_test.exec"));
		assertTrue(filter.accept(new File("."), "custom.exec"));
		assertTrue(filter.accept(new File("."), "anything.exec"));
		assertFalse(filter.accept(new File("."), "test.xml"));
		assertFalse(filter.accept(new File("."), "test.java"));
	}

	@Test
	void sessionFilterRejectsNonSessionFiles()
	{
		File execDir = new File(".");
		FilenameFilter sessionFilter = (dir, name) -> name.startsWith("session_") && name.endsWith(".exec");

		assertTrue(sessionFilter.accept(execDir, "session_MyTest#test.exec"));
		assertFalse(sessionFilter.accept(execDir, "other.exec"));
		assertFalse(sessionFilter.accept(execDir, "session_test.xml"));
	}

	@Test
	void generatesXmlFromRealExecFile(@TempDir Path tempDir) throws Exception
	{
		File classesDir = compileMinimalClass(tempDir);
		File execDir = tempDir.resolve("exec").toFile();
		execDir.mkdirs();
		writeExecFile(execDir, classesDir, "session_DummyTest#testRun");

		File reportDir = tempDir.resolve("reports").toFile();

		ExecToXmlEngine engine = new ExecToXmlEngine();
		engine.generateReports(execDir, classesDir, null, reportDir, new NoOpLogger());

		assertTrue(reportDir.exists());
		File xmlFile = new File(reportDir, "session_DummyTest#testRun.xml");
		assertTrue(xmlFile.exists(), "XML report should be generated");
		String xml = Files.readString(xmlFile.toPath());
		assertTrue(xml.contains("<?xml"));
		assertTrue(xml.contains("Dummy"));
	}

	@Test
	void multipleClassesDirsAreMerged(@TempDir Path tempDir) throws Exception
	{
		File classesDir1 = compileMinimalClass(tempDir, "Dummy", "dummy1");
		File classesDir2 = compileMinimalClass(tempDir, "Helper", "dummy2");

		File execDir = tempDir.resolve("exec").toFile();
		execDir.mkdirs();
		writeExecFileForDirs(execDir, java.util.List.of(classesDir1, classesDir2), "session_MultiTest#test");

		File reportDir = tempDir.resolve("reports").toFile();

		ExecToXmlEngine engine = new ExecToXmlEngine();
		engine.generateReports(execDir, java.util.List.of(classesDir1, classesDir2), null,
				reportDir, new NoOpLogger(), 1, ExecToXmlEngine.allExecFilter());

		assertTrue(reportDir.exists());
		File xmlFile = new File(reportDir, "session_MultiTest#test.xml");
		assertTrue(xmlFile.exists());
		String xml = Files.readString(xmlFile.toPath());
		assertTrue(xml.contains("Dummy"));
		assertTrue(xml.contains("Helper"));
	}

	@Test
	void skipsExecWithNoCoverage(@TempDir Path tempDir) throws Exception
	{
		File classesDir = compileMinimalClass(tempDir);
		File execDir = tempDir.resolve("exec").toFile();
		execDir.mkdirs();
		writeEmptyExecFile(execDir, "session_EmptyTest#test");

		File reportDir = tempDir.resolve("reports").toFile();

		ExecToXmlEngine engine = new ExecToXmlEngine();
		engine.generateReports(execDir, classesDir, null, reportDir, new NoOpLogger());

		if (reportDir.exists())
		{
			File xmlFile = new File(reportDir, "session_EmptyTest#test.xml");
			assertFalse(xmlFile.exists(), "XML should not be generated for exec with no coverage");
		}
	}

	private File compileMinimalClass(Path tempDir) throws IOException
	{
		return compileMinimalClass(tempDir, "Dummy", "classes");
	}

	private File compileMinimalClass(Path tempDir, String className, String outputDirName) throws IOException
	{
		Path srcDir = tempDir.resolve("src-" + outputDirName);
		srcDir.toFile().mkdirs();
		String source = "public class " + className + " { public int run() { int x = 1; x += 2; return x; } }";
		Files.writeString(srcDir.resolve(className + ".java"), source);

		File classesDir = tempDir.resolve(outputDirName).toFile();
		classesDir.mkdirs();

		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		assertNotNull(compiler, "Need JDK (not JRE) to run this test");
		int result = compiler.run(null, null, null,
				"-d", classesDir.getAbsolutePath(),
				srcDir.resolve(className + ".java").toString());
		assertEquals(0, result, "Compilation should succeed");

		return classesDir;
	}

	private void writeExecFile(File execDir, File classesDir, String sessionName) throws IOException
	{
		writeExecFileForDirs(execDir, java.util.List.of(classesDir), sessionName);
	}

	private void writeExecFileForDirs(File execDir, java.util.List<File> classesDirs, String sessionName) throws IOException
	{
		org.jacoco.core.data.ExecutionDataStore dataStore = new org.jacoco.core.data.ExecutionDataStore();

		for (File classesDir : classesDirs)
		{
			Files.walk(classesDir.toPath())
					.filter(p -> p.toString().endsWith(".class"))
					.forEach(classFile -> {
						try
						{
							byte[] classBytes = Files.readAllBytes(classFile);
							long classId = org.jacoco.core.internal.data.CRC64.classId(classBytes);
							String className = classFile.getFileName().toString().replace(".class", "");
							boolean[] probes = new boolean[64];
							java.util.Arrays.fill(probes, true);
							dataStore.put(new ExecutionData(classId, className, probes));
						}
						catch (IOException e)
						{
							throw new RuntimeException(e);
						}
					});
		}

		File execFile = new File(execDir, sessionName + ".exec");
		try (FileOutputStream fos = new FileOutputStream(execFile))
		{
			ExecutionDataWriter writer = new ExecutionDataWriter(fos);
			writer.visitSessionInfo(new SessionInfo(sessionName, 0, System.currentTimeMillis()));
			dataStore.accept(writer);
		}
	}

	private void writeEmptyExecFile(File execDir, String sessionName) throws IOException
	{
		File execFile = new File(execDir, sessionName + ".exec");
		try (FileOutputStream fos = new FileOutputStream(execFile))
		{
			ExecutionDataWriter writer = new ExecutionDataWriter(fos);
			writer.visitSessionInfo(new SessionInfo(sessionName, 0, System.currentTimeMillis()));
		}
	}

	private static class NoOpLogger implements EngineLogger
	{
		@Override
		public void info(String msg, Object... args) {}

		@Override
		public void warn(String msg, Object... args) {}
	}
}
