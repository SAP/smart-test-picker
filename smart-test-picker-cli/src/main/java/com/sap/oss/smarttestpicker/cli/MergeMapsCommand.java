// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.cli;

import com.sap.oss.smarttestpicker.engine.FileUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.zip.GZIPOutputStream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import com.sap.oss.smarttestpicker.mapper.ClassCoverageMetrics;
import com.sap.oss.smarttestpicker.mapper.CoverageMap;
import com.sap.oss.smarttestpicker.mapper.CoverageMapMetadata;
import com.sap.oss.smarttestpicker.mapper.CoverageMapReader;
import com.sap.oss.smarttestpicker.mapper.IndexedCoverageMap;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;


@Command(
		name = "merge-maps",
		mixinStandardHelpOptions = true,
		description = "Merge multiple coverage map JSON files into a single unified map"
)
public class MergeMapsCommand implements Callable<Integer>
{

	@Option(names = "--input-dir", required = true,
			description = "Directory containing coverage map JSON files")
	private File inputDir;

	@Option(names = "--output", required = true,
			description = "Output merged coverage map file path")
	private File output;

	@Option(names = "--indexed",
			description = "Use indexed format (smaller file, integer references)")
	private boolean indexed;

	@Option(names = "--gzip",
			description = "Compress output with gzip")
	private boolean gzip;

	@Override
	public Integer call()
	{
		if (!inputDir.isDirectory())
		{
			System.err.println("Error: Input directory does not exist: " + inputDir);
			return 1;
		}

		ConsoleLogger logger = new ConsoleLogger();
		logger.info("Input directory:  {}", inputDir.getAbsolutePath());
		logger.info("Output file:      {}", output.getAbsolutePath());
		logger.info("Indexed:          {}", indexed);
		logger.info("Gzip:             {}", gzip);

		File[] mapFiles = inputDir.listFiles(
				f -> f.isFile() && (f.getName().endsWith(".json") || f.getName().endsWith(".json.gz")));

		if (mapFiles == null || mapFiles.length == 0)
		{
			System.err.println("Error: No .json or .json.gz files found in " + inputDir);
			return 1;
		}

		logger.info("Found {} coverage map files", mapFiles.length);

		try
		{
			CoverageMapMetadata mergedMetadata = null;
			Map<String, Map<String, List<String>>> mergedMappings = new HashMap<>();
			Map<String, ClassCoverageMetrics> mergedMetrics = new HashMap<>();

			for (File mapFile : mapFiles)
			{
				logger.info("Loading: {}", mapFile.getName());
				CoverageMap map = CoverageMapReader.load(mapFile);

				if (mergedMetadata == null && map.getMetadata() != null)
				{
					mergedMetadata = map.getMetadata();
				}

				mergedMappings.putAll(map.getTestMappings());

				if (map.getClassMetrics() != null)
				{
					for (Map.Entry<String, ClassCoverageMetrics> entry : map.getClassMetrics().entrySet())
					{
						mergedMetrics.merge(entry.getKey(), entry.getValue(), ClassCoverageMetrics::merge);
					}
				}
			}

			logger.info("Merged: {} tests, {} classes with metrics",
					mergedMappings.size(), mergedMetrics.size());

			if (indexed)
			{
				writeIndexed(mergedMetadata, mergedMappings, mergedMetrics);
			}
			else
			{
				writePlain(mergedMetadata, mergedMappings, mergedMetrics);
			}

			logger.info("Written: {}", output.getAbsolutePath());
			return 0;
		}
		catch (Exception e)
		{
			System.err.println("Error: " + e.getMessage());
			return 1;
		}
	}

	private void writePlain(CoverageMapMetadata metadata, Map<String, Map<String, List<String>>> testMappings,
			Map<String, ClassCoverageMetrics> classMetrics) throws Exception
	{
		CoverageMap map = new CoverageMap(metadata, testMappings);
		if (!classMetrics.isEmpty())
		{
			map.setClassMetrics(classMetrics);
		}

		Gson gson = new GsonBuilder().setPrettyPrinting().create();
		try (Writer writer = createWriter())
		{
			gson.toJson(map, writer);
		}
	}

	private void writeIndexed(CoverageMapMetadata metadata, Map<String, Map<String, List<String>>> testMappings,
			Map<String, ClassCoverageMetrics> classMetrics) throws Exception
	{
		TreeSet<String> allClasses = new TreeSet<>();
		TreeSet<String> allMethods = new TreeSet<>();

		for (Map<String, List<String>> coverage : testMappings.values())
		{
			List<String> classes = coverage.get("classes");
			if (classes != null)
			{
				allClasses.addAll(classes);
			}
			List<String> methods = coverage.get("methods");
			if (methods != null)
			{
				allMethods.addAll(methods);
			}
		}

		List<String> classIndex = new ArrayList<>(allClasses);
		List<String> methodIndex = new ArrayList<>(allMethods);

		Map<String, Integer> classLookup = new HashMap<>();
		for (int i = 0; i < classIndex.size(); i++)
		{
			classLookup.put(classIndex.get(i), i);
		}
		Map<String, Integer> methodLookup = new HashMap<>();
		for (int i = 0; i < methodIndex.size(); i++)
		{
			methodLookup.put(methodIndex.get(i), i);
		}

		Map<String, IndexedCoverageMap.TestCoverageRef> indexedMappings = new HashMap<>();
		for (Map.Entry<String, Map<String, List<String>>> entry : testMappings.entrySet())
		{
			Map<String, List<String>> coverage = entry.getValue();

			List<Integer> classRefs = new ArrayList<>();
			List<String> classes = coverage.get("classes");
			if (classes != null)
			{
				for (String cls : classes)
				{
					classRefs.add(classLookup.get(cls));
				}
			}

			List<Integer> methodRefs = new ArrayList<>();
			List<String> methods = coverage.get("methods");
			if (methods != null)
			{
				for (String method : methods)
				{
					methodRefs.add(methodLookup.get(method));
				}
			}

			indexedMappings.put(entry.getKey(), new IndexedCoverageMap.TestCoverageRef(classRefs, methodRefs));
		}

		IndexedCoverageMap indexedMap = new IndexedCoverageMap(metadata, classIndex, methodIndex, indexedMappings);
		if (!classMetrics.isEmpty())
		{
			indexedMap.setClassMetrics(classMetrics);
		}

		Gson gson = new Gson();
		try (Writer writer = createWriter())
		{
			gson.toJson(indexedMap, writer);
		}
	}

	private Writer createWriter() throws Exception
	{
		FileUtils.ensureParentDirExists(output);
		if (gzip || output.getName().endsWith(".gz"))
		{
			return new OutputStreamWriter(
					new GZIPOutputStream(new FileOutputStream(output)), StandardCharsets.UTF_8);
		}
		return new FileWriter(output);
	}
}
