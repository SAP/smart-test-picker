// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.runtime.model;

import java.util.Objects;

public record RepositoryInvocationEvent(RepositoryKind repositoryKind, String repositoryInterface, String beanName,
		String methodName, String jvmDescriptor, String domainType, RepositoryOutcome outcome, Evidence evidence)
		implements RuntimeEvent {
	public RepositoryInvocationEvent {
		Objects.requireNonNull(repositoryKind, "repositoryKind");
		Objects.requireNonNull(repositoryInterface, "repositoryInterface");
		Objects.requireNonNull(beanName, "beanName");
		Objects.requireNonNull(methodName, "methodName");
		Objects.requireNonNull(jvmDescriptor, "jvmDescriptor");
		Objects.requireNonNull(domainType, "domainType");
		Objects.requireNonNull(outcome, "outcome");
		Objects.requireNonNull(evidence, "evidence");
		if (!jvmDescriptor.startsWith("(")) {
			throw new IllegalArgumentException("jvmDescriptor must be a JVM method descriptor");
		}
	}

	public String canonicalMethodKey() {
		return repositoryInterface + "#" + methodName + jvmDescriptor;
	}
}
