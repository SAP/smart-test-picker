// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.springdata;

import org.aopalliance.aop.Advice;
import org.springframework.aop.Pointcut;
import org.springframework.aop.PointcutAdvisor;

final class StpCallerAdvisor implements PointcutAdvisor, StpCallerAdvisorMarker {
	private final CallerBoundaryAdvice advice;

	StpCallerAdvisor(RepositoryEligibility repository, RepositoryAdapterMetrics metrics) {
		this.advice = new CallerBoundaryAdvice(repository, metrics);
	}

	CallerBoundaryAdvice callerAdvice() {
		return advice;
	}

	@Override
	public Pointcut getPointcut() {
		return Pointcut.TRUE;
	}

	@Override
	public Advice getAdvice() {
		return advice;
	}

	@Override
	public boolean isPerInstance() {
		return true;
	}
}
