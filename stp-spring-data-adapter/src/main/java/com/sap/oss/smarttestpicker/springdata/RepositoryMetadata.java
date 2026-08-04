// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.springdata;

import java.util.List;
import java.util.Objects;

record RepositoryMetadata(String canonicalBeanName, String repositoryInterface, String domainType,
		List<String> aliases, RepositoryMetadataProvenance provenance, String contextIdentity) {
	RepositoryMetadata {
		Objects.requireNonNull(canonicalBeanName, "canonicalBeanName");
		Objects.requireNonNull(repositoryInterface, "repositoryInterface");
		Objects.requireNonNull(domainType, "domainType");
		aliases = List.copyOf(aliases);
		Objects.requireNonNull(provenance, "provenance");
		Objects.requireNonNull(contextIdentity, "contextIdentity");
	}
}
