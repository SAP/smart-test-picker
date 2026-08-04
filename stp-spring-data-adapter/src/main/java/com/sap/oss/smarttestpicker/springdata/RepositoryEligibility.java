// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.springdata;

import java.util.List;
import java.util.Objects;

record RepositoryEligibility(String canonicalBeanName, String repositoryInterface, String domainType,
		boolean eligible, RepositoryEligibilityReason reason, RepositoryProxyKind proxyKind,
		List<String> exposedInterfaces, int advisorCount, List<String> advisorTypes, boolean targetSourcePresent,
		String contextIdentity) {
	RepositoryEligibility {
		Objects.requireNonNull(canonicalBeanName, "canonicalBeanName");
		Objects.requireNonNull(repositoryInterface, "repositoryInterface");
		Objects.requireNonNull(domainType, "domainType");
		Objects.requireNonNull(reason, "reason");
		Objects.requireNonNull(proxyKind, "proxyKind");
		exposedInterfaces = List.copyOf(exposedInterfaces);
		advisorTypes = List.copyOf(advisorTypes);
		Objects.requireNonNull(contextIdentity, "contextIdentity");
		if (eligible != (reason == RepositoryEligibilityReason.ELIGIBLE)) {
			throw new IllegalArgumentException("eligible flag and reason disagree");
		}
	}
}
