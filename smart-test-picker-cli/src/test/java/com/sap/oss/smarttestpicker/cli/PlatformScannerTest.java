// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.cli;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;


class PlatformScannerTest
{

	@TempDir
	Path tempDir;

	@Test
	void discoverClassesDirs_findsExtensionClasses() throws IOException
	{
		File platform = tempDir.resolve("platform").toFile();
		mkdirs(platform, "ext/core/classes");
		mkdirs(platform, "ext/commons/classes");

		PlatformScanner scanner = new PlatformScanner(platform, new ConsoleLogger());
		List<File> dirs = scanner.discoverClassesDirs();

		assertEquals(2, dirs.size());
		assertTrue(dirs.stream().anyMatch(f -> f.getPath().contains("core")));
		assertTrue(dirs.stream().anyMatch(f -> f.getPath().contains("commons")));
	}

	@Test
	void discoverSourceDirs_findsExtensionSrc() throws IOException
	{
		File platform = tempDir.resolve("platform").toFile();
		mkdirs(platform, "ext/core/src");
		mkdirs(platform, "ext/commons/src");

		PlatformScanner scanner = new PlatformScanner(platform, new ConsoleLogger());
		List<File> dirs = scanner.discoverSourceDirs();

		assertEquals(2, dirs.size());
		assertTrue(dirs.stream().anyMatch(f -> f.getPath().contains("core")));
		assertTrue(dirs.stream().anyMatch(f -> f.getPath().contains("commons")));
	}

	@Test
	void discoverClassesDirs_includesBootstrapClasses() throws IOException
	{
		File platform = tempDir.resolve("platform").toFile();
		mkdirs(platform, "ext/core/classes");
		mkdirs(platform, "bootstrap/classes");

		PlatformScanner scanner = new PlatformScanner(platform, new ConsoleLogger());
		List<File> dirs = scanner.discoverClassesDirs();

		assertEquals(2, dirs.size());
		assertTrue(dirs.stream().anyMatch(f -> f.getPath().contains("bootstrap")));
	}

	@Test
	void discoverClassesDirs_parsesExtensionsXml() throws IOException
	{
		File platform = tempDir.resolve("bin/platform").toFile();
		platform.mkdirs();

		File customExt = tempDir.resolve("bin/custom/myext/classes").toFile();
		customExt.mkdirs();

		String xml = "<?xml version=\"1.0\"?>\n"
				+ "<platformconfig><extensions>\n"
				+ "  <path dir=\"" + tempDir.resolve("bin/custom").toFile().getAbsolutePath() + "\"/>\n"
				+ "</extensions></platformconfig>\n";
		writeFile(new File(platform, "extensions.xml"), xml);

		PlatformScanner scanner = new PlatformScanner(platform, new ConsoleLogger());
		List<File> dirs = scanner.discoverClassesDirs();

		assertTrue(dirs.stream().anyMatch(f -> f.getAbsolutePath().contains("myext")),
				"Should discover classes from extensions.xml path: " + dirs);
	}

	@Test
	void discoverClassesDirs_resolvesPlatformBinDir() throws IOException
	{
		// platformHome = bin/platform → parent = bin/
		File platform = tempDir.resolve("bin/platform").toFile();
		platform.mkdirs();

		File customExt = tempDir.resolve("bin/custom/addon/classes").toFile();
		customExt.mkdirs();

		String xml = "<?xml version=\"1.0\"?>\n"
				+ "<platformconfig><extensions>\n"
				+ "  <path dir=\"${PLATFORM_BIN_DIR}/custom\"/>\n"
				+ "</extensions></platformconfig>\n";
		writeFile(new File(platform, "extensions.xml"), xml);

		PlatformScanner scanner = new PlatformScanner(platform, new ConsoleLogger());
		List<File> dirs = scanner.discoverClassesDirs();

		assertTrue(dirs.stream().anyMatch(f -> f.getAbsolutePath().contains("addon")),
				"Should resolve ${PLATFORM_BIN_DIR} to platform parent: " + dirs);
	}

	@Test
	void discoverClassesDirs_emptyPlatform()
	{
		File platform = tempDir.resolve("empty").toFile();
		platform.mkdirs();

		PlatformScanner scanner = new PlatformScanner(platform, new ConsoleLogger());
		List<File> dirs = scanner.discoverClassesDirs();

		assertTrue(dirs.isEmpty());
	}

	@Test
	void discoverSourceDirs_ignoresNonExistentDirs() throws IOException
	{
		File platform = tempDir.resolve("platform").toFile();
		mkdirs(platform, "ext/core/src");
		// ext/commons exists as a directory but has no src/ child
		mkdirs(platform, "ext/commons");

		PlatformScanner scanner = new PlatformScanner(platform, new ConsoleLogger());
		List<File> dirs = scanner.discoverSourceDirs();

		assertEquals(1, dirs.size());
		assertTrue(dirs.get(0).getAbsolutePath().contains("core"));
	}

	private void mkdirs(File base, String relativePath)
	{
		new File(base, relativePath).mkdirs();
	}

	private void writeFile(File file, String content) throws IOException
	{
		file.getParentFile().mkdirs();
		try (FileWriter writer = new FileWriter(file))
		{
			writer.write(content);
		}
	}
}
