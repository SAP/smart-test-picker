// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;


/**
 * Aggregator Mojo that merges per-module test metrics into a single unified JSON.
 *
 * <p>Runs once on the last reactor module after all modules have completed their
 * {@code verify} phase. Collects {@code target/smart-test-metrics.json} from each
 * module, adds run metadata (commitId, branch, environment), and writes the merged
 * result to the root project's {@code target/smart-test-metrics.json}.</p>
 */
@Mojo(name = "merge-test-metrics", defaultPhase = LifecyclePhase.VERIFY, aggregator = true)
public class MergeTestMetricsMojo extends AbstractMojo
{

	@Parameter(defaultValue = "${project}", readonly = true, required = true)
	private MavenProject project;

	@Parameter(defaultValue = "${reactorProjects}", readonly = true, required = true)
	private List<MavenProject> reactorProjects;

	@Parameter(defaultValue = "main", property = "smartTestPicker.baseBranch")
	private String baseBranch;

	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

	@Override
	public void execute() throws MojoExecutionException
	{
		if (!isLastModule())
		{
			return;
		}

		MavenProject root = findExecutionRoot();
		File rootOutputFile = new File(root.getBuild().getDirectory(), "smart-test-metrics.json");

		List<Map<String, Object>> allTests = new ArrayList<>();
		int modulesFound = 0;

		for (MavenProject module : reactorProjects)
		{
			if ("pom".equals(module.getPackaging()))
			{
				continue;
			}

			File metricsFile = new File(module.getBuild().getDirectory(), "smart-test-metrics.json");
			if (!metricsFile.exists())
			{
				continue;
			}

			try (FileReader reader = new FileReader(metricsFile))
			{
				Map<String, Object> moduleData = gson.fromJson(reader,
						new TypeToken<Map<String, Object>>(){}.getType());
				if (moduleData == null || !moduleData.containsKey("tests"))
				{
					continue;
				}

				modulesFound++;
				@SuppressWarnings("unchecked")
				List<Map<String, Object>> tests = (List<Map<String, Object>>) moduleData.get("tests");

				for (Map<String, Object> test : tests)
				{
					test.put("module", module.getArtifactId());
				}

				allTests.addAll(tests);
				getLog().info("[SmartTestPicker] Collected " + tests.size() + " test metrics from "
						+ module.getArtifactId());
			}
			catch (IOException e)
			{
				getLog().warn("[SmartTestPicker] Failed to read test metrics from "
						+ module.getArtifactId() + ": " + e.getMessage());
			}
		}

		if (modulesFound == 0)
		{
			getLog().warn("[SmartTestPicker] No per-module test metrics found to merge");
			return;
		}

		// Build summary
		int passed = 0, failed = 0, skipped = 0;
		long totalDurationMs = 0;
		for (Map<String, Object> test : allTests)
		{
			String status = (String) test.get("status");
			if ("PASSED".equals(status))
			{
				passed++;
			}
			else if ("FAILED".equals(status))
			{
				failed++;
			}
			else
			{
				skipped++;
			}
			Object dur = test.get("durationMs");
			if (dur instanceof Number)
			{
				totalDurationMs += ((Number) dur).longValue();
			}
		}

		// Build merged output
		Map<String, Object> merged = new java.util.LinkedHashMap<>();
		merged.put("runId", UUID.randomUUID().toString());
		merged.put("commitId", getHeadCommitId(root.getBasedir()));
		merged.put("branch", getCurrentBranch(root.getBasedir()));
		merged.put("context", detectContext());
		merged.put("timestamp", Instant.now().toString());

		Map<String, Object> environment = new java.util.LinkedHashMap<>();
		environment.put("os", System.getProperty("os.name"));
		environment.put("java", System.getProperty("java.version"));
		environment.put("ci", System.getenv("CI") != null || System.getenv("JENKINS_URL") != null);
		merged.put("environment", environment);

		Map<String, Object> summary = new java.util.LinkedHashMap<>();
		summary.put("total", allTests.size());
		summary.put("passed", passed);
		summary.put("failed", failed);
		summary.put("skipped", skipped);
		summary.put("durationMs", totalDurationMs);
		merged.put("summary", summary);

		merged.put("tests", allTests);

		// Write
		File outputDir = rootOutputFile.getParentFile();
		if (!outputDir.exists() && !outputDir.mkdirs())
		{
			throw new MojoExecutionException("Failed to create output directory: " + outputDir);
		}

		try (FileWriter writer = new FileWriter(rootOutputFile))
		{
			gson.toJson(merged, writer);
		}
		catch (IOException e)
		{
			throw new MojoExecutionException("Failed to write merged test metrics", e);
		}

		getLog().info("[SmartTestPicker] Merged test metrics: " + rootOutputFile.getAbsolutePath()
				+ " (" + allTests.size() + " tests from " + modulesFound + " modules"
				+ " — " + passed + " passed, " + failed + " failed, " + skipped + " skipped)");
	}

	private String getHeadCommitId(File projectDir)
	{
		try
		{
			return runGitCommand(projectDir, "git", "rev-parse", "HEAD").trim();
		}
		catch (Exception e)
		{
			getLog().warn("[SmartTestPicker] Failed to get HEAD commit: " + e.getMessage());
			return "unknown";
		}
	}

	private String getCurrentBranch(File projectDir)
	{
		try
		{
			return runGitCommand(projectDir, "git", "rev-parse", "--abbrev-ref", "HEAD").trim();
		}
		catch (Exception e)
		{
			getLog().warn("[SmartTestPicker] Failed to get current branch: " + e.getMessage());
			return "unknown";
		}
	}

	/**
	 * Detects execution context via CI environment variables.
	 *
	 * @return {@code "ci-pr"} if running in a CI pull-request build (CHANGE_ID or GITHUB_PR_NUMBER set),
	 *         {@code "ci-main"} if running in CI but not a PR build,
	 *         {@code "local"} if no CI environment detected
	 */
	private String detectContext()
	{
		if (System.getenv("CI") != null || System.getenv("JENKINS_URL") != null)
		{
			String prNumber = System.getenv("CHANGE_ID");  // Jenkins PR
			if (prNumber == null)
			{
				prNumber = System.getenv("GITHUB_PR_NUMBER");
			}
			return prNumber != null ? "ci-pr" : "ci-main";
		}
		return "local";
	}

	private String runGitCommand(File workDir, String... command) throws IOException
	{
		ProcessBuilder pb = new ProcessBuilder(command);
		pb.directory(workDir);
		pb.redirectErrorStream(true);
		Process process = pb.start();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream())))
		{
			return reader.lines().collect(java.util.stream.Collectors.joining("\n"));
		}
	}

	private boolean isLastModule()
	{
		if (reactorProjects == null || reactorProjects.isEmpty())
		{
			return true;
		}
		MavenProject last = reactorProjects.get(reactorProjects.size() - 1);
		return project.equals(last);
	}

	/** Returns the reactor root project, falling back to the current project if none is marked as execution root. */
	private MavenProject findExecutionRoot()
	{
		for (MavenProject p : reactorProjects)
		{
			if (p.isExecutionRoot())
			{
				return p;
			}
		}
		return project;
	}
}
