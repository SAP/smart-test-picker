// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.model;

import java.util.Set;
import java.util.TreeSet;

public record SetupScope(String id, SetupScopeType type, Set<String> coveredClasses,
		Set<TestContainer> affectedContainers)
{
	public SetupScope
	{
		if (id == null || id.isBlank()) throw new IllegalArgumentException("scope id must not be blank");
		if (type == null) throw new IllegalArgumentException("scope type must not be null");
		coveredClasses = Set.copyOf(new TreeSet<>(coveredClasses));
		affectedContainers = Set.copyOf(new TreeSet<>(affectedContainers));
		if (affectedContainers.isEmpty()) throw new IllegalArgumentException("setup scope must affect at least one container");
	}
}
