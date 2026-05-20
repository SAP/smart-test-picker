// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker.report;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;


/**
 * Writes coverage map data as external JS chunk files for lazy loading in the HTML report.
 *
 * <p>Uses the JSONP pattern (dynamic script tag injection) instead of fetch(),
 * because fetch() does not work with the file:/// protocol due to CORS restrictions.</p>
 *
 * <p>Output structure:</p>
 * <ul>
 *   <li>{@code data/chunk-0.js}, {@code data/chunk-1.js}, ... — full class/method data per test</li>
 *   <li>{@code data/search-index.js} — compact search index with short names for all tests</li>
 * </ul>
 */
public class ChunkDataWriter
{

	public static final int CHUNK_SIZE = 50;
	private static final int MAX_SEARCH_FIELD_LENGTH = 500;
	private static final int MAX_ENTRIES_PER_LIST = 200;

	/**
	 * Writes chunk JS files and search index to the given data directory.
	 *
	 * @param dataDir              output directory (e.g., report/data/)
	 * @param testMappings         full coverage map test mappings
	 * @param selectedTests        set of selected test names
	 * @param sourceLinks          source link map (may be null)
	 * @param coveragePercentages  per-class coverage percentages (may be null)
	 * @return write result with chunk count and total tests
	 */
	public ChunkWriteResult writeChunks(
			File dataDir,
			Map<String, Map<String, List<String>>> testMappings,
			Set<String> selectedTests,
			Map<String, String> sourceLinks,
			Map<String, Double> coveragePercentages) throws IOException
	{
		dataDir.mkdirs();

		if (selectedTests == null) selectedTests = Set.of();
		if (sourceLinks == null) sourceLinks = Map.of();
		if (coveragePercentages == null) coveragePercentages = Map.of();

		List<String> allTests = new ArrayList<>(testMappings.keySet());
		Collections.sort(allTests);

		int chunkCount = (allTests.size() + CHUNK_SIZE - 1) / CHUNK_SIZE;
		List<SearchIndexEntry> searchIndex = new ArrayList<>();
		Gson gson = new GsonBuilder().create();

		for (int chunk = 0; chunk < chunkCount; chunk++)
		{
			int start = chunk * CHUNK_SIZE;
			int end = Math.min(start + CHUNK_SIZE, allTests.size());
			List<Map<String, Object>> chunkEntries = new ArrayList<>();

			for (int i = start; i < end; i++)
			{
				String test = allTests.get(i);
				Map<String, List<String>> coverage = testMappings.get(test);
				List<String> classes = coverage != null ? coverage.get("classes") : List.of();
				List<String> methods = coverage != null ? coverage.get("methods") : List.of();
				if (classes == null) classes = List.of();
				if (methods == null) methods = List.of();

				boolean selected = selectedTests.contains(test);

				Map<String, Object> entry = new HashMap<>();
				entry.put("t", test);
				entry.put("s", selected);
				entry.put("cc", classes.size());
				entry.put("mc", methods.size());
				entry.put("classes", classes.size() > MAX_ENTRIES_PER_LIST
						? classes.subList(0, MAX_ENTRIES_PER_LIST) : classes);
				entry.put("methods", methods.size() > MAX_ENTRIES_PER_LIST
						? methods.subList(0, MAX_ENTRIES_PER_LIST) : methods);

				Map<String, String> testSourceLinks = filterByClasses(sourceLinks, classes);
				if (!testSourceLinks.isEmpty())
				{
					entry.put("sl", testSourceLinks);
				}

				Map<String, Double> testCovPct = filterCoverageByClasses(coveragePercentages, classes);
				if (!testCovPct.isEmpty())
				{
					entry.put("cp", testCovPct);
				}

				chunkEntries.add(entry);

				searchIndex.add(new SearchIndexEntry(
						test, selected, classes.size(), methods.size(),
						truncate(shortNames(classes)), truncate(shortMethodNames(methods)), chunk));
			}

			writeChunkFile(dataDir, chunk, gson.toJson(chunkEntries));
		}

		writeSearchIndex(dataDir, gson.toJson(searchIndex));

		return new ChunkWriteResult(allTests.size(), chunkCount);
	}

	private void writeChunkFile(File dataDir, int index, String json) throws IOException
	{
		File file = new File(dataDir, "chunk-" + index + ".js");
		try (FileWriter writer = new FileWriter(file))
		{
			writer.write("window.__STP_CHUNKS=window.__STP_CHUNKS||{};window.__STP_CHUNKS[");
			writer.write(String.valueOf(index));
			writer.write("]=");
			writer.write(json);
			writer.write(";\n");
		}
	}

	private void writeSearchIndex(File dataDir, String json) throws IOException
	{
		File file = new File(dataDir, "search-index.js");
		try (FileWriter writer = new FileWriter(file))
		{
			writer.write("window.__STP_SEARCH_INDEX=");
			writer.write(json);
			writer.write(";\n");
		}
	}

	private Map<String, String> filterByClasses(Map<String, String> sourceLinks, List<String> classes)
	{
		Map<String, String> result = new HashMap<>();
		for (String cls : classes)
		{
			String link = sourceLinks.get(cls);
			if (link != null)
			{
				result.put(cls, link);
			}
		}
		return result;
	}

	private Map<String, Double> filterCoverageByClasses(Map<String, Double> coveragePercentages, List<String> classes)
	{
		Map<String, Double> result = new HashMap<>();
		for (String cls : classes)
		{
			Double pct = coveragePercentages.get(cls);
			if (pct != null)
			{
				result.put(cls, pct);
			}
		}
		return result;
	}

	private String shortNames(List<String> fqns)
	{
		StringBuilder sb = new StringBuilder();
		for (String fqn : fqns)
		{
			if (sb.length() > 0) sb.append(' ');
			int dot = fqn.lastIndexOf('.');
			sb.append(dot >= 0 ? fqn.substring(dot + 1) : fqn);
		}
		return sb.toString();
	}

	private String shortMethodNames(List<String> methods)
	{
		StringBuilder sb = new StringBuilder();
		for (String method : methods)
		{
			if (sb.length() > 0) sb.append(' ');
			int hash = method.indexOf('#');
			if (hash >= 0)
			{
				sb.append(method.substring(hash + 1));
			}
			else
			{
				sb.append(method);
			}
		}
		return sb.toString();
	}

	private String truncate(String value)
	{
		if (value.length() <= MAX_SEARCH_FIELD_LENGTH) return value;
		return value.substring(0, MAX_SEARCH_FIELD_LENGTH);
	}


	/**
	 * Search index entry — compact representation for full-dataset search.
	 * Serialized to JSON with Gson (field names are the JSON keys).
	 */
	@SuppressWarnings("unused")
	static class SearchIndexEntry
	{
		final String t;
		final boolean s;
		final int cc;
		final int mc;
		final String c;
		final String m;
		final int k;

		SearchIndexEntry(String test, boolean selected, int classCount, int methodCount,
				String classShortNames, String methodShortNames, int chunkIndex)
		{
			this.t = test;
			this.s = selected;
			this.cc = classCount;
			this.mc = methodCount;
			this.c = classShortNames;
			this.m = methodShortNames;
			this.k = chunkIndex;
		}
	}


	/**
	 * Result of writing chunk data files.
	 */
	public static class ChunkWriteResult
	{
		private final int totalTests;
		private final int chunkCount;

		ChunkWriteResult(int totalTests, int chunkCount)
		{
			this.totalTests = totalTests;
			this.chunkCount = chunkCount;
		}

		public int getTotalTests()
		{
			return totalTests;
		}

		public int getChunkCount()
		{
			return chunkCount;
		}
	}
}
