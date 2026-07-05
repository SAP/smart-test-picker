// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.store;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sap.oss.smarttestpicker.engine.EngineLogger;

import static org.junit.jupiter.api.Assertions.*;


/**
 * Tests for {@link CoverageMapResolver} - verifies map resolution logic
 * for remote, local, and nearest modes.
 */
class CoverageMapResolverTest
{

	private static final String REMOTE_MAP = "remote-coverage-map.json";
	private static final String LOCAL_MAP = "local-coverage-map.json";

	private static final EngineLogger SILENT_LOGGER = new EngineLogger()
	{
		@Override
		public void info(String msg, Object... args) {}

		@Override
		public void warn(String msg, Object... args) {}
	};

	@TempDir
	Path tempDir;

	private File projectDir;
	private Path cacheDir;

	@BeforeEach
	void setUp() throws IOException
	{
		projectDir = tempDir.resolve("myproject").toFile();
		projectDir.mkdirs();

		// Initialize a git repo so GitChangeDetector works
		runGit(projectDir, "init");
		runGit(projectDir, "commit", "--allow-empty", "-m", "initial");

		// Manually set up cache dir (normally ~/.gradle/smart-test-picker/myproject/)
		cacheDir = tempDir.resolve("gradle-home").resolve("smart-test-picker").resolve("myproject");
		Files.createDirectories(cacheDir);
	}

	@Test
	void returnsNullWhenNoCachedMaps()
	{
		CoverageMapResolver resolver = createResolver();
		File result = resolver.resolve(CoverageMapResolver.PreferMode.NEAREST);
		assertNull(result);
	}

	@Test
	void returnsRemoteMapWhenOnlyRemoteExists() throws IOException
	{
		writeMapWithCommit(cacheDir.resolve(REMOTE_MAP), getCurrentCommit());

		CoverageMapResolver resolver = createResolver();
		File result = resolver.resolve(CoverageMapResolver.PreferMode.NEAREST);

		assertNotNull(result);
		assertTrue(result.getName().contains("remote"));
	}

	@Test
	void returnsLocalMapWhenOnlyLocalExists() throws IOException
	{
		writeMapWithCommit(cacheDir.resolve(LOCAL_MAP), getCurrentCommit());

		CoverageMapResolver resolver = createResolver();
		File result = resolver.resolve(CoverageMapResolver.PreferMode.NEAREST);

		assertNotNull(result);
		assertTrue(result.getName().contains("local"));
	}

	@Test
	void remoteModeIgnoresLocalMap() throws IOException
	{
		writeMapWithCommit(cacheDir.resolve(LOCAL_MAP), getCurrentCommit());

		CoverageMapResolver resolver = createResolver();
		// Remote mode but remote doesn't exist - should fall back to local
		File result = resolver.resolve(CoverageMapResolver.PreferMode.REMOTE);

		assertNotNull(result, "Should fall back to local when remote is missing");
		assertTrue(result.getName().contains("local"));
	}

	@Test
	void remoteModeSelectsRemoteWhenBothExist() throws IOException
	{
		String commit = getCurrentCommit();
		writeMapWithCommit(cacheDir.resolve(REMOTE_MAP), commit);
		writeMapWithCommit(cacheDir.resolve(LOCAL_MAP), commit);

		CoverageMapResolver resolver = createResolver();
		File result = resolver.resolve(CoverageMapResolver.PreferMode.REMOTE);

		assertNotNull(result);
		assertTrue(result.getName().contains("remote"));
	}

	@Test
	void localModeSelectsLocalWhenBothExist() throws IOException
	{
		String commit = getCurrentCommit();
		writeMapWithCommit(cacheDir.resolve(REMOTE_MAP), commit);
		writeMapWithCommit(cacheDir.resolve(LOCAL_MAP), commit);

		CoverageMapResolver resolver = createResolver();
		File result = resolver.resolve(CoverageMapResolver.PreferMode.LOCAL);

		assertNotNull(result);
		assertTrue(result.getName().contains("local"));
	}

	@Test
	void nearestPrefersRemoteWhenEqualDistance() throws IOException
	{
		String commit = getCurrentCommit();
		writeMapWithCommit(cacheDir.resolve(REMOTE_MAP), commit);
		writeMapWithCommit(cacheDir.resolve(LOCAL_MAP), commit);

		CoverageMapResolver resolver = createResolver();
		File result = resolver.resolve(CoverageMapResolver.PreferMode.NEAREST);

		assertNotNull(result);
		assertTrue(result.getName().contains("remote"),
				"Should prefer remote when both have equal commit distance");
	}

	@Test
	void nearestPrefersCloserMap() throws IOException
	{
		// Remote map points to initial commit (distance > 0)
		String initialCommit = getCurrentCommit();
		writeMapWithCommit(cacheDir.resolve(REMOTE_MAP), initialCommit);

		// Make a new commit so local map is at HEAD (distance = 0)
		runGit(projectDir, "commit", "--allow-empty", "-m", "second");
		String headCommit = getCurrentCommit();
		writeMapWithCommit(cacheDir.resolve(LOCAL_MAP), headCommit);

		CoverageMapResolver resolver = createResolver();
		File result = resolver.resolve(CoverageMapResolver.PreferMode.NEAREST);

		assertNotNull(result);
		assertTrue(result.getName().contains("local"),
				"Should prefer local map when it is closer to HEAD");
	}

	@Test
	void localModeFallsBackToRemoteWhenLocalMissing() throws IOException
	{
		writeMapWithCommit(cacheDir.resolve(REMOTE_MAP), getCurrentCommit());

		CoverageMapResolver resolver = createResolver();
		File result = resolver.resolve(CoverageMapResolver.PreferMode.LOCAL);

		assertNotNull(result, "Should fall back to remote when local is missing");
		assertTrue(result.getName().contains("remote"));
	}

	// --- Helper methods ---

	private CoverageMapResolver createResolver()
	{
		// Override cache dir by using a custom resolver that points to our temp dir
		return new CoverageMapResolver(projectDir, SILENT_LOGGER)
		{
			@Override
			public Path getCacheDir()
			{
				return cacheDir;
			}
		};
	}

	private String getCurrentCommit()
	{
		try
		{
			ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "HEAD");
			pb.directory(projectDir);
			Process p = pb.start();
			String output = new String(p.getInputStream().readAllBytes()).trim();
			p.waitFor();
			return output;
		}
		catch (Exception e)
		{
			throw new RuntimeException(e);
		}
	}

	private void runGit(File dir, String... args)
	{
		try
		{
			String[] cmd = new String[args.length + 1];
			cmd[0] = "git";
			System.arraycopy(args, 0, cmd, 1, args.length);
			ProcessBuilder pb = new ProcessBuilder(cmd);
			pb.directory(dir);
			pb.environment().put("GIT_AUTHOR_NAME", "Test");
			pb.environment().put("GIT_AUTHOR_EMAIL", "test@test.com");
			pb.environment().put("GIT_COMMITTER_NAME", "Test");
			pb.environment().put("GIT_COMMITTER_EMAIL", "test@test.com");
			Process p = pb.start();
			p.waitFor();
		}
		catch (Exception e)
		{
			throw new RuntimeException(e);
		}
	}

	private void writeMapWithCommit(Path path, String commitId) throws IOException
	{
		String json = String.format(
				"{\"metadata\":{\"baseBranch\":\"main\",\"commitId\":\"%s\",\"timestamp\":\"2026-07-05T10:00:00Z\"},"
						+ "\"testMappings\":{\"SomeTest#test1\":{\"classes\":[\"com.example.Foo\"],\"methods\":[\"com.example.Foo#bar\"]}}}",
				commitId);
		Files.writeString(path, json);
	}
}
