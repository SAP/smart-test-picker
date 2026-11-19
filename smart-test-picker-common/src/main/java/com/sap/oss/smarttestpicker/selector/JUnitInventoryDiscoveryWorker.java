// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.selector;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.core.LauncherConfig;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClasspathRoots;

/** Loaded in a target-owned JUnit class loader when the test runtime supplies a complete JUnit stack. */
public final class JUnitInventoryDiscoveryWorker {
	private JUnitInventoryDiscoveryWorker() {}
	public static List<String> junitRuntimeOrigins() {
		return List.of("org.junit.jupiter.api.Test", "org.junit.jupiter.api.MethodOrderer",
				"org.junit.platform.launcher.Launcher", "org.junit.platform.engine.TestEngine",
				"org.junit.jupiter.engine.JupiterTestEngine").stream().map(JUnitInventoryDiscoveryWorker::origin).toList();
	}
	private static String origin(String name) {
		try {
			Class<?> type = Class.forName(name, false, JUnitInventoryDiscoveryWorker.class.getClassLoader());
			var source = type.getProtectionDomain().getCodeSource();
			return name + " version=" + type.getPackage().getImplementationVersion() + " origin="
					+ (source == null ? "unknown" : source.getLocation()) + " loader=" + type.getClassLoader();
		} catch (ClassNotFoundException failure) {
			return name + " MISSING loader=" + JUnitInventoryDiscoveryWorker.class.getClassLoader();
		}
	}
	public static List<String[]> discover(Set<Path> roots) {
		var request = LauncherDiscoveryRequestBuilder.request().selectors(selectClasspathRoots(roots)).build();
		TestPlan plan = LauncherFactory.create(LauncherConfig.builder()
				.enableTestExecutionListenerAutoRegistration(false)
				.enableLauncherSessionListenerAutoRegistration(false).build()).discover(request);
		Map<String, String[]> rows = new LinkedHashMap<>();
		for (TestIdentifier root : plan.getRoots()) visit(plan, root, null, null, rows);
		return new ArrayList<>(rows.values());
	}

	/** Uses only JDK types at the isolated class-loader boundary. */
	public static List<String[]> discoverSelected(List<String> classes, Map<String, List<String>> filters,
			java.util.function.BiPredicate<String, String> names) {
		var request = LauncherDiscoveryRequestBuilder.request();
		for (String name : classes) {
			Class<?> type;
			try {
				type = Class.forName(name, false, Thread.currentThread().getContextClassLoader());
			} catch (ClassNotFoundException | LinkageError failure) {
				throw new JUnitHeadTestInventoryGenerator.UnloadableTestClassException(name, failure);
			}
			if (type.getEnclosingClass() != null && !java.lang.reflect.Modifier.isStatic(type.getModifiers())) continue;
			request.selectors(org.junit.platform.engine.discovery.DiscoverySelectors.selectClass(type));
		}
		if (!filters.get("includeTags").isEmpty()) request.filters(org.junit.platform.launcher.TagFilter.includeTags(filters.get("includeTags")));
		if (!filters.get("excludeTags").isEmpty()) request.filters(org.junit.platform.launcher.TagFilter.excludeTags(filters.get("excludeTags")));
		if (!filters.get("includeEngines").isEmpty()) request.filters(org.junit.platform.launcher.EngineFilter.includeEngines(filters.get("includeEngines")));
		if (!filters.get("excludeEngines").isEmpty()) request.filters(org.junit.platform.launcher.EngineFilter.excludeEngines(filters.get("excludeEngines")));
		boolean ancestorMethods = Boolean.parseBoolean(filters.get("matchAncestorMethods").get(0));
		request.filters((org.junit.platform.launcher.PostDiscoveryFilter) descriptor ->
				org.junit.platform.engine.FilterResult.includedIf(selected(descriptor, names, ancestorMethods),
						() -> "Selected by build test filter", () -> "Excluded by build test filter"));
		TestPlan plan = LauncherFactory.create(LauncherConfig.builder()
				.enableTestExecutionListenerAutoRegistration(false)
				.enableLauncherSessionListenerAutoRegistration(false).build()).discover(request.build());
		Map<String, String[]> rows = new LinkedHashMap<>();
		for (TestIdentifier root : plan.getRoots()) visit(plan, root, null, null, rows);
		return new ArrayList<>(rows.values());
	}

	/** Discovery with filters supplied by the resolved provider, in this same isolated JUnit runtime. */
	public static List<String[]> discoverProviderSelection(List<String> classes, List<?> filters,
			Map<String, String> parameters) {
		var request = LauncherDiscoveryRequestBuilder.request().configurationParameters(parameters);
		classes.forEach(name -> request.selectors(org.junit.platform.engine.discovery.DiscoverySelectors.selectClass(name)));
		request.filters(filters.toArray(org.junit.platform.engine.Filter<?>[]::new));
		TestPlan plan = LauncherFactory.create(LauncherConfig.builder()
				.enableTestExecutionListenerAutoRegistration(false)
				.enableLauncherSessionListenerAutoRegistration(false).build()).discover(request.build());
		Map<String, String[]> rows = new LinkedHashMap<>();
		for (TestIdentifier root : plan.getRoots()) visit(plan, root, null, null, rows);
		return new ArrayList<>(rows.values());
	}

	private static boolean selected(org.junit.platform.engine.TestDescriptor descriptor,
			java.util.function.BiPredicate<String, String> names, boolean ancestorMethods) {
		String method = null;
		for (var current = descriptor; current.getParent().isPresent(); current = current.getParent().get()) {
			if (current.getSource().orElse(null) instanceof ClassSource source && names.test(source.getClassName(), method)) return true;
			if (ancestorMethods && current.getSource().orElse(null) instanceof MethodSource source) method = source.getMethodName();
		}
		return selectedSource(descriptor, names, false);
	}

	private static boolean selectedSource(org.junit.platform.engine.TestDescriptor descriptor,
			java.util.function.BiPredicate<String, String> names, boolean parent) {
		var source = descriptor.getSource().orElse(null);
		if (source == null) return true;
		if (source instanceof MethodSource method) {
			if (names.test(method.getClassName(), method.getMethodName())) return true;
			return descriptor.getParent().flatMap(org.junit.platform.engine.TestDescriptor::getSource)
					.filter(ClassSource.class::isInstance).map(ClassSource.class::cast)
					.map(owner -> names.test(owner.getClassName(), method.getMethodName())).orElse(false);
		}
		if (source instanceof ClassSource type) {
			if (!parent && descriptor.getChildren().stream().anyMatch(child -> selectedSource(child, names, false))) return true;
			return !descriptor.getChildren().isEmpty() || names.test(type.getClassName(), null)
					|| names.test(type.getClassName(), descriptor.getLegacyReportingName());
		}
		return descriptor.getParent().map(owner -> selectedSource(owner, names, true)).orElse(false);
	}
	private static void visit(TestPlan plan, TestIdentifier id, MethodSource inheritedMethod,
			ClassSource inheritedClass, Map<String, String[]> rows) {
		Optional<MethodSource> own = id.getSource().filter(MethodSource.class::isInstance).map(MethodSource.class::cast);
		Optional<ClassSource> ownClass = id.getSource().filter(ClassSource.class::isInstance).map(ClassSource.class::cast);
		MethodSource source = own.orElse(inheritedMethod);
		ClassSource testClass = ownClass.orElse(inheritedClass);
		if (own.isPresent()) add(own.get(), rows);
		if (id.isTest()) {
			if (source != null) add(source, rows);
			else addVintage(id, testClass, rows);
		}
		for (TestIdentifier child : plan.getChildren(id)) visit(plan, child, source, testClass, rows);
	}
	private static void addVintage(TestIdentifier id, ClassSource testClass, Map<String, String[]> rows) {
		if (testClass == null || !id.getUniqueId().contains("[engine:junit-vintage]"))
			throw new IllegalStateException("Executable JUnit test has no authoritative MethodSource: " + id.getUniqueId());
		String method = JUnitHeadTestInventoryGenerator.vintageMethodName(id.getLegacyReportingName())
				.orElseThrow(() -> new IllegalStateException(
						"Executable JUnit Vintage test has no usable method identity: " + id.getUniqueId()));
		String[] row = { testClass.getClassName(), method, "" };
		rows.putIfAbsent(row[0] + "\u0000" + row[1] + "\u0000", row);
	}
	private static void add(MethodSource source, Map<String, String[]> rows) {
		String[] row = { source.getClassName(), source.getMethodName(), source.getMethodParameterTypes() };
		if (row[0] == null || row[0].isBlank() || row[1] == null || row[1].isBlank())
			throw new IllegalStateException("JUnit MethodSource has no usable declared test identity");
		rows.putIfAbsent(row[0] + "\u0000" + row[1] + "\u0000" + row[2], row);
	}
}
