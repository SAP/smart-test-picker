// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.runtime.model;

public sealed interface RuntimeEvent permits MethodHitEvent, SpringBeanEvent, EndpointEvent,
		RepositoryInvocationEvent, EntityEvent, TableEvent, UnattributedEvent {
	Evidence evidence();
}

