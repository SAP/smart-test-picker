// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.testing.Test;

/** Dedicated STP mapping JVM; normal {@code test} and {@code smartTest} remain collector-free. */
public abstract class StpCoverageTest extends Test {
	@Input
	public abstract Property<CoverageCollectorType> getCoverageCollector();

	@Input
	public abstract Property<String> getCoverageRevision();

	@Input
	public abstract Property<String> getCoverageShardId();

	@Input
	@Optional
	public abstract Property<String> getAgentConfigurationVersion();
}
