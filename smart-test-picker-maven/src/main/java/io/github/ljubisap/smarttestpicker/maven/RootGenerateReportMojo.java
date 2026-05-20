// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker.maven;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import io.github.ljubisap.smarttestpicker.engine.ReportEngine;
import io.github.ljubisap.smarttestpicker.mapper.ClassCoverageMetrics;
import io.github.ljubisap.smarttestpicker.mapper.CoverageMap;
import io.github.ljubisap.smarttestpicker.mapper.CoverageMapMetadata;


/**
 * Aggregator Mojo that merges per-module coverage maps and generates the HTML report.
 *
 * <p>Combines the work of {@code merge-coverage-maps} and the old {@code root-generate-report}
 * into a single aggregator invocation. Scans reactor modules for per-module coverage maps,
 * merges them, then generates the unified HTML dashboard.</p>
 */
@Mojo(name = "root-generate-report", aggregator = true)
public class RootGenerateReportMojo extends AbstractMojo
{

	@Parameter(defaultValue = "${project}", readonly = true, required = true)
	private MavenProject project;

	@Parameter(defaultValue = "${reactorProjects}", readonly = true, required = true)
	private List<MavenProject> reactorProjects;

	@Parameter(defaultValue = "500", property = "smartTestPicker.maxCommitDistance")
	private int maxCommitDistance;

	@Parameter(defaultValue = "false", property = "smartTestPicker.classLevelSelection")
	private boolean classLevelSelection;

	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

	@Override
	public void execute() throws MojoExecutionException
	{
		MavenProject root = findExecutionRoot();
		File rootTarget = new File(root.getBuild().getDirectory());

		// Step 1: Merge per-module coverage maps
		File coverageMapFile = mergeCoverageMaps(rootTarget);
		if (coverageMapFile == null)
		{
			return;
		}

		// Step 2: Check for selected-tests.json
		File selectedTestsFile = new File(rootTarget, "selected-tests.json");
		if (!selectedTestsFile.exists())
		{
			getLog().warn("[SmartTestPicker] Selected tests not found: " + selectedTestsFile.getAbsolutePath()
					+ " — run root-select-tests first");
			return;
		}

		// Step 3: Collect dirs from reactor modules
		File reportFile = new File(rootTarget, "reports/smart-test-picker/index.html");
		List<File> xmlDirs = new ArrayList<>();
		List<File> execDirs = new ArrayList<>();
		List<File> classesDirs = new ArrayList<>();
		List<File> sourceDirs = new ArrayList<>();
		for (MavenProject module : reactorProjects)
		{
			if ("pom".equals(module.getPackaging()))
			{
				continue;
			}
			File xmlDir = new File(module.getBuild().getDirectory(), "jacoco-xml");
			if (xmlDir.exists())
			{
				xmlDirs.add(xmlDir);
			}
			File execDir = new File(module.getBuild().getDirectory(), "jacoco");
			if (execDir.exists())
			{
				execDirs.add(execDir);
			}
			File classesDir = new File(module.getBuild().getOutputDirectory());
			if (classesDir.exists())
			{
				classesDirs.add(classesDir);
			}
			File srcDir = new File(module.getBasedir(), "src/main/java");
			if (srcDir.exists())
			{
				sourceDirs.add(srcDir);
			}
		}

		getLog().info("[SmartTestPicker] Found " + xmlDirs.size() + " XML dirs, "
				+ execDirs.size() + " exec dirs, "
				+ classesDirs.size() + " classes dirs, "
				+ sourceDirs.size() + " source dirs");

		// Step 4: Generate report
		try
		{
			new ReportEngine().generate(
					coverageMapFile,
					selectedTestsFile,
					reportFile,
					xmlDirs,
					sourceDirs,
					execDirs,
					classesDirs,
					root.getBasedir(),
					maxCommitDistance,
					classLevelSelection,
					new MavenEngineLogger(getLog()));
		}
		catch (IOException e)
		{
			throw new MojoExecutionException("Failed to generate report", e);
		}

		getLog().info("[SmartTestPicker] Report generated: " + reportFile.getAbsolutePath());
	}

	private File mergeCoverageMaps(File rootTarget) throws MojoExecutionException
	{
		CoverageMap merged = new CoverageMap();
		Map<String, ClassCoverageMetrics> mergedClassMetrics = new HashMap<>();
		CoverageMapMetadata metadata = null;
		int modulesFound = 0;
		int totalTests = 0;

		for (MavenProject module : reactorProjects)
		{
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
			return null;
		}

		merged.setMetadata(metadata);
		if (!mergedClassMetrics.isEmpty())
		{
			merged.setClassMetrics(mergedClassMetrics);
		}

		File outputFile = new File(rootTarget, "test-coverage-map.json");
		outputFile.getParentFile().mkdirs();
		try (FileWriter writer = new FileWriter(outputFile))
		{
			gson.toJson(merged, writer);
		}
		catch (IOException e)
		{
			throw new MojoExecutionException("Failed to write merged coverage map", e);
		}

		getLog().info("[SmartTestPicker] Merged coverage map: " + outputFile.getAbsolutePath()
				+ " (" + totalTests + " tests from " + modulesFound + " modules)");
		return outputFile;
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
