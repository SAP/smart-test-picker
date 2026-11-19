// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.internal.LifecycleExecutionPlanCalculator;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MavenPluginManager;
import org.apache.maven.plugin.Mojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.util.filter.DependencyFilterUtils;
import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;
import com.sap.oss.smarttestpicker.selector.JUnitInventoryDiscoveryWorker;
import com.sap.oss.smarttestpicker.selector.JUnitHeadTestInventoryGenerator;

/**
 * Version-isolated boundary to the resolved Surefire/Failsafe implementation.
 * Maven configures the real provider mojo without executing it. Its scan lists and
 * TestListResolver, and the matching version's JUnit Platform filters, define selection;
 * STP does not maintain a second provider glob or tag parser.
 */
final class MavenProviderSelection {
    private final MavenSession session;
    private final MavenPluginManager plugins;
    private final LifecycleExecutionPlanCalculator plans;
    private final RepositorySystem repositories;
    private final java.util.function.Consumer<String> diagnostics;

    MavenProviderSelection(MavenSession session, MavenPluginManager plugins,
            LifecycleExecutionPlanCalculator plans, RepositorySystem repositories,
            java.util.function.Consumer<String> diagnostics) {
        this.session = session;
        this.plugins = plugins;
        this.plans = plans;
        this.repositories = repositories;
        this.diagnostics = diagnostics;
    }

    Set<TestIdentity> discover(MavenProject project, String providerName, String executionId, String target) throws Exception {
        String provider = providerName == null || providerName.isBlank() ? "surefire" : providerName;
        String id = executionId == null || executionId.isBlank()
                ? (provider.equals("surefire") ? "default-test" : "default-integration-test") : executionId;
        String context = target + " (module " + project.getArtifactId() + ", " + provider + ", execution " + id + ")";
        String resolvedVersion = "unresolved";
        try {
            if (session.getUserProperties().containsKey("test") || session.getUserProperties().containsKey("it.test")) {
                throw new IllegalArgumentException("Command-line test selection is unsupported for inventory and mapping");
            }
            var scoped = session.clone();
            scoped.setCurrentProject(project);
            Plugin plugin = project.getPlugin("org.apache.maven.plugins:maven-" + provider + "-plugin");
            if (plugin == null) throw new IllegalArgumentException("Provider plugin is absent from the effective build");
            var raw = plugin.getConfiguration() instanceof org.codehaus.plexus.util.xml.Xpp3Dom value
                    ? new org.codehaus.plexus.util.xml.Xpp3Dom(value) : new org.codehaus.plexus.util.xml.Xpp3Dom("configuration");
            for (var configured : plugin.getExecutions()) {
                if (configured.getId().equals(id) && configured.getConfiguration() instanceof org.codehaus.plexus.util.xml.Xpp3Dom value) {
                    raw = org.codehaus.plexus.util.xml.Xpp3Dom.mergeXpp3Dom(new org.codehaus.plexus.util.xml.Xpp3Dom(value), raw);
                }
            }
            if (raw.getChild("suiteXmlFiles") != null) throw new IllegalArgumentException("suiteXmlFiles is unsupported");
            for (var dependency : plugin.getDependencies()) {
                if (dependency.getArtifactId().startsWith("surefire-")
                        && !dependency.getArtifactId().equals("surefire-junit-platform")) {
                    throw new IllegalArgumentException("Legacy/custom provider dependency is unsupported: " + dependency.getArtifactId());
                }
            }
            var execution = new MojoExecution(plugin, provider.equals("surefire") ? "test" : "integration-test", id);
            plans.setupMojoExecution(scoped, project, execution);
            resolvedVersion = execution.getMojoDescriptor().getPluginDescriptor().getVersion();
            Object mojo = plugins.getConfiguredMojo(Mojo.class, scoped, execution);
            try {
                if (nonEmpty(call(mojo, "getDependenciesToScan"))) throw new IllegalArgumentException("dependenciesToScan is unsupported");
                // Retain this check on versions that removed the mojo parameter entirely.
                var suite = execution.getConfiguration().getChild("suiteXmlFiles");
                if (suite != null && suite.getChildCount() > 0)
                    throw new IllegalArgumentException("suiteXmlFiles is unsupported");
                List<String> scanIncludes = strings(call(mojo, "getIncludedScanList"));
                List<String> scanExcludes = strings(call(mojo, "getExcludedScanList"));
                List<String> methodIncludes = strings(call(mojo, "getIncludeList"));
                List<String> methodExcludes = strings(call(mojo, "getExcludeList"));
                List<Path> classpath = new ArrayList<>(project.getTestClasspathElements().stream().map(Path::of).toList());
                boolean platform = project.getArtifacts().stream().anyMatch(artifact ->
                        artifact.getGroupId().equals("org.junit.platform") && artifact.getArtifactId().equals("junit-platform-engine"));
                if (!platform) throw new IllegalArgumentException("A JUnit Platform engine must be present on the project test runtime; "
                        + "provider-only engine injection and non-Platform providers are unsupported");
                if (project.getArtifacts().stream().noneMatch(artifact -> artifact.getGroupId().equals("org.junit.platform")
                        && artifact.getArtifactId().equals("junit-platform-launcher"))) {
                    // Like the provider, align an implicit launcher with the target's Platform runtime,
                    // not with the provider artifact's own compile-time JUnit dependency.
                    String platformVersion = project.getArtifacts().stream().filter(artifact ->
                            artifact.getGroupId().equals("org.junit.platform") && artifact.getArtifactId().equals("junit-platform-engine"))
                            .findFirst().orElseThrow().getVersion();
                    var launcher = repositories.resolveArtifact(session.getRepositorySession(),
                            new org.eclipse.aether.resolution.ArtifactRequest(new DefaultArtifact(
                                    "org.junit.platform", "junit-platform-launcher", "jar", platformVersion),
                                    project.getRemoteProjectRepositories(), "stp-provider-discovery"));
                    classpath.add(launcher.getArtifact().getFile().toPath());
                }
                Map<String, String> properties = new HashMap<>();
                // Let the resolved mojo apply provider wire keys and property precedence itself.
                if (call(mojo, "getProperties") == null) {
                    mojo.getClass().getMethod("setProperties", Properties.class).invoke(mojo, new Properties());
                }
                call(mojo, "convertGroupParameters");
                call(mojo, "convertJunitEngineParameters");
                Object configured = call(mojo, "getProperties");
                if (configured instanceof Properties values) values.forEach((key, value) -> properties.put(key.toString(), value.toString()));
                classpath.addAll(providerClasspath(project, resolvedVersion));
                return discoverIsolated(project, classpath, scanIncludes, scanExcludes, methodIncludes, methodExcludes, properties,
                        message -> diagnostics.accept("[SmartTestPicker] " + project.getId() + " " + message));
            } finally {
                plugins.releaseMojo(mojo, execution);
            }
        } catch (Exception | LinkageError failure) {
            throw new IllegalArgumentException("Cannot reproduce provider selection for " + context
                    + "; resolved version " + resolvedVersion + "; qualified versions [3.2.5, 3.5.6, 3.6.1]. "
                    + "Provider compatibility or configuration check failed: " + rootMessage(failure), failure);
        }
    }

    private List<Path> providerClasspath(MavenProject project, String version) throws Exception {
        var artifact = new DefaultArtifact("org.apache.maven.surefire", "surefire-junit-platform", "jar", version);
        var collect = new CollectRequest(new Dependency(artifact, "runtime"), project.getRemotePluginRepositories());
        var resolved = repositories.resolveDependencies(session.getRepositorySession(),
                new DependencyRequest(collect, DependencyFilterUtils.classpathFilter("runtime")));
        return resolved.getArtifactResults().stream().map(result -> result.getArtifact().getFile().toPath()).toList();
    }

    private static Set<TestIdentity> discoverIsolated(MavenProject project, List<Path> classpath,
            List<String> scanIncludes, List<String> scanExcludes, List<String> methodIncludes,
            List<String> methodExcludes, Map<String, String> properties,
            java.util.function.Consumer<String> diagnostics) throws Exception {
        Set<URL> urls = new LinkedHashSet<>();
        for (Path path : classpath) urls.add(path.toUri().toURL());
        urls.add(JUnitInventoryDiscoveryWorker.class.getProtectionDomain().getCodeSource().getLocation());
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (var loader = JUnitHeadTestInventoryGenerator.isolatedTargetRuntime(urls.toArray(URL[]::new))) {
            Thread.currentThread().setContextClassLoader(loader);
            Class<?> resolverType = loader.loadClass("org.apache.maven.surefire.api.testset.TestListResolver");
            Object scan = resolverType.getConstructor(Collection.class, Collection.class).newInstance(scanIncludes, scanExcludes);
            Object methods = resolverType.getConstructor(Collection.class, Collection.class).newInstance(methodIncludes, methodExcludes);
            Method shouldRun = resolverType.getMethod("shouldRun", String.class, String.class);
            Path root = Path.of(project.getBuild().getTestOutputDirectory());
            if (!Files.isDirectory(root)) throw new IllegalArgumentException("Compiled test output is unavailable: " + root);
            List<String> classes = new ArrayList<>();
            try (var paths = Files.walk(root)) {
                for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                    String relative = root.relativize(path).toString().replace(File.separatorChar, '/');
                    if (relative.endsWith(".class") && !relative.equals("module-info.class")
                            && (boolean) shouldRun.invoke(scan, relative, null)) {
                        classes.add(relative.substring(0, relative.length() - 6).replace('/', '.'));
                    }
                }
            }
            Class<?> constants = loader.loadClass("org.apache.maven.surefire.api.booter.ProviderParameterNames");
            for (var entry : Map.of("INCLUDES_SCAN_LIST", scanIncludes, "EXCLUDES_SCAN_LIST", scanExcludes).entrySet()) {
                try { properties.put(constants.getField(entry.getKey()).get(null).toString(), String.join(",", entry.getValue())); }
                catch (NoSuchFieldException ignored) { /* These scan filters were introduced in Surefire 3.6. */ }
            }
            Class<?> requestType = loader.loadClass("org.apache.maven.surefire.api.testset.TestRequest");
            Object request;
            try {
                request = requestType.getConstructor(File.class, resolverType, int.class).newInstance(null, methods, 0);
            } catch (NoSuchMethodException olderProvider) {
                request = requestType.getConstructor(List.class, File.class, resolverType, int.class)
                        .newInstance(List.of(), null, methods, 0);
            }
            Object scanResult = loader.loadClass("org.apache.maven.surefire.api.util.DefaultScanResult")
                    .getConstructor(List.class).newInstance(classes);
            Object testRequest = request;
            Class<?> parameters = loader.loadClass("org.apache.maven.surefire.api.provider.ProviderParameters");
            Object proxy = Proxy.newProxyInstance(loader, new Class<?>[]{parameters}, (object, method, args) -> switch (method.getName()) {
                case "getProviderProperties" -> properties;
                case "getTestRequest" -> testRequest;
                case "getTestClassLoader" -> loader;
                case "getScanResult" -> scanResult;
                case "isInsideFork" -> false;
                default -> throw new IllegalStateException("Unsupported provider discovery API: " + method.getName());
            });
            Class<?> provider = loader.loadClass("org.apache.maven.surefire.junitplatform.JUnitPlatformProvider");
            Object instance = provider.getConstructor(parameters).newInstance(proxy);
            Object[] filters = (Object[]) call(instance, "getFilters");
            @SuppressWarnings("unchecked")
            Map<String, String> configuration = (Map<String, String>) call(instance, "getConfigurationParameters");
            Class<?> worker = loader.loadClass(JUnitInventoryDiscoveryWorker.class.getName());
            @SuppressWarnings("unchecked")
            List<String> origins = (List<String>) worker.getMethod("junitRuntimeOrigins").invoke(null);
            origins.forEach(diagnostics);
            @SuppressWarnings("unchecked")
            List<String[]> rows = (List<String[]>) worker.getMethod("discoverProviderSelection", List.class, List.class, Map.class)
                    .invoke(null, classes, Arrays.asList(filters), configuration);
            Set<TestIdentity> result = new TreeSet<>();
            for (String[] row : rows) result.add(new TestIdentity(row[0], row[1], row[2]));
            return result;
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private static Object call(Object instance, String name) throws Exception {
        for (Class<?> type = instance.getClass(); type != null; type = type.getSuperclass()) {
            try {
                var method = type.getDeclaredMethod(name);
                method.setAccessible(true);
                return method.invoke(instance);
            } catch (NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException(instance.getClass().getName() + "." + name);
    }

    private static boolean nonEmpty(Object value) {
        return value != null && (!value.getClass().isArray() || java.lang.reflect.Array.getLength(value) > 0);
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object value) { return value == null ? List.of() : (List<String>) value; }

    private static String rootMessage(Throwable failure) {
        while (failure.getCause() != null) failure = failure.getCause();
        return failure.toString();
    }
}
