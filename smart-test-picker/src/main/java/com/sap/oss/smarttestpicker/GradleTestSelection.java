// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.testing.Test;
import org.gradle.api.tasks.testing.junitplatform.JUnitPlatformOptions;
import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;
import com.sap.oss.smarttestpicker.selector.JUnitHeadTestInventoryGenerator;

/** Serializable selection inputs captured before assignment routing changes the Test filter. */
public abstract class GradleTestSelection {
    @Input public abstract Property<String> getTaskPath();
    @Input public abstract Property<Boolean> getPlatform();
    @Input public abstract SetProperty<String> getIncludes();
    @Input public abstract SetProperty<String> getExcludes();
    @Input public abstract SetProperty<String> getIncludeTags();
    @Input public abstract SetProperty<String> getExcludeTags();
    @Input public abstract SetProperty<String> getIncludeEngines();
    @Input public abstract SetProperty<String> getExcludeEngines();
    @Classpath public abstract ConfigurableFileCollection getRuntimeClasspath();
    @Classpath public abstract ConfigurableFileCollection getTestClasses();
    @Input public abstract MapProperty<String, String> getDistributionModules();
    @Classpath public abstract ConfigurableFileCollection getDistributionClasspath();
    @InputFiles @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getCandidates();

    static GradleTestSelection capture(Test task) {
        var selection = task.getProject().getObjects().newInstance(GradleTestSelection.class);
        selection.getTaskPath().set(task.getPath());
        selection.getPlatform().set(task.getOptions() instanceof JUnitPlatformOptions);
        GradleInventoryMatcher.captureFrameworkRuntime(task, selection);
        selection.getIncludes().set(Set.copyOf(task.getFilter().getIncludePatterns()));
        selection.getExcludes().set(Set.copyOf(task.getFilter().getExcludePatterns()));
        selection.getRuntimeClasspath().from(task.getClasspath());
        selection.getTestClasses().from(task.getTestClassesDirs());
        // Snapshot the build's file patterns before assignment routing adds a class boundary.
        Set<String> fileIncludes = Set.copyOf(task.getIncludes());
        Set<String> fileExcludes = Set.copyOf(task.getExcludes());
        selection.getCandidates().from(task.getTestClassesDirs().getAsFileTree().matching(patterns -> {
            patterns.setIncludes(fileIncludes);
            patterns.setExcludes(fileExcludes);
        }));
        selection.getIncludeTags().set(Set.of()); selection.getExcludeTags().set(Set.of());
        selection.getIncludeEngines().set(Set.of()); selection.getExcludeEngines().set(Set.of());
        if (task.getOptions() instanceof JUnitPlatformOptions options) {
            selection.getIncludeTags().set(Set.copyOf(options.getIncludeTags()));
            selection.getExcludeTags().set(Set.copyOf(options.getExcludeTags()));
            selection.getIncludeEngines().set(Set.copyOf(options.getIncludeEngines()));
            selection.getExcludeEngines().set(Set.copyOf(options.getExcludeEngines()));
        }
        return selection;
    }

    Set<TestIdentity> discover() {
        if (!getPlatform().get()) throw new GradleException("Gradle inventory task " + getTaskPath().get()
                + " must use JUnit Platform; other test frameworks are unsupported");
        var matcher = new GradleInventoryMatcher(getIncludes().get(), getExcludes().get());
        Set<String> classes = new TreeSet<>();
        for (File file : getCandidates().getFiles()) {
            if (!file.getName().endsWith(".class") || file.getName().equals("module-info.class")) continue;
            for (File root : getTestClasses().getFiles()) {
                if (!file.toPath().startsWith(root.toPath())) continue;
                String relative = root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '.');
                String name = relative.substring(0, relative.length() - ".class".length());
                if (!name.matches(".*\\$\\d+") && matcher.mayIncludeClass(name)) classes.add(name);
            }
        }
        var filters = Map.of("includeTags", List.copyOf(getIncludeTags().get()),
                "excludeTags", List.copyOf(getExcludeTags().get()),
                "includeEngines", List.copyOf(getIncludeEngines().get()),
                "excludeEngines", List.copyOf(getExcludeEngines().get()),
                "matchAncestorMethods", List.of(Boolean.toString(GradleInventoryMatcher.matchesAncestorMethods())));
        try {
            return new JUnitHeadTestInventoryGenerator().generateSelected(
                    GradleInventoryMatcher.runtimeClasspath(this),
                    getTestClasses().getFiles().stream().map(File::toPath).toList(),
                    List.copyOf(classes), filters, matcher::matchesTest).runnableTests();
        } catch (Exception failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof JUnitHeadTestInventoryGenerator.UnloadableTestClassException unloadable) {
                    throw new GradleException("Gradle inventory " + getTaskPath().get()
                            + " cannot load test class " + unloadable.className(), failure);
                }
            }
            throw new GradleException("Gradle inventory " + getTaskPath().get() + " discovery failed", failure);
        }
    }
}
