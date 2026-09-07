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
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.core.LauncherConfig;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClasspathRoots;

/** Loaded in a target-owned JUnit class loader when the test runtime supplies its own launcher. */
public final class JUnitInventoryDiscoveryWorker {
	private JUnitInventoryDiscoveryWorker() {}
	public static List<String[]> discover(Set<Path> roots) {
		var request = LauncherDiscoveryRequestBuilder.request().selectors(selectClasspathRoots(roots)).build();
		TestPlan plan = LauncherFactory.create(LauncherConfig.builder()
				.enableTestExecutionListenerAutoRegistration(false)
				.enableLauncherSessionListenerAutoRegistration(false).build()).discover(request);
		Map<String, String[]> rows = new LinkedHashMap<>();
		for (TestIdentifier root : plan.getRoots()) visit(plan, root, null, rows);
		return new ArrayList<>(rows.values());
	}
	private static void visit(TestPlan plan, TestIdentifier id, MethodSource inherited, Map<String, String[]> rows) {
		Optional<MethodSource> own = id.getSource().filter(MethodSource.class::isInstance).map(MethodSource.class::cast);
		MethodSource source = own.orElse(inherited);
		if (own.isPresent()) add(own.get(), rows);
		if (id.isTest()) {
			if (source == null) throw new IllegalStateException("Executable JUnit test has no authoritative MethodSource: " + id.getUniqueId());
			add(source, rows);
		}
		for (TestIdentifier child : plan.getChildren(id)) visit(plan, child, source, rows);
	}
	private static void add(MethodSource source, Map<String, String[]> rows) {
		String[] row = { source.getClassName(), source.getMethodName(), source.getMethodParameterTypes() };
		if (row[0] == null || row[0].isBlank() || row[1] == null || row[1].isBlank())
			throw new IllegalStateException("JUnit MethodSource has no usable declared test identity");
		rows.putIfAbsent(row[0] + "\u0000" + row[1] + "\u0000" + row[2], row);
	}
}
