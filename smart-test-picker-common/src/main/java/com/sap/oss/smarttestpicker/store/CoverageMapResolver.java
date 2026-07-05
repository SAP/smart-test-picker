// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.store;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sap.oss.smarttestpicker.change.GitChangeDetector;
import com.sap.oss.smarttestpicker.engine.EngineLogger;


/**
 * Resolves which cached coverage map to use for test selection.
 *
 * <p>Manages a local file-system cache under {@code ~/.gradle/smart-test-picker/PROJECT_NAME/}
 * with two map slots:</p>
 * <ul>
 *   <li>{@code remote-coverage-map.json} - pulled from CI via pull-map command</li>
 *   <li>{@code local-coverage-map.json} - generated locally via generate-map command</li>
 * </ul>
 *
 * <p>Three selection modes determine which map is used:</p>
 * <ul>
 *   <li>{@code nearest} (default) - picks the map whose commitId is closest to HEAD</li>
 *   <li>{@code remote} - always picks the remote map</li>
 *   <li>{@code local} - always picks the local map</li>
 * </ul>
 *
 * <p>If the preferred map does not exist, falls back to the other one.
 * All decisions are logged for full transparency.</p>
 */
public class CoverageMapResolver
{

	private static final String CACHE_DIR_NAME = "smart-test-picker";
	private static final String REMOTE_MAP_NAME = "remote-coverage-map.json";
	private static final String LOCAL_MAP_NAME = "local-coverage-map.json";

	/**
	 * Selection mode for choosing between remote and local coverage maps.
	 */
	public enum PreferMode
	{
		/** Pick the map whose commitId is closest to HEAD. If equal, prefer remote. */
		NEAREST,
		/** Always prefer the remote map. Fall back to local if remote is missing. */
		REMOTE,
		/** Always prefer the local map. Fall back to remote if local is missing. */
		LOCAL
	}

	private final File projectDir;
	private final EngineLogger logger;

	public CoverageMapResolver(File projectDir, EngineLogger logger)
	{
		this.projectDir = projectDir;
		this.logger = logger;
	}

	/**
	 * Resolves the coverage map file to use based on the cache and preference mode.
	 *
	 * @param mode the selection mode (nearest, remote, local)
	 * @return the resolved map file, or null if no map is available
	 */
	public File resolve(PreferMode mode)
	{
		Path cacheDir = getCacheDir();
		File remoteMap = cacheDir.resolve(REMOTE_MAP_NAME).toFile();
		File localMap = cacheDir.resolve(LOCAL_MAP_NAME).toFile();

		boolean remoteExists = remoteMap.exists();
		boolean localExists = localMap.exists();

		logger.info("[SmartTestPicker] Resolving coverage map (mode: {})", mode.name().toLowerCase());
		logger.info("[SmartTestPicker]   Cache directory: {}", cacheDir);

		if (!remoteExists && !localExists)
		{
			logger.warn("[SmartTestPicker]   No cached maps found. Run 'pull-map' to download from CI or 'generate-map --cache' to create locally.");
			return null;
		}

		if (remoteExists)
		{
			logMapInfo("Remote map", remoteMap);
		}
		else
		{
			logger.info("[SmartTestPicker]   Remote map: not present");
		}

		if (localExists)
		{
			logMapInfo("Local map", localMap);
		}
		else
		{
			logger.info("[SmartTestPicker]   Local map: not present");
		}

		switch (mode)
		{
			case REMOTE:
				return resolveWithPreference(remoteMap, localMap, remoteExists, localExists, "remote", "local");
			case LOCAL:
				return resolveWithPreference(localMap, remoteMap, localExists, remoteExists, "local", "remote");
			case NEAREST:
			default:
				return resolveNearest(remoteMap, localMap, remoteExists, localExists);
		}
	}

	/**
	 * Returns the path where pull-map should write the remote coverage map.
	 */
	public File getRemoteMapPath()
	{
		Path cacheDir = getCacheDir();
		cacheDir.toFile().mkdirs();
		return cacheDir.resolve(REMOTE_MAP_NAME).toFile();
	}

	/**
	 * Returns the path where generate-map should write the local coverage map.
	 */
	public File getLocalMapPath()
	{
		Path cacheDir = getCacheDir();
		cacheDir.toFile().mkdirs();
		return cacheDir.resolve(LOCAL_MAP_NAME).toFile();
	}

	/**
	 * Returns the cache directory for the current project.
	 */
	public Path getCacheDir()
	{
		String projectName = projectDir.getName();
		String gradleHome = System.getProperty("user.home") + "/.gradle";
		return Path.of(gradleHome, CACHE_DIR_NAME, projectName);
	}

	private File resolveWithPreference(File preferred, File fallback,
			boolean preferredExists, boolean fallbackExists, String preferredName, String fallbackName)
	{
		if (preferredExists)
		{
			logger.info("[SmartTestPicker]   Selected: {} map (mode: {})", preferredName, preferredName);
			return preferred;
		}
		if (fallbackExists)
		{
			logger.info("[SmartTestPicker]   {} map not available, falling back to {} map", preferredName, fallbackName);
			return fallback;
		}
		return null;
	}

	private File resolveNearest(File remoteMap, File localMap,
			boolean remoteExists, boolean localExists)
	{
		if (remoteExists && !localExists)
		{
			logger.info("[SmartTestPicker]   Selected: remote map (only map available)");
			return remoteMap;
		}
		if (localExists && !remoteExists)
		{
			logger.info("[SmartTestPicker]   Selected: local map (only map available)");
			return localMap;
		}

		// Both exist - compare commit distance
		GitChangeDetector git = new GitChangeDetector(projectDir);

		int remoteDistance = getCommitDistance(git, remoteMap);
		int localDistance = getCommitDistance(git, localMap);

		logger.info("[SmartTestPicker]   Comparing distances: remote={}, local={}", remoteDistance, localDistance);

		if (localDistance < remoteDistance)
		{
			logger.info("[SmartTestPicker]   Selected: local map (distance {} < {})", localDistance, remoteDistance);
			return localMap;
		}
		else if (remoteDistance < localDistance)
		{
			logger.info("[SmartTestPicker]   Selected: remote map (distance {} < {})", remoteDistance, localDistance);
			return remoteMap;
		}
		else
		{
			// Equal distance - prefer remote (more complete, CI-generated)
			logger.info("[SmartTestPicker]   Selected: remote map (equal distance {}, preferring remote)", remoteDistance);
			return remoteMap;
		}
	}

	/**
	 * Reads the commitId from a coverage map file's metadata and computes
	 * the commit distance from HEAD.
	 *
	 * @return commit distance, or Integer.MAX_VALUE if commitId cannot be determined
	 */
	private int getCommitDistance(GitChangeDetector git, File mapFile)
	{
		String commitId = readCommitId(mapFile);
		if (commitId == null || commitId.isEmpty())
		{
			logger.warn("[SmartTestPicker]   Cannot read commitId from {}", mapFile.getName());
			return Integer.MAX_VALUE;
		}
		if (!git.isValidCommit(commitId))
		{
			logger.warn("[SmartTestPicker]   commitId {} in {} is not a valid commit (rebase/force push?)",
					commitId, mapFile.getName());
			return Integer.MAX_VALUE;
		}
		return git.getCommitDistance(commitId);
	}

	/**
	 * Reads the commitId from a coverage map file without fully parsing it.
	 * Parses only the metadata section for performance.
	 */
	private String readCommitId(File mapFile)
	{
		try
		{
			String content = Files.readString(mapFile.toPath());
			JsonObject root = JsonParser.parseString(content).getAsJsonObject();
			if (root.has("metadata"))
			{
				JsonObject metadata = root.getAsJsonObject("metadata");
				if (metadata.has("commitId"))
				{
					return metadata.get("commitId").getAsString();
				}
			}
		}
		catch (IOException | RuntimeException e)
		{
			logger.warn("[SmartTestPicker]   Failed to read metadata from {}: {}", mapFile.getName(), e.getMessage());
		}
		return null;
	}

	private void logMapInfo(String label, File mapFile)
	{
		String commitId = readCommitId(mapFile);
		long sizeKb = mapFile.length() / 1024;
		logger.info("[SmartTestPicker]   {}: {} (commitId={}, {}KB)",
				label, mapFile.getAbsolutePath(),
				commitId != null ? commitId.substring(0, Math.min(7, commitId.length())) : "unknown",
				sizeKb);
	}
}
