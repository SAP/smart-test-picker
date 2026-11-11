// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;

class MavenSurefireTestFilterTest {
	private static final TestIdentity SERVER = TestIdentity.parse("org.sonar.java.it.JavaRulingTest#sonarqube_server");
	private static final TestIdentity VIBEBOT = TestIdentity.parse("org.sonar.java.it.JavaRulingTest#vibebot");
	private static final TestIdentity GUAVA = TestIdentity.parse("org.sonar.java.it.JavaRulingTest#guava(java.lang.String)");

	@Test void appliesRulingProfileIncludeAndExcludeExpressionsToDeclaredIdentities() {
		var without = MavenSurefireTestFilter.parse("!org.sonar.java.it.JavaRulingTest#sonarqube_server, !org.sonar.java.it.JavaRulingTest#vibebot");
		assertFalse(without.test(SERVER));
		assertFalse(without.test(VIBEBOT));
		assertTrue(without.test(GUAVA));
		var only = MavenSurefireTestFilter.parse("org.sonar.java.it.JavaRulingTest#sonarqube_server");
		assertTrue(only.test(SERVER));
		assertFalse(only.test(VIBEBOT));
		assertFalse(only.test(GUAVA));
	}

	@Test void appliesAnExplicitClassOrMethodExecutionFilter() {
		var sanity = MavenSurefireTestFilter.parse("org.sonar.java.SanityTest");
		assertTrue(sanity.test(TestIdentity.parse("org.sonar.java.SanityTest#verify")));
		assertFalse(sanity.test(SERVER));
		var vibebot = MavenSurefireTestFilter.parse("org.sonar.java.it.JavaRulingTest#vibebot");
		assertTrue(vibebot.test(VIBEBOT));
		assertFalse(vibebot.test(GUAVA));
	}

	@Test void appliesEffectiveSurefireIncludesAndExcludes() {
		MavenProject project = new MavenProject();
		Plugin plugin = new Plugin();
		plugin.setGroupId("org.apache.maven.plugins");
		plugin.setArtifactId("maven-surefire-plugin");
		Xpp3Dom configuration = new Xpp3Dom("configuration");
		Xpp3Dom includes = new Xpp3Dom("includes");
		includes.addChild(value("include", "org/sonar/java/**/*.java"));
		configuration.addChild(includes);
		Xpp3Dom excludes = new Xpp3Dom("excludes");
		excludes.addChild(value("exclude", "org/sonar/java/it/**"));
		configuration.addChild(excludes);
		plugin.setConfiguration(configuration);
		project.getBuild().addPlugin(plugin);
		var filter = MavenSurefireTestFilter.from(project);
		assertTrue(filter.test(TestIdentity.parse("org.sonar.java.SanityTest#verify")));
		assertFalse(filter.test(SERVER));
	}

	private static Xpp3Dom value(String name, String value) {
		Xpp3Dom result = new Xpp3Dom(name);
		result.setValue(value);
		return result;
	}
}
