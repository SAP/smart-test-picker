// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker.mapper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;


/**
 * Centralized reader for coverage map files, supporting multiple formats.
 *
 * <p>Handles three formats transparently:</p>
 * <ul>
 *   <li><b>Plain JSON</b> -- standard {@link CoverageMap} with string-based test mappings</li>
 *   <li><b>Indexed JSON</b> -- detected by the presence of a {@code classIndex} field;
 *       integer references are resolved back to FQN strings</li>
 *   <li><b>Gzip</b> -- detected by {@code .gz} file extension; decompressed before parsing</li>
 * </ul>
 *
 * <p>All consumers of the coverage map (TestSelector, TestSelectionEngine, ReportEngine,
 * SmartTestPickerPlugin) should use this class instead of direct Gson deserialization.</p>
 *
 * @see CoverageMap
 * @see IndexedCoverageMap
 */
public class CoverageMapReader
{

	/**
	 * Loads a coverage map from a file, auto-detecting the format.
	 *
	 * @param file the coverage map file (plain JSON, indexed JSON, or gzip)
	 * @return the parsed {@link CoverageMap}, always in the standard string-based format
	 * @throws IOException if the file cannot be read or parsed
	 */
	public static CoverageMap load(File file) throws IOException
	{
		try (Reader reader = openReader(file))
		{
			JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();

			if (root.has("classIndex"))
			{
				CoverageMap map = resolveIndexed(root);
				TestClassFilter.filterAll(map.getTestMappings());
				return map;
			}

			CoverageMap map = new Gson().fromJson(root, CoverageMap.class);
			TestClassFilter.filterAll(map.getTestMappings());
			return map;
		}
	}

	private static Reader openReader(File file)throws IOException
	{
		if (file.getName().endsWith(".gz"))
		{
			return new BufferedReader(new InputStreamReader(
					new GZIPInputStream(new FileInputStream(file)), StandardCharsets.UTF_8));
		}
		return new BufferedReader(new FileReader(file));
	}

	/**
	 * Converts an indexed coverage map back to the standard string-based format.
	 * Resolves integer references in each test's classes/methods arrays into FQN
	 * strings using the shared classIndex and methodIndex arrays.
	 */
	private static CoverageMap resolveIndexed(JsonObject root)
	{
		Gson gson = new Gson();

		CoverageMapMetadata metadata = gson.fromJson(root.get("metadata"), CoverageMapMetadata.class);

		List<String> classIndex = new ArrayList<>();
		for (JsonElement el : root.getAsJsonArray("classIndex"))
		{
			classIndex.add(el.getAsString());
		}

		List<String> methodIndex = new ArrayList<>();
		for (JsonElement el : root.getAsJsonArray("methodIndex"))
		{
			methodIndex.add(el.getAsString());
		}

		Map<String, Map<String, List<String>>> testMappings = new HashMap<>();
		JsonObject mappings = root.getAsJsonObject("testMappings");

		for (String testName : mappings.keySet())
		{
			JsonObject ref = mappings.getAsJsonObject(testName);

			List<String> classes = new ArrayList<>();
			if (ref.has("classes"))
			{
				for (JsonElement idx : ref.getAsJsonArray("classes"))
				{
					classes.add(classIndex.get(idx.getAsInt()));
				}
			}

			List<String> methods = new ArrayList<>();
			if (ref.has("methods"))
			{
				for (JsonElement idx : ref.getAsJsonArray("methods"))
				{
					methods.add(methodIndex.get(idx.getAsInt()));
				}
			}

			Map<String, List<String>> coverage = new HashMap<>();
			coverage.put("classes", classes);
			coverage.put("methods", methods);
			testMappings.put(testName, coverage);
		}

		CoverageMap map = new CoverageMap(metadata, testMappings);

		if (root.has("classMetrics"))
		{
			Map<String, ClassCoverageMetrics> metrics = gson.fromJson(
					root.get("classMetrics"),
					new TypeToken<Map<String, ClassCoverageMetrics>>(){}.getType());
			map.setClassMetrics(metrics);
		}

		return map;
	}
}
