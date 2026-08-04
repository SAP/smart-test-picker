// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.springdata;

import com.sap.oss.smarttestpicker.runtime.RuntimeContextRegistry;
import com.sap.oss.smarttestpicker.runtime.model.RepositoryInvocationEvent;

@FunctionalInterface
interface RuntimeEventRecorder {
	RuntimeEventRecorder SHARED_RUNTIME = event -> RuntimeContextRegistry.current().map(service -> {
		service.record(event);
		return true;
	}).orElse(false);

	boolean record(RepositoryInvocationEvent event);
}
