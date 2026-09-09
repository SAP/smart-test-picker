// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.selector;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.lang.reflect.Method;

import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.core.LauncherConfig;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClasspathRoots;

/** Build-tool-neutral, discovery-only producer of exact logical JUnit test identities. */
public final class JUnitHeadTestInventoryGenerator {
	public HeadTestInventory generate(Collection<Path> runtimeClasspath, Collection<Path> testClassRoots) {
		return generate(null, runtimeClasspath, testClassRoots);
	}

	public HeadTestInventory generate(String revision, Collection<Path> runtimeClasspath,
			Collection<Path> testClassRoots) {
		if (testClassRoots == null || testClassRoots.isEmpty())
			return bind(revision, HeadTestInventory.from(List.of()));
		Set<Path> roots = new LinkedHashSet<>(testClassRoots.stream().filter(java.nio.file.Files::isDirectory).toList());
		if (roots.isEmpty()) return bind(revision, HeadTestInventory.from(List.of()));
		List<URL> urls = new ArrayList<>();
		try {
			for (Path path : runtimeClasspath) urls.add(path.toUri().toURL());
			ClassLoader previous = Thread.currentThread().getContextClassLoader();
			boolean targetOwnsLauncher = runtimeClasspath.stream()
					.anyMatch(path -> path.getFileName().toString().startsWith("junit-platform-launcher-"));
			if (targetOwnsLauncher) urls.add(JUnitHeadTestInventoryGenerator.class.getProtectionDomain()
					.getCodeSource().getLocation());
			try (URLClassLoader loader = targetOwnsLauncher
					? new TargetJUnitClassLoader(urls.toArray(URL[]::new), previous)
					: new URLClassLoader(urls.toArray(URL[]::new), previous)) {
				Thread.currentThread().setContextClassLoader(loader);
				try {
					if (targetOwnsLauncher) return bind(revision, isolated(loader, roots));
					LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
							.selectors(selectClasspathRoots(roots)).build();
					Launcher launcher = LauncherFactory.create(LauncherConfig.builder()
							.enableTestExecutionListenerAutoRegistration(false)
							.enableLauncherSessionListenerAutoRegistration(false).build());
					return bind(revision, inventory(launcher.discover(request)));
				} finally { Thread.currentThread().setContextClassLoader(previous); }
			}
		} catch (Exception failure) {
			throw new IllegalStateException("JUnit head inventory discovery failed", failure);
		}
	}

	private static HeadTestInventory bind(String revision, HeadTestInventory inventory) {
		return revision == null ? inventory : HeadTestInventory.atRevision(revision, inventory.runnableTests());
	}

	@SuppressWarnings("unchecked")
	private HeadTestInventory isolated(ClassLoader loader, Set<Path> roots) throws Exception {
		Class<?> worker = Class.forName(JUnitInventoryDiscoveryWorker.class.getName(), true, loader);
		Method method = worker.getMethod("discover", Set.class);
		List<String[]> rows = (List<String[]>) method.invoke(null, roots);
		List<TestIdentity> identities = rows.stream()
				.map(row -> JUnitMethodSourceIdentity.fromParts(row[0], row[1], row[2])).toList();
		return new HeadTestInventory(new LinkedHashSet<>(identities));
	}

	private static final class TargetJUnitClassLoader extends URLClassLoader {
		TargetJUnitClassLoader(URL[] urls, ClassLoader parent) { super(urls, parent); }
		@Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			if (name.startsWith("org.junit.platform.") || name.startsWith("org.junit.jupiter.")
					|| name.equals(JUnitInventoryDiscoveryWorker.class.getName())) {
				synchronized (getClassLoadingLock(name)) {
					Class<?> loaded = findLoadedClass(name);
					if (loaded == null) try { loaded = findClass(name); } catch (ClassNotFoundException ignored) { }
					if (loaded != null) { if (resolve) resolveClass(loaded); return loaded; }
			}
			}
			return super.loadClass(name, resolve);
		}
	}

	HeadTestInventory inventory(TestPlan plan) {
		Set<TestIdentity> identities = new LinkedHashSet<>();
		for (TestIdentifier root : plan.getRoots()) visit(plan, root, null, identities);
		return new HeadTestInventory(identities);
	}

	private void visit(TestPlan plan, TestIdentifier id, MethodSource inherited, Set<TestIdentity> identities) {
		Optional<MethodSource> ownMethod = methodSource(id);
		MethodSource method = ownMethod.orElse(inherited);
		// Template descriptors are containers at discovery time; their authoritative declared
		// MethodSource is itself the logical test even before invocation children exist.
		ownMethod.map(JUnitMethodSourceIdentity::from).ifPresent(identities::add);
		if (id.isTest()) {
			if (method == null) throw new IllegalStateException(
					"Executable JUnit test has no authoritative MethodSource: " + id.getUniqueId());
			identities.add(JUnitMethodSourceIdentity.from(method));
		}
		for (TestIdentifier child : plan.getChildren(id)) visit(plan, child, method, identities);
	}

	private Optional<MethodSource> methodSource(TestIdentifier id) {
		return id.getSource().filter(MethodSource.class::isInstance).map(MethodSource.class::cast);
	}
}
