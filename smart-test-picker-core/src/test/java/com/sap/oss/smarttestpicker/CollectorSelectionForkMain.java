// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/** Runs in a fresh JVM without any Java agent; core's two SPI services remain on its classpath. */
public final class CollectorSelectionForkMain
{
	public static void main(String[] args)
	{
		var summary = new SummaryGeneratingListener();
		LauncherFactory.create().execute(LauncherDiscoveryRequestBuilder.request()
				.configurationParameter("junit.jupiter.extensions.autodetection.enabled", "true")
				.selectors(selectClass(Probe.class)).build(), summary);
		if (summary.getSummary().getTestsSucceededCount() != 1)
			throw new AssertionError("Probe did not execute successfully");
	}

	@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "stp.probe.marker", matches = ".+")
	static class Probe
	{
		@Test void executesOnlyInProbeFork() throws Exception
		{
			Files.writeString(Path.of(System.getProperty("stp.probe.marker")), "executed");
		}
	}
}
