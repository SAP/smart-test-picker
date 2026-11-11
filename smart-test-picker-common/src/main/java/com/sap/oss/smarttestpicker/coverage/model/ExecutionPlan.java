// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.model;

import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Immutable semantic representation of execution-plan v1. */
public record ExecutionPlan(int version, ExecutionPlanMode mode, Set<ExecutableTestIdentity> tests,
		CoverageMapRevision revision)
{
	public ExecutionPlan
	{
		Objects.requireNonNull(mode, "mode");
		Objects.requireNonNull(tests, "tests");
		if (tests.stream().anyMatch(Objects::isNull))
			throw new IllegalArgumentException("Execution plan contains a null test identity");
		if (mode == ExecutionPlanMode.RUN_ALL && !tests.isEmpty())
			throw new IllegalArgumentException("RUN_ALL execution plan must not contain selected tests");
		tests = Collections.unmodifiableSet(new TreeSet<>(tests));
	}

	public boolean selectsNothing()
	{
		return mode == ExecutionPlanMode.SELECT && tests.isEmpty();
	}

	public Optional<CoverageMapRevision> boundRevision()
	{
		return Optional.ofNullable(revision);
	}
}
