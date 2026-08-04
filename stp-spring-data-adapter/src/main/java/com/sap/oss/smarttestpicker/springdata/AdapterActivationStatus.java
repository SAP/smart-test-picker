// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.springdata;

record AdapterActivationStatus(State state) {
	enum State {
		ACTIVE,
		DISABLED_NO_RUNTIME,
		DISABLED_NO_SPRING_DATA
	}
}
