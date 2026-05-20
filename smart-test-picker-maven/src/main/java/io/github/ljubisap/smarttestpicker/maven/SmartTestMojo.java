// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker.maven;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.InvocationResult;
import org.apache.maven.shared.invoker.Invoker;
import org.apache.maven.shared.invoker.MavenInvocationException;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import io.github.ljubisap.smarttestpicker.engine.ReportEngine;
import io.github.ljubisap.smarttestpicker.engine.TestSelectionEngine;
import io.github.ljubisap.smarttestpicker.mapper.ClassCoverageMetrics;
import io.github.ljubisap.smarttestpicker.mapper.CoverageMap;
import io.github.ljubisap.smarttestpicker.mapper.CoverageMapMetadata;
import io.github.ljubisap.smarttestpicker.selector.SelectionOutput;


/**
 * All-in-one aggregator Mojo for smart test selection on Maven multi-module projects.
 *
 * <p>Performs the full smart-test workflow in a single invocation:</p>
 * <ol>
 *   <li>Select tests from the existing (baseline) coverage map</li>
 *   <li>Determine which reactor modules contain selected/unmapped tests</li>
 *   <li>Fork {@code mvn verify} only on affected modules (via Maven Invoker)</li>
 *   <li>Merge per-module coverage maps</li>
 *   <li>Generate HTML report</li>
 * </ol>
 *
 * <p>Usage: {@code mvn smart-test-picker:smart-test -Psmart-test}</p>
 */
@Mojo(name = "smart-test", aggregator = true, requiresProject = true)
public class SmartTestMojo extends AbstractMojo
{

	@Parameter(defaultValue = "${project}", readonly = true, required = true)
	private MavenProject project;

	@Parameter(defaultValue = "${reactorProjects}", readonly = true, required = true)
	private List<MavenProject> reactorProjects;

	@Parameter(defaultValue = "500", property = "smartTestPicker.maxCommitDistance")
	private int maxCommitDistance;

	@Parameter(property = "smartTestPicker.fullSuiteTriggers")
	private List<String> fullSuiteTriggers;

	@Parameter(defaultValue = "false", property = "smartTestPicker.classLevelSelection")
	private boolean classLevelSelection;

	@Parameter(property = "spring.profiles.active")
	private String springProfiles;

	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

	@Override
	public void execute() throws MojoExecutionException
	{
		MavenProject root = findExecutionRoot();
		File rootTarget = new File(root.getBuild().getDirectory());
		MavenEngineLogger logger = new MavenEngineLogger(getLog());

		// Step 1: Select tests from baseline coverage map
		File coverageMapFile = new File(rootTarget, "test-coverage-map.json");
		if (!coverageMapFile.exists())
		{
			getLog().error("[SmartTestPicker] No baseline coverage map found at: "
					+ coverageMapFile.getAbsolutePath());
			getLog().error("[SmartTestPicker] Run baseline first: mvn verify -Psmart-test-picker");
			return;
		}

		List<File> testClassesDirs = new ArrayList<>();
		List<File> testSourceDirs = new ArrayList<>();
		for (MavenProject module : reactorProjects)
		{
			if ("pom".equals(module.getPackaging()))
			{
				continue;
			}
			File dir = new File(module.getBuild().getDirectory(), "test-classes");
			if (dir.exists())
			{
				testClassesDirs.add(dir);
			}
			for (String srcRoot : module.getTestCompileSourceRoots())
			{
				File srcDir = new File(srcRoot);
				if (srcDir.exists())
				{
					testSourceDirs.add(srcDir);
				}
			}
		}

		TestSelectionEngine engine = new TestSelectionEngine();
		SelectionOutput output = engine.select(
				coverageMapFile,
				testClassesDirs,
				testSourceDirs,
				root.getBasedir(),
				maxCommitDistance,
				fullSuiteTriggers != null ? fullSuiteTriggers : List.of(),
				logger);

		getLog().info("[SmartTestPicker] Status: " + output.getStatus() + " — " + output.getReason());

		// Write selected-tests.json to root
		File selectedTestsFile = new File(rootTarget, "selected-tests.json");
		writeJson(selectedTestsFile, output);

		// Step 2: Determine affected modules
		Set<String> affectedModules = determineAffectedModules(output);

		if (affectedModules.isEmpty() && "NONE".equals(output.getStatus()))
		{
			getLog().info("[SmartTestPicker] No tests to run — skipping build");
			mergeCoverageMapsAndReport(root, rootTarget, selectedTestsFile, logger);
			return;
		}

		// Step 3: Write includes files and fork Maven verify on affected modules
		writeIncludesFiles(output);
		cleanCoverageArtifactsForAffectedTests(output, affectedModules);

		String moduleList = String.join(",", affectedModules);
		getLog().info("[SmartTestPicker] Affected modules: " + moduleList);

		forkMavenVerify(root.getBasedir(), moduleList);

		// Step 4: Prune tests that no longer exist (commented/deleted) from selection and coverage map
		pruneNonExistentTests(output, selectedTestsFile);

		// Step 5: Merge coverage maps + generate report
		mergeCoverageMapsAndReport(root, rootTarget, selectedTestsFile, logger);
	}

	/**
	 * Determines which reactor modules need to be built. For SELECTED: modules containing
	 * selected tests or unmapped tests. For FULL_SUITE: all non-pom modules.
	 */
	private Set<String> determineAffectedModules(SelectionOutput output)
	{
		Set<String> modules = new LinkedHashSet<>();

		if (output.getSelectedTests() != null)
		{
			for (String test : output.getSelectedTests())
			{
				int hash = test.indexOf('#');
				String className = hash > 0 ? test.substring(0, hash) : test;
				String module = findModuleForTestClass(className);
				if (module != null)
				{
					modules.add(module);
				}
			}
		}

		// Include modules containing unmapped tests only if:
		// (a) the module is already affected by selected tests, OR
		// (b) the unmapped test shares a module with changed production code
		// This prevents building every module just because it has unmapped tests.
		Set<String> changedCodeModules = findModulesWithChangedCode(output);
		if (output.getUnmappedTests() != null)
		{
			for (String fqn : output.getUnmappedTests().keySet())
			{
				String module = findModuleForTestClassFqn(fqn);
				if (module != null && (modules.contains(module) || changedCodeModules.contains(module)))
				{
					modules.add(module);
				}
			}
		}

		if ("FULL_SUITE".equals(output.getStatus()))
		{
			for (MavenProject m : reactorProjects)
			{
				if (!"pom".equals(m.getPackaging()))
				{
					modules.add(m.getArtifactId());
				}
			}
		}

		getLog().info("[SmartTestPicker] Affected modules: " + modules);
		return modules;
	}

	private Set<String> findModulesWithChangedCode(SelectionOutput output)
	{
		Set<String> modules = new LinkedHashSet<>();
		if (output.getChangedClasses() == null)
		{
			return modules;
		}
		for (String changedClass : output.getChangedClasses())
		{
			String classFile = changedClass.replace('.', File.separatorChar) + ".class";
			for (MavenProject module : reactorProjects)
			{
				if ("pom".equals(module.getPackaging()))
				{
					continue;
				}
				File classes = new File(module.getBuild().getOutputDirectory());
				if (new File(classes, classFile).exists())
				{
					modules.add(module.getArtifactId());
					break;
				}
			}
		}
		return modules;
	}

	private String findModuleForTestClass(String simpleClassName)
	{
		for (MavenProject module : reactorProjects)
		{
			if ("pom".equals(module.getPackaging()))
			{
				continue;
			}
			File testClasses = new File(module.getBuild().getDirectory(), "test-classes");
			if (testClasses.exists() && containsClass(testClasses, simpleClassName))
			{
				return module.getArtifactId();
			}
		}
		return null;
	}

	private String findModuleForTestClassFqn(String fqn)
	{
		String classFile = fqn.replace('.', File.separatorChar) + ".class";
		for (MavenProject module : reactorProjects)
		{
			if ("pom".equals(module.getPackaging()))
			{
				continue;
			}
			File testClasses = new File(module.getBuild().getDirectory(), "test-classes");
			if (new File(testClasses, classFile).exists())
			{
				return module.getArtifactId();
			}
		}
		return null;
	}

	private boolean containsClass(File testClassesDir, String simpleClassName)
	{
		try
		{
			return java.nio.file.Files.walk(testClassesDir.toPath())
					.anyMatch(p -> p.getFileName().toString().equals(simpleClassName + ".class"));
		}
		catch (IOException e)
		{
			return false;
		}
	}

	private void writeIncludesFiles(SelectionOutput output) throws MojoExecutionException
	{
		for (MavenProject module : reactorProjects)
		{
			if ("pom".equals(module.getPackaging()))
			{
				continue;
			}
			try
			{
				Map<String, String> moduleUnmapped = filterUnmappedForModule(output, module);
				SelectionOutput moduleOutput = new SelectionOutput(
						output.getStatus(), output.getReason(),
						output.getSelectedTests(), moduleUnmapped);

				File moduleTarget = new File(module.getBuild().getDirectory());
				moduleTarget.mkdirs();
				File includesFile = new File(moduleTarget, "selected-tests-surefire.txt");
				SmartTestFilter.writeIncludesFile(moduleOutput, includesFile, classLevelSelection);

				if (!moduleUnmapped.isEmpty())
				{
					getLog().info("[SmartTestPicker] " + module.getArtifactId()
							+ ": " + moduleUnmapped.size() + " unmapped tests in includes");
				}
			}
			catch (IOException e)
			{
				getLog().warn("[SmartTestPicker] Failed to write includes for "
						+ module.getArtifactId() + ": " + e.getMessage());
			}
		}
	}

	/** Filters unmapped tests to only those whose .class file exists in this module's test-classes directory. */
	private Map<String, String> filterUnmappedForModule(SelectionOutput output, MavenProject module)
	{
		if (output.getUnmappedTests() == null || output.getUnmappedTests().isEmpty())
		{
			return Map.of();
		}
		File testClasses = new File(module.getBuild().getDirectory(), "test-classes");
		if (!testClasses.exists())
		{
			return Map.of();
		}
		Map<String, String> result = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : output.getUnmappedTests().entrySet())
		{
			String fqn = entry.getKey();
			String classFile = fqn.replace('.', File.separatorChar) + ".class";
			if (new File(testClasses, classFile).exists())
			{
				result.put(fqn, entry.getValue());
			}
		}
		return result;
	}

	/**
	 * Deletes stale .exec and .xml coverage artifacts for tests that will be re-run.
	 * Fresh artifacts are needed so new JaCoCo data doesn't get mixed with baseline data.
	 */
	private void cleanCoverageArtifactsForAffectedTests(SelectionOutput output, Set<String> affectedModuleIds)
	{
		Set<String> testClassPrefixes = new LinkedHashSet<>();
		if (output.getSelectedTests() != null)
		{
			for (String test : output.getSelectedTests())
			{
				int hash = test.indexOf('#');
				String className = hash > 0 ? test.substring(0, hash) : test;
				testClassPrefixes.add(className);
			}
		}
		if (output.getUnmappedTests() != null)
		{
			for (String fqn : output.getUnmappedTests().keySet())
			{
				int lastDot = fqn.lastIndexOf('.');
				String simpleName = lastDot >= 0 ? fqn.substring(lastDot + 1) : fqn;
				testClassPrefixes.add(simpleName);
			}
		}

		for (MavenProject module : reactorProjects)
		{
			if (!affectedModuleIds.contains(module.getArtifactId()))
			{
				continue;
			}
			File targetDir = new File(module.getBuild().getDirectory());
			deleteMatchingFiles(new File(targetDir, "jacoco"), testClassPrefixes);
			deleteMatchingFiles(new File(targetDir, "jacoco-xml"), testClassPrefixes);
		}
	}

	private void deleteMatchingFiles(File dir, Set<String> testClassNames)
	{
		if (!dir.exists())
		{
			return;
		}
		File[] files = dir.listFiles();
		if (files == null)
		{
			return;
		}
		int deleted = 0;
		for (File f : files)
		{
			String name = f.getName();
			for (String testClass : testClassNames)
			{
				if (name.contains(testClass))
				{
					f.delete();
					deleted++;
					break;
				}
			}
		}
		if (deleted > 0)
		{
			getLog().info("[SmartTestPicker] Deleted " + deleted + " stale coverage files from " + dir);
		}
	}

	private void forkMavenVerify(File baseDir, String moduleList) throws MojoExecutionException
	{
		InvocationRequest request = new DefaultInvocationRequest();
		request.setPomFile(new File(baseDir, "pom.xml"));
		request.setGoals(Collections.singletonList("verify"));
		request.setProjects(List.of(moduleList.split(",")));
		request.setProfiles(List.of("smart-test"));
		request.setReactorFailureBehavior(InvocationRequest.ReactorFailureBehavior.FailAtEnd);
		request.setRecursive(true);
		request.setBatchMode(true);

		Properties props = new Properties();
		String profiles = springProfiles;
		if (profiles == null || profiles.isEmpty())
		{
			profiles = System.getProperty("spring.profiles.active");
		}
		if (profiles != null && !profiles.isEmpty())
		{
			props.setProperty("spring.profiles.active", profiles);
		}
		request.setProperties(props);

		Invoker invoker = new DefaultInvoker();
		try
		{
			getLog().info("[SmartTestPicker] Forking: mvn verify -pl " + moduleList
					+ " -Psmart-test --fail-at-end");
			InvocationResult result = invoker.execute(request);
			if (result.getExitCode() != 0)
			{
				getLog().warn("[SmartTestPicker] Forked build exited with code "
						+ result.getExitCode() + " (--fail-at-end, continuing with report)");
			}
		}
		catch (MavenInvocationException e)
		{
			throw new MojoExecutionException("Failed to fork Maven build", e);
		}
	}

	/**
	 * Removes tests from the selection output that no longer have XML reports on disk.
	 * This handles tests that were deleted or commented out between selection and execution.
	 */
	private void pruneNonExistentTests(SelectionOutput output, File selectedTestsFile)
			throws MojoExecutionException
	{
		if (output.getSelectedTests() == null || output.getSelectedTests().isEmpty())
		{
			return;
		}

		List<File> xmlDirs = new ArrayList<>();
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
		}

		List<String> pruned = new ArrayList<>();
		int removed = 0;
		for (String test : output.getSelectedTests())
		{
			if (xmlExistsForTest(xmlDirs, test))
			{
				pruned.add(test);
			}
			else
			{
				removed++;
			}
		}

		if (removed > 0)
		{
			output.setSelectedTests(pruned);
			output.setReason(pruned.size() + " tests selected out of "
					+ output.getReason().replaceAll("^\\d+ tests selected out of ", "")
					+ " (" + removed + " removed: no longer in source)");
			writeJson(selectedTestsFile, output);
			getLog().info("[SmartTestPicker] Pruned " + removed
					+ " non-existent tests from selection, " + pruned.size() + " remaining");
		}
	}

	private boolean xmlExistsForTest(List<File> xmlDirs, String testName)
	{
		for (File dir : xmlDirs)
		{
			if (new File(dir, "session_" + testName + ".xml").exists())
			{
				return true;
			}
			if (new File(dir, testName + ".xml").exists())
			{
				return true;
			}
		}
		return false;
	}

	private void mergeCoverageMapsAndReport(MavenProject root, File rootTarget,
			File selectedTestsFile, MavenEngineLogger logger) throws MojoExecutionException
	{
		File mergedMapFile = new File(rootTarget, "test-coverage-map.json");

		// Start with the existing root baseline map to preserve entries from modules
		// that didn't run in this invocation. Module maps only contain tests that have
		// XML reports on disk — a selective run produces a partial module map.
		CoverageMap merged = new CoverageMap();
		CoverageMapMetadata metadata = null;
		if (mergedMapFile.exists())
		{
			try (FileReader reader = new FileReader(mergedMapFile))
			{
				CoverageMap baseline = gson.fromJson(reader, CoverageMap.class);
				if (baseline != null)
				{
					if (baseline.getTestMappings() != null)
					{
						merged.getTestMappings().putAll(baseline.getTestMappings());
					}
					if (baseline.getClassMetrics() != null)
					{
						merged.setClassMetrics(new HashMap<>(baseline.getClassMetrics()));
					}
					metadata = baseline.getMetadata();
				}
			}
			catch (IOException e)
			{
				getLog().warn("[SmartTestPicker] Failed to read existing baseline: " + e.getMessage());
			}
		}

		Map<String, ClassCoverageMetrics> mergedClassMetrics = merged.getClassMetrics() != null
				? new HashMap<>(merged.getClassMetrics())
				: new HashMap<>();
		int modulesFound = 0;
		int updatedTests = 0;

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
				updatedTests += moduleTests;

				// Remove all baseline entries for test classes present in this module map.
				// The module map contains only tests that actually ran and produced XML,
				// so stale entries (deleted/commented tests) are replaced, not preserved.
				Set<String> moduleTestClasses = new LinkedHashSet<>();
				for (String testKey : moduleData.getTestMappings().keySet())
				{
					int hash = testKey.indexOf('#');
					moduleTestClasses.add(hash > 0 ? testKey.substring(0, hash) : testKey);
				}
				merged.getTestMappings().keySet().removeIf(existingTest ->
				{
					int hash = existingTest.indexOf('#');
					String cls = hash > 0 ? existingTest.substring(0, hash) : existingTest;
					return moduleTestClasses.contains(cls);
				});

				merged.getTestMappings().putAll(moduleData.getTestMappings());
				if (moduleData.getClassMetrics() != null)
				{
					moduleData.getClassMetrics().forEach((cls, metrics) ->
							mergedClassMetrics.merge(cls, metrics, ClassCoverageMetrics::merge));
				}
			}
			catch (IOException e)
			{
				getLog().warn("[SmartTestPicker] Failed to read map from "
						+ module.getArtifactId() + ": " + e.getMessage());
			}
		}

		merged.setMetadata(metadata);
		if (!mergedClassMetrics.isEmpty())
		{
			merged.setClassMetrics(mergedClassMetrics);
		}

		writeJson(mergedMapFile, merged);

		getLog().info("[SmartTestPicker] Merged map: " + merged.getTestMappings().size()
				+ " total tests (" + updatedTests + " updated from " + modulesFound + " modules)");

		// Enrich selection output: resolve unmapped test classes to individual methods
		// now present in the post-build coverage map
		enrichSelectionOutput(selectedTestsFile, merged);

		// Generate report using XML-based source coverage (more reliable than exec merge)
		List<File> xmlDirs = new ArrayList<>();
		List<File> sourceDirs = new ArrayList<>();
		for (MavenProject module : reactorProjects)
		{
			if ("pom".equals(module.getPackaging()))
			{
				continue;
			}
			addIfExists(xmlDirs, new File(module.getBuild().getDirectory(), "jacoco-xml"));
			addIfExists(sourceDirs, new File(module.getBasedir(), "src/main/java"));
		}

		File reportFile = new File(rootTarget, "reports/smart-test-picker/index.html");
		try
		{
			new ReportEngine().generate(
					mergedMapFile, selectedTestsFile, reportFile,
					xmlDirs, sourceDirs,
					root.getBasedir(), maxCommitDistance, classLevelSelection, logger);
		}
		catch (IOException e)
		{
			throw new MojoExecutionException("Failed to generate report", e);
		}

		getLog().info("[SmartTestPicker] Report: " + reportFile.getAbsolutePath());
	}

	/**
	 * Post-build: resolves unmapped test class names to individual test method keys
	 * in the updated coverage map, so the selection output reflects what actually ran.
	 */
	private void enrichSelectionOutput(File selectedTestsFile, CoverageMap mergedMap)
			throws MojoExecutionException
	{
		try
		{
			String json = new String(Files.readAllBytes(selectedTestsFile.toPath()));
			SelectionOutput output = gson.fromJson(json, SelectionOutput.class);
			if (output == null || output.getUnmappedTests() == null || output.getUnmappedTests().isEmpty())
			{
				return;
			}

			List<String> selected = output.getSelectedTests() != null
					? new ArrayList<>(output.getSelectedTests())
					: new ArrayList<>();
			Set<String> selectedSet = new LinkedHashSet<>(selected);
			Map<String, String> remainingUnmapped = new LinkedHashMap<>(output.getUnmappedTests());
			int resolved = 0;

			for (Map.Entry<String, String> entry : output.getUnmappedTests().entrySet())
			{
				String unmappedFqn = entry.getKey();
				int lastDot = unmappedFqn.lastIndexOf('.');
				String simpleName = lastDot >= 0 ? unmappedFqn.substring(lastDot + 1) : unmappedFqn;

				boolean found = false;
				if (mergedMap.getTestMappings() != null)
				{
					for (String testKey : mergedMap.getTestMappings().keySet())
					{
						int hash = testKey.indexOf('#');
						String testClass = hash > 0 ? testKey.substring(0, hash) : testKey;
						if (testClass.equals(simpleName) && !selectedSet.contains(testKey))
						{
							selectedSet.add(testKey);
							found = true;
						}
					}
				}

				if (found)
				{
					remainingUnmapped.remove(unmappedFqn);
					resolved++;
				}
			}

			if (resolved > 0)
			{
				output.setSelectedTests(new ArrayList<>(selectedSet));
				output.setUnmappedTests(remainingUnmapped);
				output.setReason(output.getReason() + " (+" + resolved
						+ " unmapped classes resolved from updated coverage map)");
				writeJson(selectedTestsFile, output);
				getLog().info("[SmartTestPicker] Enriched selection: added methods from "
						+ resolved + " unmapped test classes, total selected: " + selectedSet.size());
			}
		}
		catch (IOException e)
		{
			getLog().warn("[SmartTestPicker] Failed to enrich selection output: " + e.getMessage());
		}
	}

	private void writeJson(File file, Object data) throws MojoExecutionException
	{
		file.getParentFile().mkdirs();
		try (FileWriter writer = new FileWriter(file))
		{
			gson.toJson(data, writer);
		}
		catch (IOException e)
		{
			throw new MojoExecutionException("Failed to write " + file.getName(), e);
		}
	}

	private static void addIfExists(List<File> list, File dir)
	{
		if (dir.exists())
		{
			list.add(dir);
		}
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
