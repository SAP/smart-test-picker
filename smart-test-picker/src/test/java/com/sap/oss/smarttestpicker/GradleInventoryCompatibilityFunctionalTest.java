// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** No real distribution is broken: hide one API at the isolated adapter boundary instead. */
class GradleInventoryCompatibilityFunctionalTest {
    @TempDir Path root;
    static Stream<Arguments> missingApis() {
        return Stream.of(
                Arguments.of("8.14", "org.gradle.api.internal.tasks.testing.filter.TestSelectionMatcher", false),
                Arguments.of("8.14", "org.gradle.api.internal.classpath.ModuleRegistry", false),
                Arguments.of("9.9.1", "org/gradle/api/internal/tasks/testing/junitplatform/filters/ClassMethodNameFilter.class", true));
    }

    @ParameterizedTest(name = "{0} missing {1}") @MethodSource("missingApis")
    void incompatibleInternalApiFailsWithTaskAndSupportedVersions(String version, String hidden, boolean resource) throws Exception {
        fixture(hidden, resource);
        var result = runner(version).buildAndFail();
        for (String expected : new String[] {":test", "Gradle " + version, "tested", "8.14", "9.5.1", "9.7.1", "9.9.1"})
            assertTrue(result.getOutput().contains(expected), result.getOutput());
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings = {"8.14", "9.9.1"})
    void intactLoaderCanCaptureRuntimeAndMatchSelection(String version) throws Exception {
        fixture("nothing.hidden", false);
        assertTrue(runner(version).build().getOutput().contains("INTACT_ADAPTER_CONTROL_OK"));
    }

    private GradleRunner runner(String version) {
        return GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath().withGradleVersion(version)
                .withArguments("probeCompatibility", "--stacktrace", "--console=plain");
    }

    private void fixture(String hidden, boolean resource) throws Exception {
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'compatibility-probe'\n");
        Files.writeString(root.resolve("build.gradle"), """
                import com.sap.oss.smarttestpicker.GradleTestSelection
                import org.gradle.api.tasks.testing.Test
                plugins { id 'java'; id 'com.sap.oss.smart-test-picker' }
                test { useJUnitPlatform() }
                @groovy.transform.CompileStatic
                class IsolatedAdapter extends ClassLoader {
                    String hidden
                    boolean resource
                    IsolatedAdapter(ClassLoader parent, String hidden, boolean resource) {
                        super(parent); this.hidden = hidden; this.resource = resource
                    }
                    protected Class<?> loadClass(String name, boolean resolve) {
                        if (!resource && name == hidden) throw new ClassNotFoundException(name)
                        if (name == 'com.sap.oss.smarttestpicker.GradleInventoryMatcher'
                                || name == 'com.sap.oss.smarttestpicker.GradleTestSelection') {
                            def found = findLoadedClass(name)
                            if (found != null) return found
                            byte[] bytes = parent.getResourceAsStream(name.replace('.', '/') + '.class').bytes
                            return defineClass(name, bytes, 0, bytes.length)
                        }
                        return super.loadClass(name, resolve)
                    }
                    URL getResource(String name) {
                        if (resource && name == hidden) return null
                        return super.getResource(name)
                    }
                }
                tasks.register('probeCompatibility') {
                    doLast {
                        def loader = new IsolatedAdapter(GradleTestSelection.class.classLoader, '%s', %s)
                        def boundary = loader.loadClass('com.sap.oss.smarttestpicker.GradleTestSelection')
                        def capture = boundary.getDeclaredMethod('capture', Test)
                        capture.accessible = true
                        capture.invoke(null, tasks.test)
                        def adapter = loader.loadClass('com.sap.oss.smarttestpicker.GradleInventoryMatcher')
                        def constructor = adapter.getDeclaredConstructor(Set, Set)
                        constructor.accessible = true
                        def matcher = constructor.newInstance(['example.SampleTest.selected'] as Set, [] as Set)
                        def matches = adapter.getDeclaredMethod('matchesTest', String, String)
                        matches.accessible = true
                        assert matches.invoke(matcher, 'example.SampleTest', 'selected')
                        assert !matches.invoke(matcher, 'example.SampleTest', 'sibling')
                        def ancestors = adapter.getDeclaredMethod('matchesAncestorMethods')
                        ancestors.accessible = true
                        assert ancestors.invoke(null) == (org.gradle.util.GradleVersion.current().baseVersion >= org.gradle.util.GradleVersion.version('9.0'))
                        println 'INTACT_ADAPTER_CONTROL_OK'
                    }
                }
                """.formatted(hidden, resource));
    }
}
