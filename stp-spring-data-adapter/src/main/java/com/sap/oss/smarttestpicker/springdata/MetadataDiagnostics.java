// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.springdata;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

final class MetadataDiagnostics {
	static final int SAMPLE_LIMIT = 20;
	private final Map<Enum<?>, Long> counts = new java.util.HashMap<>();
	private final Map<Enum<?>, TreeSet<String>> samples = new java.util.HashMap<>();

	synchronized void record(MetadataDiagnosticReason reason, String canonicalBeanName) {
		recordReason(reason, canonicalBeanName);
	}

	synchronized void record(RepositoryEligibilityReason reason, String canonicalBeanName) {
		recordReason(reason, canonicalBeanName);
	}

	synchronized void record(AdvisorAuditReason reason, String canonicalBeanName) {
		recordReason(reason, canonicalBeanName);
	}

	private void recordReason(Enum<?> reason, String canonicalBeanName) {
		counts.merge(reason, 1L, Long::sum);
		if (canonicalBeanName == null || canonicalBeanName.isBlank()) return;
		TreeSet<String> values = samples.computeIfAbsent(reason, ignored -> new TreeSet<>());
		values.add(canonicalBeanName);
		if (values.size() > SAMPLE_LIMIT) values.pollLast();
	}

	synchronized long count(RepositoryEligibilityReason reason) {
		return counts.getOrDefault(reason, 0L);
	}

	synchronized List<EligibilityDiagnosticSnapshot> eligibilitySnapshots() {
		List<EligibilityDiagnosticSnapshot> result = new ArrayList<>();
		for (RepositoryEligibilityReason reason : RepositoryEligibilityReason.values()) {
			long count = counts.getOrDefault(reason, 0L);
			if (count > 0) {
				result.add(new EligibilityDiagnosticSnapshot(reason, count,
						List.copyOf(samples.getOrDefault(reason, new TreeSet<>()))));
			}
		}
		return List.copyOf(result);
	}

	synchronized List<AuditDiagnosticSnapshot> auditSnapshots() {
		List<AuditDiagnosticSnapshot> result = new ArrayList<>();
		for (AdvisorAuditReason reason : AdvisorAuditReason.values()) {
			long count = counts.getOrDefault(reason, 0L);
			if (count > 0) {
				result.add(new AuditDiagnosticSnapshot(reason, count,
						List.copyOf(samples.getOrDefault(reason, new TreeSet<>()))));
			}
		}
		return List.copyOf(result);
	}

	synchronized List<DiagnosticSnapshot> snapshots() {
		List<DiagnosticSnapshot> result = new ArrayList<>();
		for (MetadataDiagnosticReason reason : MetadataDiagnosticReason.values()) {
			long count = counts.getOrDefault(reason, 0L);
			if (count > 0) {
				result.add(new DiagnosticSnapshot(reason, count,
						List.copyOf(samples.getOrDefault(reason, new TreeSet<>()))));
			}
		}
		return List.copyOf(result);
	}

	record DiagnosticSnapshot(MetadataDiagnosticReason reason, long count, List<String> canonicalBeanNames) {
		DiagnosticSnapshot {
			canonicalBeanNames = List.copyOf(canonicalBeanNames);
		}
	}

	record EligibilityDiagnosticSnapshot(RepositoryEligibilityReason reason, long count,
			List<String> canonicalBeanNames) {
		EligibilityDiagnosticSnapshot {
			canonicalBeanNames = List.copyOf(canonicalBeanNames);
		}
	}

	record AuditDiagnosticSnapshot(AdvisorAuditReason reason, long count, List<String> canonicalBeanNames) {
		AuditDiagnosticSnapshot {
			canonicalBeanNames = List.copyOf(canonicalBeanNames);
		}
	}
}
