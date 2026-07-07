// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.engine;

import java.io.File;


/**
 * Utility methods for safe file operations across all modules.
 */
public final class FileUtils
{

	private FileUtils()
	{
	}

	/**
	 * Creates parent directories for a file if they do not exist.
	 * Handles the case where the file is in the current directory (no parent).
	 *
	 * @param file the file whose parent directories should be created
	 */
	public static void ensureParentDirExists(File file)
	{
		File parent = file.getParentFile();
		if (parent != null)
		{
			parent.mkdirs();
		}
	}
}
