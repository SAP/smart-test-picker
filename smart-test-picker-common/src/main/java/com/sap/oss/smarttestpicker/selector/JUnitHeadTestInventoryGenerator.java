// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.selector;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;
import org.junit.platform.engine.support.descriptor.ClassSource;
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
		return generate(revision, runtimeClasspath, testClassRoots, ignored -> { });
	}

	public HeadTestInventory generate(String revision, Collection<Path> runtimeClasspath,
			Collection<Path> testClassRoots, Consumer<String> diagnostics) {
		if (testClassRoots == null || testClassRoots.isEmpty())
			return bind(revision, HeadTestInventory.from(List.of()));
		Set<Path> roots = new LinkedHashSet<>(testClassRoots.stream().filter(java.nio.file.Files::isDirectory).toList());
		if (roots.isEmpty()) return bind(revision, HeadTestInventory.from(List.of()));
		List<URL> urls = new ArrayList<>();
		try {
			for (Path path : runtimeClasspath) urls.add(path.toUri().toURL());
			ClassLoader previous = Thread.currentThread().getContextClassLoader();
			boolean targetOwnsJUnit = ownsCompleteJUnitRuntime(runtimeClasspath);
			if (targetOwnsJUnit) urls.add(JUnitHeadTestInventoryGenerator.class.getProtectionDomain()
					.getCodeSource().getLocation());
			try (URLClassLoader loader = targetOwnsJUnit
					? new TargetJUnitClassLoader(urls.toArray(URL[]::new), previous)
					: new StpJUnitClassLoader(urls.toArray(URL[]::new), previous)) {
				Thread.currentThread().setContextClassLoader(loader);
				try {
					if (targetOwnsJUnit) return bind(revision, concreteOnly(loader, isolated(loader, roots, diagnostics)));
					stpJUnitRuntimeOrigins().forEach(diagnostics);
					LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
							.selectors(selectClasspathRoots(roots)).build();
					Launcher launcher = LauncherFactory.create(LauncherConfig.builder()
							.enableTestExecutionListenerAutoRegistration(false)
							.enableLauncherSessionListenerAutoRegistration(false).build());
					return bind(revision, concreteOnly(loader, inventory(launcher.discover(request))));
				} finally { Thread.currentThread().setContextClassLoader(previous); }
			}
		} catch (Exception failure) {
			throw new IllegalStateException("JUnit head inventory discovery failed with "
					+ junitArtifacts(runtimeClasspath), failure);
		}
	}

	private static HeadTestInventory concreteOnly(ClassLoader loader, HeadTestInventory inventory) {
		return HeadTestInventory.from(inventory.runnableTests().stream().filter(test -> {
			try {
				Class<?> type = Class.forName(test.className(), false, loader);
				for (Class<?> container = type; container != null; container = container.getEnclosingClass())
					if (java.lang.reflect.Modifier.isAbstract(container.getModifiers())) return false;
				return true;
			}
			catch (ClassNotFoundException failure) {
				throw new IllegalStateException("Cannot resolve discovered JUnit test class: " + test.className(), failure);
			}
		}).toList());
	}

	private static List<String> stpJUnitRuntimeOrigins() {
		return List.of("org.junit.jupiter.api.Test", "org.junit.jupiter.api.MethodOrderer",
				"org.junit.platform.launcher.Launcher", "org.junit.platform.engine.TestEngine",
				"org.junit.jupiter.engine.JupiterTestEngine").stream().map(name -> {
			try {
				Class<?> type = Class.forName(name, false, JUnitHeadTestInventoryGenerator.class.getClassLoader());
				var source = type.getProtectionDomain().getCodeSource();
				return name + " version=" + type.getPackage().getImplementationVersion() + " origin="
						+ (source == null ? "unknown" : source.getLocation()) + " loader=" + type.getClassLoader();
			} catch (ClassNotFoundException failure) {
				return name + " MISSING loader=" + JUnitHeadTestInventoryGenerator.class.getClassLoader();
			}
		}).toList();
	}

	private static boolean ownsCompleteJUnitRuntime(Collection<Path> classpath) {
		return List.of("junit-jupiter-api-", "junit-jupiter-engine-", "junit-platform-launcher-",
				"junit-platform-engine-", "junit-platform-commons-").stream()
				.allMatch(prefix -> classpath.stream().map(Path::getFileName).map(Path::toString)
						.anyMatch(name -> name.startsWith(prefix)));
	}

	private static String junitArtifacts(Collection<Path> classpath) {
		List<String> artifacts = classpath.stream().filter(path -> path.getFileName().toString().startsWith("junit-"))
				.map(Path::toString).toList();
		return artifacts.isEmpty() ? "STP-owned JUnit runtime" : "resolved JUnit artifacts " + artifacts;
	}

	private static HeadTestInventory bind(String revision, HeadTestInventory inventory) {
		return revision == null ? inventory : HeadTestInventory.atRevision(revision, inventory.runnableTests());
	}

	@SuppressWarnings("unchecked")
	private HeadTestInventory isolated(ClassLoader loader, Set<Path> roots, Consumer<String> diagnostics) throws Exception {
		Class<?> worker = Class.forName(JUnitInventoryDiscoveryWorker.class.getName(), true, loader);
		@SuppressWarnings("unchecked")
		List<String> origins = (List<String>) worker.getMethod("junitRuntimeOrigins").invoke(null);
		origins.forEach(diagnostics);
		Method method = worker.getMethod("discover", Set.class);
		List<String[]> rows = (List<String[]>) method.invoke(null, roots);
		List<TestIdentity> identities = rows.stream()
				.map(row -> JUnitMethodSourceIdentity.fromParts(row[0], row[1], row[2])).toList();
		return new HeadTestInventory(new LinkedHashSet<>(identities));
	}

	static final class TargetJUnitClassLoader extends URLClassLoader {
		private static final String ENGINE_SERVICE = "META-INF/services/org.junit.platform.engine.TestEngine";
		TargetJUnitClassLoader(URL[] urls, ClassLoader parent) { super(urls, parent); }
		@Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			if (name.startsWith("org.junit.")
					|| name.equals(JUnitInventoryDiscoveryWorker.class.getName())) {
				synchronized (getClassLoadingLock(name)) {
					Class<?> loaded = findLoadedClass(name);
					if (loaded == null) try { loaded = findClass(name); } catch (ClassNotFoundException ignored) { }
					if (loaded != null) { if (resolve) resolveClass(loaded); return loaded; }
			}
			}
			return super.loadClass(name, resolve);
		}
		@Override public Enumeration<URL> getResources(String name) throws IOException {
			if (ENGINE_SERVICE.equals(name)) return findResources(name);
			return super.getResources(name);
		}
	}

	/** Target application classes remain visible, but the parent owns every JUnit class and engine provider. */
	private static final class StpJUnitClassLoader extends URLClassLoader {
		private static final String ENGINE_SERVICE = "META-INF/services/org.junit.platform.engine.TestEngine";
		StpJUnitClassLoader(URL[] urls, ClassLoader parent) { super(urls, parent); }
		@Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			if (name.startsWith("org.junit.")) return getParent().loadClass(name);
			return super.loadClass(name, resolve);
		}
		@Override public Enumeration<URL> getResources(String name) throws IOException {
			if (ENGINE_SERVICE.equals(name)) return getParent().getResources(name);
			return super.getResources(name);
		}
	}

	HeadTestInventory inventory(TestPlan plan) {
		Set<TestIdentity> identities = new LinkedHashSet<>();
		for (TestIdentifier root : plan.getRoots()) visit(plan, root, null, null, identities);
		return new HeadTestInventory(identities);
	}

	private void visit(TestPlan plan, TestIdentifier id, MethodSource inheritedMethod,
			ClassSource inheritedClass, Set<TestIdentity> identities) {
		Optional<MethodSource> ownMethod = methodSource(id);
		MethodSource method = ownMethod.orElse(inheritedMethod);
		ClassSource testClass = classSource(id).orElse(inheritedClass);
		// Template descriptors are containers at discovery time; their authoritative declared
		// MethodSource is itself the logical test even before invocation children exist.
		ownMethod.map(JUnitMethodSourceIdentity::from).ifPresent(identities::add);
		if (id.isTest()) {
			if (method != null) identities.add(JUnitMethodSourceIdentity.from(method));
			else identities.add(vintageIdentity(id, testClass).orElseThrow(() -> new IllegalStateException(
					"Executable JUnit test has no authoritative MethodSource: " + id.getUniqueId())));
		}
		for (TestIdentifier child : plan.getChildren(id)) visit(plan, child, method, testClass, identities);
	}

	private Optional<MethodSource> methodSource(TestIdentifier id) {
		return id.getSource().filter(MethodSource.class::isInstance).map(MethodSource.class::cast);
	}

	private Optional<ClassSource> classSource(TestIdentifier id) {
		return id.getSource().filter(ClassSource.class::isInstance).map(ClassSource.class::cast);
	}

	private static Optional<TestIdentity> vintageIdentity(TestIdentifier id, ClassSource testClass) {
		if (testClass == null || !id.getUniqueId().contains("[engine:junit-vintage]")) return Optional.empty();
		return vintageMethodName(id.getLegacyReportingName())
				.map(method -> new TestIdentity(testClass.getClassName(), method));
	}

	static Optional<String> vintageMethodName(String legacyReportingName) {
		if (legacyReportingName == null) return Optional.empty();
		String method = legacyReportingName;
		int classSuffix = method.indexOf('(');
		if (classSuffix >= 0) method = method.substring(0, classSuffix);
		method = method.trim();
		return method.isEmpty() ? Optional.empty() : Optional.of(method);
	}
}
