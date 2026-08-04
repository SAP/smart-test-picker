// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.spike.springdata.confirmation;

import org.springframework.core.Ordered;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

public final class RepositoryLifecycleTestExecutionListener implements TestExecutionListener, Ordered {
	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}

	@Override
	public void prepareTestInstance(TestContext testContext) {
		String mode = testContext.getApplicationContext().getEnvironment().getProperty("stp.confirmation.mode", "");
		if (!mode.startsWith("lifecycle-")) return;
		testContext.getApplicationContext().getBean(RepositoryLifecycleRecorder.class)
				.captureAll("TEST_CONTEXT_PREPARE_TEST_INSTANCE");
	}
}
