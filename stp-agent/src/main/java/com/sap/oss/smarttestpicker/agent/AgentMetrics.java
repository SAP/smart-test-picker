// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.agent;

import java.util.concurrent.atomic.LongAdder;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class AgentMetrics {
	private final long agentStartNanos;
	private final LongAdder classesSeen = new LongAdder();
	private final LongAdder classesIncluded = new LongAdder();
	private final LongAdder classesExcluded = new LongAdder();
	private final LongAdder classesIgnored = new LongAdder();
	private final LongAdder transformationErrors = new LongAdder();
	private final LongAdder transformerTotalNanos = new LongAdder();
	private final LongAdder classLoaderPresent = new LongAdder();
	private final LongAdder classLoaderMissing = new LongAdder();
	private final LongAdder protectionDomainPresent = new LongAdder();
	private final LongAdder protectionDomainMissing = new LongAdder();
	private final LongAdder classesTransformed = new LongAdder();
	private final LongAdder methodsConsidered = new LongAdder();
	private final LongAdder methodsInstrumented = new LongAdder();
	private final LongAdder methodsSkipped = new LongAdder();
	private final LongAdder alreadyInstrumentedClasses = new LongAdder();
	private final LongAdder methodIdCollisions = new LongAdder();
	private final LongAdder rawMethodHits = new LongAdder();
	private final LongAdder runtimeRecordingNanos = new LongAdder();
	private final Set<Long> uniqueMethodHits = ConcurrentHashMap.newKeySet();

	public AgentMetrics(long agentStartNanos) {
		this.agentStartNanos = agentStartNanos;
	}

	void seen(boolean loaderPresent, boolean domainPresent) {
		classesSeen.increment();
		(loaderPresent ? classLoaderPresent : classLoaderMissing).increment();
		(domainPresent ? protectionDomainPresent : protectionDomainMissing).increment();
	}

	void included() { classesIncluded.increment(); }
	void excluded() { classesExcluded.increment(); }
	void ignored() { classesIgnored.increment(); }
	void error() { transformationErrors.increment(); }
	void addNanos(long nanos) { transformerTotalNanos.add(nanos); }
	void classTransformed() { classesTransformed.increment(); }
	void methodConsidered() { methodsConsidered.increment(); }
	void methodInstrumented() { methodsInstrumented.increment(); }
	void methodSkipped() { methodsSkipped.increment(); }
	void alreadyInstrumented() { alreadyInstrumentedClasses.increment(); }
	void methodIdCollision() { methodIdCollisions.increment(); }
	void methodHit(long methodId) {
		rawMethodHits.increment();
		uniqueMethodHits.add(methodId);
	}
	void addRuntimeRecordingNanos(long nanos) { runtimeRecordingNanos.add(nanos); }

	public Snapshot snapshot() {
		return new Snapshot(classesSeen.sum(), classesIncluded.sum(), classesExcluded.sum(), classesIgnored.sum(),
				transformationErrors.sum(), transformerTotalNanos.sum(), agentStartNanos, classLoaderPresent.sum(),
				classLoaderMissing.sum(), protectionDomainPresent.sum(), protectionDomainMissing.sum(),
				classesTransformed.sum(), methodsConsidered.sum(), methodsInstrumented.sum(), methodsSkipped.sum(),
				alreadyInstrumentedClasses.sum(), methodIdCollisions.sum(), rawMethodHits.sum(), uniqueMethodHits.size(),
				runtimeRecordingNanos.sum());
	}

	public record Snapshot(long classesSeen, long classesIncluded, long classesExcluded, long classesIgnored,
			long transformationErrors, long transformerTotalNanos, long agentStartNanos, long classLoaderPresent,
			long classLoaderMissing, long protectionDomainPresent, long protectionDomainMissing,
			long classesTransformed, long methodsConsidered, long methodsInstrumented, long methodsSkipped,
			long alreadyInstrumentedClasses, long methodIdCollisions, long rawMethodHits, long uniqueMethodHits,
			long runtimeRecordingNanos) {
	}
}
