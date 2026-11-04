// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.Properties;
import com.sap.oss.smarttestpicker.StpCollectorSelection;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;

class StpMavenCollectorSelectionTest
{
	@Test void resolvesUserSelectionBeforeProjectSelectionAndKeepsLegacyDefault()
	{
		var project = new MavenProject();
		var user = new Properties();
		assertEquals(StpCollectorSelection.JACOCO, StpMavenLifecycleParticipant.selectedCollector(project, user));
		project.getProperties().setProperty(StpCollectorSelection.PROPERTY, "ASM");
		assertEquals(StpCollectorSelection.ASM, StpMavenLifecycleParticipant.selectedCollector(project, user));
		user.setProperty(StpCollectorSelection.PROPERTY, "JACOCO");
		assertEquals(StpCollectorSelection.JACOCO, StpMavenLifecycleParticipant.selectedCollector(project, user));
		user.setProperty(StpCollectorSelection.PROPERTY, "both");
		assertThrows(IllegalArgumentException.class, () -> StpMavenLifecycleParticipant.selectedCollector(project, user));
	}

	@Test void bootstrapIncludesOnlyTheSelectedCollectorRuntimeAndTransportsItsSelection() throws Exception
	{
		assertTrue(StpMavenLifecycleParticipant.runtimeClasspath(StpCollectorSelection.ASM).isEmpty());
		assertFalse(StpMavenLifecycleParticipant.runtimeClasspath(StpCollectorSelection.JACOCO).isEmpty());
		for (var collector : StpCollectorSelection.values()) {
			var configuration = new Xpp3Dom("configuration");
			StpMavenLifecycleParticipant.configureRuntime(configuration, Path.of("target/stp/evidence"),
					"maven:module", collector);
			assertEquals(collector.name(), configuration.getChild("systemPropertyVariables")
					.getChild(StpCollectorSelection.PROPERTY).getValue());
			assertEquals(collector == StpCollectorSelection.ASM ? 0 : 1,
					configuration.getChild("additionalClasspathElements").getChildCount());
		}
	}
}
