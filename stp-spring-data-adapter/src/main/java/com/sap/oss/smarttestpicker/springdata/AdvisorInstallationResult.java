// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.springdata;

import java.util.List;
import java.util.Objects;

record AdvisorInstallationResult(String canonicalBeanName, String repositoryInterface, String domainType,
		AdvisorInstallationState state, AdvisorAuditReason reason, int advisorIndex,
		List<Integer> cacheAdvisorPositionsBefore, List<Integer> cacheAdvisorPositionsAfter,
		boolean beanIdentityMatches, boolean proxyKindMatches, boolean interfacesMatch,
		boolean targetSourceMatches, boolean advisorOrderMatches, boolean proxyLayerCountMatches) {
	AdvisorInstallationResult {
		Objects.requireNonNull(canonicalBeanName, "canonicalBeanName");
		Objects.requireNonNull(repositoryInterface, "repositoryInterface");
		Objects.requireNonNull(domainType, "domainType");
		Objects.requireNonNull(state, "state");
		Objects.requireNonNull(reason, "reason");
		cacheAdvisorPositionsBefore = List.copyOf(cacheAdvisorPositionsBefore);
		cacheAdvisorPositionsAfter = List.copyOf(cacheAdvisorPositionsAfter);
	}
}
