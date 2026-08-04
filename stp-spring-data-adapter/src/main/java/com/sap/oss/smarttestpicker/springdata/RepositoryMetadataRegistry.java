// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.springdata;

import org.springframework.beans.factory.DisposableBean;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

final class RepositoryMetadataRegistry implements DisposableBean {
	private final String contextIdentity;
	private final MetadataDiagnostics diagnostics;
	private final Map<String, RepositoryMetadata> byCanonicalName = new TreeMap<>();
	private final Map<String, String> aliasOwners = new HashMap<>();
	private final TreeSet<String> ambiguousCanonicalNames = new TreeSet<>();

	RepositoryMetadataRegistry(String contextIdentity, MetadataDiagnostics diagnostics) {
		this.contextIdentity = contextIdentity;
		this.diagnostics = diagnostics;
	}

	synchronized boolean register(RepositoryMetadata metadata) {
		if (!contextIdentity.equals(metadata.contextIdentity())) {
			ambiguousCanonicalNames.add(metadata.canonicalBeanName());
			diagnostics.record(MetadataDiagnosticReason.CONFLICTING_METADATA, metadata.canonicalBeanName());
			return false;
		}
		RepositoryMetadata existing = byCanonicalName.get(metadata.canonicalBeanName());
		if (existing != null) {
			if (existing.equals(metadata)) {
				diagnostics.record(MetadataDiagnosticReason.DUPLICATE_EQUAL_METADATA,
						metadata.canonicalBeanName());
				return true;
			}
			ambiguousCanonicalNames.add(metadata.canonicalBeanName());
			diagnostics.record(MetadataDiagnosticReason.CONFLICTING_METADATA, metadata.canonicalBeanName());
			return false;
		}
		String canonicalAliasOwner = aliasOwners.get(metadata.canonicalBeanName());
		if (canonicalAliasOwner != null && !canonicalAliasOwner.equals(metadata.canonicalBeanName())) {
			ambiguousCanonicalNames.add(metadata.canonicalBeanName());
			diagnostics.record(MetadataDiagnosticReason.ALIAS_CONFLICT, metadata.canonicalBeanName());
			return false;
		}
		for (String alias : metadata.aliases()) {
			String owner = aliasOwners.get(alias);
			if ((owner != null && !owner.equals(metadata.canonicalBeanName()))
					|| (byCanonicalName.containsKey(alias) && !alias.equals(metadata.canonicalBeanName()))) {
				ambiguousCanonicalNames.add(metadata.canonicalBeanName());
				diagnostics.record(MetadataDiagnosticReason.ALIAS_CONFLICT, metadata.canonicalBeanName());
				return false;
			}
		}
		byCanonicalName.put(metadata.canonicalBeanName(), metadata);
		metadata.aliases().forEach(alias -> aliasOwners.put(alias, metadata.canonicalBeanName()));
		return true;
	}

	synchronized Optional<RepositoryMetadata> find(String beanNameOrAlias) {
		String canonicalName = aliasOwners.getOrDefault(beanNameOrAlias, beanNameOrAlias);
		return Optional.ofNullable(byCanonicalName.get(canonicalName));
	}

	synchronized List<RepositoryMetadata> entries() {
		return List.copyOf(new ArrayList<>(byCanonicalName.values()));
	}

	synchronized boolean isAmbiguous(String canonicalBeanName) {
		return ambiguousCanonicalNames.contains(canonicalBeanName);
	}

	@Override
	public synchronized void destroy() {
		byCanonicalName.clear();
		aliasOwners.clear();
		ambiguousCanonicalNames.clear();
	}
}
