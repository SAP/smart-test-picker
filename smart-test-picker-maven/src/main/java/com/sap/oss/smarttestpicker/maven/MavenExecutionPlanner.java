// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.project.MavenProject;

import com.sap.oss.smarttestpicker.execution.ExecutionFallbackCause;
import com.sap.oss.smarttestpicker.execution.ExecutionIdentityMetadata;
import com.sap.oss.smarttestpicker.execution.ExecutionMode;
import com.sap.oss.smarttestpicker.execution.ExecutionPlan;
import com.sap.oss.smarttestpicker.execution.ExecutionPlanEntry;
import com.sap.oss.smarttestpicker.execution.ExecutionShape;
import com.sap.oss.smarttestpicker.selector.SelectionOutput;

/** Pure JZC-02A translation from a selected logical set to module-scoped Surefire selectors. */
final class MavenExecutionPlanner
{
	private final List<MavenProject> modules;

	MavenExecutionPlanner(List<MavenProject> modules) { this.modules = modules; }

	ExecutionPlan plan(SelectionOutput output, boolean classLevelSelection)
	{
		List<ExecutionPlanEntry> entries = new ArrayList<>();
		Map<String, ExecutionIdentityMetadata> identities = output.getExecutionIdentities() != null
				? output.getExecutionIdentities() : Map.of();
		for (String selected : output.getSelectedTests() != null ? output.getSelectedTests() : List.<String>of())
		{
			ExecutionIdentityMetadata identity = identities.get(selected);
			Resolution resolution = resolve(selected, identity);
			if (resolution.module == null || resolution.classFqn == null)
			{
				entries.add(new ExecutionPlanEntry(selected, ExecutionMode.FULL_SUITE_FALLBACK,
						ExecutionFallbackCause.MODULE_SCOPE_UNRESOLVED, resolution.module, null, List.of()));
				continue;
			}
			if (classLevelSelection)
			{
				entries.add(classFallback(selected, resolution, ExecutionFallbackCause.CLASS_LEVEL_SELECTION_REQUESTED));
				continue;
			}
			if (identity == null || identity.getTestClassFqn() == null || identity.getLogicalMethodName() == null)
			{
				entries.add(classFallback(selected, resolution, ExecutionFallbackCause.AMBIGUOUS_TEST_IDENTITY));
				continue;
			}
			ExecutionShape shape = identity.getExecutionShape() != null ? identity.getExecutionShape() : ExecutionShape.UNKNOWN;
			if (shape == ExecutionShape.UNKNOWN)
			{
				entries.add(classFallback(selected, resolution, ExecutionFallbackCause.UNKNOWN_EXECUTION_SHAPE));
				continue;
			}
			if (shape == ExecutionShape.JUNIT4_PARAMETERIZED_NAMED)
			{
				// The wildcard selector is exact through direct Surefire invocation,
				// but the frozen Maven Invoker product path produces a zero-match
				// execution. One fork per selected method is deliberately unsupported;
				// retain safety with visible class expansion.
				entries.add(classFallback(selected, resolution, ExecutionFallbackCause.UNSUPPORTED_RUNNER));
				continue;
			}
			if (!methodExists(resolution.project, identity.getTestClassFqn(), identity.getLogicalMethodName()))
			{
				entries.add(classFallback(selected, resolution, ExecutionFallbackCause.STALE_METHOD_IDENTITY));
				continue;
			}
			String selector = identity.getTestClassFqn() + "#" + identity.getLogicalMethodName();
			entries.add(new ExecutionPlanEntry(selected, ExecutionMode.METHOD_EXACT, null,
					resolution.module, selector, List.of(selected)));
		}

		if (output.getUnmappedTests() != null)
		{
			for (String fqn : output.getUnmappedTests().keySet())
			{
				Resolution resolution = resolveFqn(fqn);
				if (resolution.module == null)
					entries.add(new ExecutionPlanEntry(fqn, ExecutionMode.FULL_SUITE_FALLBACK,
							ExecutionFallbackCause.MODULE_SCOPE_UNRESOLVED, null, null, List.of()));
				else
					entries.add(new ExecutionPlanEntry(fqn, ExecutionMode.CLASS_FALLBACK,
							ExecutionFallbackCause.UNMAPPED_TEST_CLASS, resolution.module, fqn,
							discoverMethods(resolution.project, fqn)));
			}
		}
		return new ExecutionPlan(entries);
	}

	Map<String, String> selectorsByModule(ExecutionPlan plan)
	{
		Map<String, LinkedHashSet<String>> selectors = new LinkedHashMap<>();
		Set<String> fullSuiteModules = new LinkedHashSet<>();
		boolean globalFullSuite = false;
		for (ExecutionPlanEntry entry : plan.entries())
		{
			if (entry.executionMode() == ExecutionMode.NOT_EXECUTABLE_ERROR) continue;
			if (entry.executionMode() == ExecutionMode.FULL_SUITE_FALLBACK)
			{
				if (entry.module() == null)
					globalFullSuite = true;
				else fullSuiteModules.add(entry.module());
				continue;
			}
			selectors.computeIfAbsent(entry.module(), ignored -> new LinkedHashSet<>()).add(entry.generatedSurefireSelector());
		}
		Map<String, String> result = new LinkedHashMap<>();
		if (globalFullSuite)
		{
			for (MavenProject project : modules) if (!"pom".equals(project.getPackaging())) result.put(project.getArtifactId(), null);
			return result;
		}
		selectors.forEach((module, values) -> result.put(module, values.isEmpty() ? null : String.join(",", values)));
		fullSuiteModules.forEach(module -> result.put(module, null));
		return result;
	}

	/** Builds invocation-safe batches for Surefire 2.22.2 named Parameterized selectors. */
	Map<String, List<String>> selectorBatchesByModule(ExecutionPlan plan)
	{
		Map<String, List<String>> result = new LinkedHashMap<>();
		for (var moduleEntry : selectorsByModule(plan).entrySet())
		{
			if (moduleEntry.getValue() == null)
			{
				result.put(moduleEntry.getKey(), java.util.Collections.singletonList(null));
				continue;
			}
			List<String> ordinary = new ArrayList<>();
			Map<String, List<String>> namedParameterized = new LinkedHashMap<>();
			for (String selector : moduleEntry.getValue().split(","))
			{
				if (selector.endsWith("[*]") && selector.contains("#"))
				{
					int hash = selector.indexOf('#');
					namedParameterized.computeIfAbsent(selector.substring(0, hash), ignored -> new ArrayList<>())
							.add(selector.substring(hash + 1));
				}
				else ordinary.add(selector);
			}
			List<String> batches = new ArrayList<>();
			if (!ordinary.isEmpty()) batches.add(String.join(",", ordinary));
			namedParameterized.forEach((className, methods) -> batches.add(methods.stream()
					.map(method -> className + "#" + method).collect(java.util.stream.Collectors.joining(","))));
			result.put(moduleEntry.getKey(), batches);
		}
		return result;
	}

	private ExecutionPlanEntry classFallback(String selected, Resolution resolution, ExecutionFallbackCause cause)
	{
		return new ExecutionPlanEntry(selected, ExecutionMode.CLASS_FALLBACK, cause, resolution.module,
				resolution.classFqn, discoverMethods(resolution.project, resolution.classFqn));
	}

	private Resolution resolve(String legacy, ExecutionIdentityMetadata identity)
	{
		if (identity != null && identity.getTestClassFqn() != null)
		{
			Resolution result = resolveFqn(identity.getTestClassFqn());
			if (identity.getModule() == null || identity.getModule().equals(result.module)) return result;
			MavenProject project = module(identity.getModule());
			if (project != null && classFile(project, identity.getTestClassFqn()).isFile())
				return new Resolution(project.getArtifactId(), identity.getTestClassFqn(), project);
		}
		String simple = legacy.contains("#") ? legacy.substring(0, legacy.indexOf('#')) : legacy;
		List<Resolution> candidates = new ArrayList<>();
		for (MavenProject project : modules)
		{
			if ("pom".equals(project.getPackaging())) continue;
			File root = new File(project.getBuild().getTestOutputDirectory());
			if (!root.isDirectory()) continue;
			try (var stream = java.nio.file.Files.walk(root.toPath()))
			{
				stream.filter(path -> path.getFileName().toString().equals(simple + ".class"))
						.forEach(path -> candidates.add(new Resolution(project.getArtifactId(),
								root.toPath().relativize(path).toString().replace(File.separatorChar, '.')
										.replaceAll("\\.class$", ""), project)));
			}
			catch (Exception ignored) {}
		}
		return candidates.size() == 1 ? candidates.get(0) : new Resolution(null, null, null);
	}

	private Resolution resolveFqn(String fqn)
	{
		List<MavenProject> found = modules.stream().filter(p -> !"pom".equals(p.getPackaging()))
				.filter(p -> classFile(p, fqn).isFile()).toList();
		return found.size() == 1 ? new Resolution(found.get(0).getArtifactId(), fqn, found.get(0))
				: new Resolution(null, fqn, null);
	}

	private MavenProject module(String id) { return modules.stream().filter(p -> p.getArtifactId().equals(id)).findFirst().orElse(null); }
	private File classFile(MavenProject project, String fqn) { return new File(project.getBuild().getTestOutputDirectory(), fqn.replace('.', File.separatorChar) + ".class"); }

	private boolean methodExists(MavenProject project, String fqn, String method)
	{
		return discoverMethods(project, fqn).contains(fqn + "#" + method);
	}

	private List<String> discoverMethods(MavenProject project, String fqn)
	{
		if (project == null) return List.of();
		try
		{
			List<URL> urls = new ArrayList<>();
			for (String element : project.getTestClasspathElements()) urls.add(new File(element).toURI().toURL());
			try (URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader()))
			{
				Class<?> type = Class.forName(fqn, false, loader);
				Set<String> methods = new LinkedHashSet<>();
				for (var method : type.getMethods()) methods.add(fqn + "#" + method.getName());
				return methods.stream().sorted().toList();
			}
		}
		catch (Exception | LinkageError ignored) { return List.of(); }
	}

	private record Resolution(String module, String classFqn, MavenProject project) {}
}
