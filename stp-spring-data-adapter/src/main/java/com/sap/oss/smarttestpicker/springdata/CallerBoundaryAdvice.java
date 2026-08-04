// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.springdata;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;

import com.sap.oss.smarttestpicker.runtime.model.Certainty;
import com.sap.oss.smarttestpicker.runtime.model.Evidence;
import com.sap.oss.smarttestpicker.runtime.model.EvidenceSource;
import com.sap.oss.smarttestpicker.runtime.model.RepositoryInvocationEvent;
import com.sap.oss.smarttestpicker.runtime.model.RepositoryKind;
import com.sap.oss.smarttestpicker.runtime.model.RepositoryOutcome;

final class CallerBoundaryAdvice implements MethodInterceptor, StpCallerAdvisorMarker {
	private static final Evidence EVIDENCE = new Evidence(EvidenceSource.SPRING_DATA, Certainty.OBSERVED);
	private final RepositoryEligibility repository;
	private final RepositoryAdapterMetrics metrics;
	private final RuntimeEventRecorder recorder;
	private volatile boolean enabled;

	CallerBoundaryAdvice(RepositoryEligibility repository, RepositoryAdapterMetrics metrics) {
		this(repository, metrics, RuntimeEventRecorder.SHARED_RUNTIME);
	}

	CallerBoundaryAdvice(RepositoryEligibility repository, RepositoryAdapterMetrics metrics,
			RuntimeEventRecorder recorder) {
		this.repository = repository;
		this.metrics = metrics;
		this.recorder = recorder;
	}

	boolean isEnabled() {
		return enabled;
	}

	void enableAfterAudit() {
		enabled = true;
	}

	@Override
	public Object invoke(MethodInvocation invocation) throws Throwable {
		if (!enabled) return invocation.proceed();
		metrics.invocationObserved();
		try {
			Object result = invocation.proceed();
			safeRecord(invocation, RepositoryOutcome.SUCCEEDED);
			return result;
		}
		catch (Throwable repositoryFailure) {
			// Repository failures are application behavior and must retain exact identity.
			safeRecord(invocation, RepositoryOutcome.FAILED);
			throw repositoryFailure;
		}
	}

	private void safeRecord(MethodInvocation invocation, RepositoryOutcome outcome) {
		metrics.eventAttempted(outcome == RepositoryOutcome.SUCCEEDED);
		long started = System.nanoTime();
		try {
			RepositoryInvocationEvent event = new RepositoryInvocationEvent(RepositoryKind.SPRING_DATA_PROXY,
					repository.repositoryInterface(), repository.canonicalBeanName(), invocation.getMethod().getName(),
					JvmMethodDescriptors.descriptor(invocation.getMethod()), repository.domainType(), outcome, EVIDENCE);
			if (recorder.record(event)) metrics.eventRecorded();
			else metrics.runtimeUnavailable();
		}
		catch (Throwable recordingFailure) {
			// Observation is best-effort at this boundary. This deliberately isolates
			// every recording failure, including serious JVM errors, from the repository.
			metrics.recordingFailed();
		}
		finally {
			metrics.recordingNanos(System.nanoTime() - started);
		}
	}
}
