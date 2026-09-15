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
