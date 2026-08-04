// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.runtime.model;

import java.util.Objects;

public record SpringBeanEvent(String beanName, String binaryClassName, Evidence evidence) implements RuntimeEvent {
	public SpringBeanEvent {
		Objects.requireNonNull(beanName, "beanName");
		Objects.requireNonNull(binaryClassName, "binaryClassName");
		Objects.requireNonNull(evidence, "evidence");
	}
}

