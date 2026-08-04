// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.springdata;

import org.springframework.beans.factory.DisposableBean;

import java.util.concurrent.atomic.LongAdder;

final class RepositoryAdapterMetrics implements DisposableBean {
	private final LongAdder invocationsObserved = new LongAdder();
	private final LongAdder successfulEventsAttempted = new LongAdder();
	private final LongAdder failedEventsAttempted = new LongAdder();
	private final LongAdder eventsRecorded = new LongAdder();
	private final LongAdder recordingFailures = new LongAdder();
	private final LongAdder recordingNanos = new LongAdder();
	private final LongAdder runtimeUnavailable = new LongAdder();

	void invocationObserved() { invocationsObserved.increment(); }
	void eventAttempted(boolean successful) {
		if (successful) successfulEventsAttempted.increment(); else failedEventsAttempted.increment();
	}
	void eventRecorded() { eventsRecorded.increment(); }
	void recordingFailed() { recordingFailures.increment(); }
	void recordingNanos(long nanos) { recordingNanos.add(nanos); }
	void runtimeUnavailable() { runtimeUnavailable.increment(); }

	Snapshot snapshot() {
		return new Snapshot(invocationsObserved.sum(), successfulEventsAttempted.sum(), failedEventsAttempted.sum(),
				eventsRecorded.sum(), recordingFailures.sum(), recordingNanos.sum(), runtimeUnavailable.sum());
	}

	@Override
	public void destroy() {
		invocationsObserved.reset();
		successfulEventsAttempted.reset();
		failedEventsAttempted.reset();
		eventsRecorded.reset();
		recordingFailures.reset();
		recordingNanos.reset();
		runtimeUnavailable.reset();
	}

	record Snapshot(long invocationsObserved, long successfulEventsAttempted, long failedEventsAttempted,
			long eventsRecorded, long recordingFailures, long recordingNanos, long runtimeUnavailable) {
	}
}
