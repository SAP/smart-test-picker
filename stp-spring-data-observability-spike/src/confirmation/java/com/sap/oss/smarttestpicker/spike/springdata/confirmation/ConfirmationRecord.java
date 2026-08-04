// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.spike.springdata.confirmation;

record ConfirmationRecord(long sequence, String hookName, String phase, String repositoryInterface,
		String declaringClass, String methodName, String jvmDescriptor, String domainType, String resultState,
		String exceptionType, boolean transactionActive, String beanName, String proxyOrAdvisorType) {
}
