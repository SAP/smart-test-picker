// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.model;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Schema-v3 selection output. Executable ownership is authoritative. */
public record ExecutableSelectionResult(CoverageMapRevision revision, Set<ExecutableTestIdentity> selectedTests)
{
	public ExecutableSelectionResult
	{
		Objects.requireNonNull(revision, "revision");
		Objects.requireNonNull(selectedTests, "selectedTests");
		if (selectedTests.stream().anyMatch(Objects::isNull))
			throw new IllegalArgumentException("selection contains null executable identity");
		selectedTests = Collections.unmodifiableSet(new TreeSet<>(selectedTests));
	}
}
