// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker.cli;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;


/**
 * Discovers source and compiled-classes directories from a custom platform installation.
 *
 * <p>Scans {@code ext/<name>/classes/} and {@code ext/<name>/src/} under platform home,
 * plus additional extension directories referenced by {@code extensions.xml}.</p>
 */
class PlatformScanner
{

	private final File platformHome;
	private final ConsoleLogger logger;

	PlatformScanner(File platformHome, ConsoleLogger logger)
	{
		this.platformHome = platformHome;
		this.logger = logger;
	}

	List<File> discoverClassesDirs()
	{
		List<File> dirs = new ArrayList<>();

		scanSubDirs(new File(platformHome, "ext"), "classes", dirs);

		File extensionsXml = new File(platformHome, "extensions.xml");
		if (extensionsXml.exists())
		{
			ExtensionPaths paths = resolveExtensionPaths(extensionsXml);
			for (File pathDir : paths.pathDirs)
			{
				scanSubDirs(pathDir, "classes", dirs);
			}
			for (File extDir : paths.extensionDirs)
			{
				File classesDir = new File(extDir, "classes");
				if (classesDir.isDirectory())
				{
					dirs.add(classesDir);
				}
			}
		}

		File bootstrapClasses = new File(platformHome, "bootstrap/classes");
		if (bootstrapClasses.isDirectory())
		{
			dirs.add(bootstrapClasses);
		}

		logger.info("Discovered {} classes directories from platform: {}", dirs.size(), platformHome);
		return dirs;
	}

	List<File> discoverSourceDirs()
	{
		List<File> dirs = new ArrayList<>();

		scanSubDirs(new File(platformHome, "ext"), "src", dirs);

		File extensionsXml = new File(platformHome, "extensions.xml");
		if (extensionsXml.exists())
		{
			ExtensionPaths paths = resolveExtensionPaths(extensionsXml);
			for (File pathDir : paths.pathDirs)
			{
				scanSubDirs(pathDir, "src", dirs);
			}
			for (File extDir : paths.extensionDirs)
			{
				File srcDir = new File(extDir, "src");
				if (srcDir.isDirectory())
				{
					dirs.add(srcDir);
				}
			}
		}

		logger.info("Discovered {} source directories from platform: {}", dirs.size(), platformHome);
		return dirs;
	}

	private void scanSubDirs(File parent, String childName, List<File> result)
	{
		if (!parent.isDirectory())
		{
			return;
		}
		File[] extensions = parent.listFiles(File::isDirectory);
		if (extensions == null)
		{
			return;
		}
		for (File ext : extensions)
		{
			File target = new File(ext, childName);
			if (target.isDirectory())
			{
				result.add(target);
			}
		}
	}

	private static class ExtensionPaths
	{
		final List<File> pathDirs = new ArrayList<>();
		final List<File> extensionDirs = new ArrayList<>();
	}

	private ExtensionPaths resolveExtensionPaths(File extensionsXml)
	{
		ExtensionPaths result = new ExtensionPaths();
		try
		{
			Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(extensionsXml);
			File binDir = platformHome.getParentFile();

			NodeList paths = doc.getElementsByTagName("path");
			for (int i = 0; i < paths.getLength(); i++)
			{
				Element pathEl = (Element) paths.item(i);
				String dir = pathEl.getAttribute("dir");
				if (dir == null || dir.isEmpty())
				{
					continue;
				}

				dir = dir.replace("${PLATFORM_BIN_DIR}", binDir.getAbsolutePath());
				File resolvedDir = new File(dir);
				if (resolvedDir.isDirectory())
				{
					result.pathDirs.add(resolvedDir);
				}
			}

			NodeList extensions = doc.getElementsByTagName("extension");
			for (int i = 0; i < extensions.getLength(); i++)
			{
				Element extEl = (Element) extensions.item(i);
				String dir = extEl.getAttribute("dir");
				if (dir == null || dir.isEmpty())
				{
					continue;
				}

				dir = dir.replace("${PLATFORM_BIN_DIR}", binDir.getAbsolutePath());
				File resolvedDir = new File(dir);
				if (resolvedDir.isDirectory())
				{
					result.extensionDirs.add(resolvedDir);
				}
			}
		}
		catch (Exception e)
		{
			logger.warn("Failed to parse extensions.xml: {}", e.getMessage());
		}
		return result;
	}
}
