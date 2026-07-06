// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.engine;

import java.io.File;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;


/**
 * Tests for {@link FileUtils#ensureParentDirExists(File)}.
 * Verifies that parent directory creation handles all edge cases
 * including files without a parent path (current directory).
 */
class FileUtilsTest
{

	@TempDir
	Path tempDir;

	@Test
	void createsParentDirectoryWhenMissing()
	{
		File file = tempDir.resolve("sub/dir/output.json").toFile();
		assertFalse(file.getParentFile().exists());

		FileUtils.ensureParentDirExists(file);

		assertTrue(file.getParentFile().exists());
		assertTrue(file.getParentFile().isDirectory());
	}

	@Test
	void handlesFileWithNoParent()
	{
		// This is the bug case: new File("output.json").getParentFile() returns null
		File file = new File("output.json");
		assertNull(file.getParentFile());

		// Must not throw NullPointerException
		assertDoesNotThrow(() -> FileUtils.ensureParentDirExists(file));
	}

	@Test
	void handlesFileInCurrentDirectory()
	{
		// ./output.json has parent "." which exists
		File file = new File("./output.json");
		assertNotNull(file.getParentFile());

		assertDoesNotThrow(() -> FileUtils.ensureParentDirExists(file));
	}

	@Test
	void handlesNestedDirectoryCreation()
	{
		File file = tempDir.resolve("a/b/c/d/e/output.json").toFile();
		assertFalse(tempDir.resolve("a").toFile().exists());

		FileUtils.ensureParentDirExists(file);

		assertTrue(tempDir.resolve("a/b/c/d/e").toFile().exists());
	}

	@Test
	void doesNothingWhenParentAlreadyExists()
	{
		File file = tempDir.resolve("output.json").toFile();
		assertTrue(file.getParentFile().exists());

		assertDoesNotThrow(() -> FileUtils.ensureParentDirExists(file));
	}
}
