// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import java.io.File;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.gradle.api.internal.classpath.ModuleRegistry;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.internal.tasks.testing.filter.TestFilterSpec;
import org.gradle.api.internal.tasks.testing.filter.TestSelectionMatcher;
import org.gradle.api.tasks.testing.Test;
import org.gradle.util.GradleVersion;

/**
 * The single inventory boundary to Gradle internals. Public TestFilter exposes patterns,
 * but not their matching semantics (notably simple names and class prefiltering).
 * The distribution-module snapshot also mirrors Gradle 8's worker runtime augmentation;
 * Gradle 9 removed that augmentation. Only immutable inputs leave this boundary.
 * Differential execution tests cover this adapter on Gradle 8.14, 9.5.1 and 9.7.1.
 */
final class GradleInventoryMatcher {
    private final TestSelectionMatcher matcher;

    GradleInventoryMatcher(Set<String> includes, Set<String> excludes) {
        matcher = new TestSelectionMatcher(new TestFilterSpec(includes, excludes, Set.of()));
    }

    boolean mayIncludeClass(String name) { return matcher.mayIncludeClass(name); }
    boolean matchesTest(String name, String method) { return matcher.matchesTest(name, method); }

    static boolean matchesAncestorMethods() {
        // Gradle 9's extracted platform filter retains the method while walking enclosing classes.
        return GradleInventoryMatcher.class.getClassLoader().getResource(
                "org/gradle/api/internal/tasks/testing/junitplatform/filters/ClassMethodNameFilter.class") != null;
    }

    static void captureFrameworkRuntime(Test task, GradleTestSelection selection) {
        selection.getDistributionModules().set(java.util.Map.of());
        if (GradleVersion.current().getBaseVersion().compareTo(GradleVersion.version("9.0")) >= 0
                || !selection.getPlatform().get()) {
            return;
        }
        var framework = task.getTestFramework();
        if (!framework.getUseDistributionDependencies()) {
            return;
        }
        var registry = ((ProjectInternal) task.getProject()).getServices().get(ModuleRegistry.class);
        var modules = new LinkedHashMap<String, String>();
        var distribution = new ArrayList<File>();
        var applicationModules = new ArrayList<>(framework.getWorkerApplicationClasspathModules());
        applicationModules.addAll(framework.getWorkerApplicationModulepathModules());
        for (var module : applicationModules) {
            modules.put(module.getExampleClassName(), module.getJarFilePattern().pattern());
            distribution.addAll(registry.getExternalModule(module.getModuleName())
                    .getImplementationClasspath().getAsFiles());
        }
        selection.getDistributionModules().set(modules);
        selection.getDistributionClasspath().from(distribution);
    }

    static List<Path> runtimeClasspath(GradleTestSelection selection) throws IOException {
        var files = new ArrayList<>(selection.getRuntimeClasspath().getFiles());
        var modules = selection.getDistributionModules().get();
        var urls = new java.net.URL[files.size()];
        for (int i = 0; i < files.size(); i++) {
            urls[i] = files.get(i).toURI().toURL();
        }
        try (var loader = new URLClassLoader(urls, null)) {
            boolean missing = modules.entrySet().stream().anyMatch(module -> {
                var pattern = Pattern.compile(module.getValue());
                return files.stream().noneMatch(file -> pattern.matcher(file.getName()).matches())
                        && loader.findResource(module.getKey().replace('.', '/') + ".class") == null;
            });
            // ForkedTestClasspathFactory appends the whole framework set if any module is missing,
            // not just the missing module, preserving the target classpath's precedence.
            if (missing) {
                files.addAll(selection.getDistributionClasspath().getFiles());
            }
        }
        return files.stream().map(File::toPath).toList();
    }
}
