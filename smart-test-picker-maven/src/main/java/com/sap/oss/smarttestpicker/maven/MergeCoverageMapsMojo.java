// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker.maven;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import io.github.ljubisap.smarttestpicker.mapper.ClassCoverageMetrics;
import io.github.ljubisap.smarttestpicker.mapper.CoverageMap;
import io.github.ljubisap.smarttestpicker.mapper.CoverageMapMetadata;


/**
 * Aggregator Mojo that merges per-module coverage maps into a single unified map.
 *
 * <p>Runs once on the root project after all modules have completed their
 * {@code verify} phase. Scans reactor modules for {@code target/test-coverage-map.json},
 * merges all test mappings into one map, and writes the result to the root
 * project's {@code target/test-coverage-map.json}.</p>
 *
 * <p>The merged map uses the metadata (commitId, baseBranch, timestamp) from the
 * first module that has one — all modules share the same commit context.</p>
 */
@Mojo(name = "merge-coverage-maps", defaultPhase = LifecyclePhase.VERIFY, aggregator = true)
public class MergeCoverageMapsMojo extends AbstractMojo
{

	@Parameter(defaultValue = "${project}", readonly = true, required = true)
	private MavenProject project;

	@Parameter(defaultValue = "${reactorProjects}", readonly = true, required = true)
	private List<MavenProject> reactorProjects;

	/** Output file for the merged coverage map. */
	@Parameter(defaultValue = "${project.build.directory}/test-coverage-map.json", required = true)
	private File outputFile;

	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

	@Override
	public void execute() throws MojoExecutionException
	{
		// Output to the execution root's target directory
		MavenProject root = findExecutionRoot();
		File rootOutputFile = root != null
				? new File(root.getBuild().getDirectory(), "test-coverage-map.json")
				: outputFile;

		CoverageMap merged = new CoverageMap();
		Map<String, ClassCoverageMetrics> mergedClassMetrics = new HashMap<>();
		CoverageMapMetadata metadata = null;
		int modulesFound = 0;
		int totalTests = 0;

		for (MavenProject module : reactorProjects)
		{
			// Skip POM-packaging modules (root, parent aggregators) — they don't have per-module maps
			if ("pom".equals(module.getPackaging()))
			{
				continue;
			}

			File moduleMap = new File(module.getBuild().getDirectory(), "test-coverage-map.json");
			if (!moduleMap.exists())
			{
				continue;
			}

			try (FileReader reader = new FileReader(moduleMap))
			{
				CoverageMap moduleData = gson.fromJson(reader, CoverageMap.class);
				if (moduleData == null || moduleData.getTestMappings() == null)
				{
					continue;
				}

				modulesFound++;

				if (metadata == null && moduleData.getMetadata() != null)
				{
					metadata = moduleData.getMetadata();
				}

				int moduleTests = moduleData.getTestMappings().size();
				totalTests += moduleTests;
				merged.getTestMappings().putAll(moduleData.getTestMappings());

				if (moduleData.getClassMetrics() != null)
				{
					moduleData.getClassMetrics().forEach((cls, metrics) ->
							mergedClassMetrics.merge(cls, metrics, ClassCoverageMetrics::merge));
				}

				getLog().info("[SmartTestPicker] Merged " + moduleTests + " tests from "
						+ module.getArtifactId());
			}
			catch (IOException e)
			{
				getLog().warn("[SmartTestPicker] Failed to read coverage map from "
						+ module.getArtifactId() + ": " + e.getMessage());
			}
		}

		if (modulesFound == 0)
		{
			getLog().warn("[SmartTestPicker] No per-module coverage maps found to merge");
			return;
		}

		merged.setMetadata(metadata);
		if (!mergedClassMetrics.isEmpty())
		{
			merged.setClassMetrics(mergedClassMetrics);
		}

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
			throw new MojoExecutionException("Failed to write merged coverage map", e);
		}

		getLog().info("[SmartTestPicker] Merged coverage map: " + rootOutputFile.getAbsolutePath()
				+ " (" + totalTests + " tests from " + modulesFound + " modules"
				+ ", commitId: " + (metadata != null ? metadata.getCommitId() : "unknown") + ")");
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
