// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.runtime;

import com.sap.oss.smarttestpicker.runtime.model.EndpointEvent;
import com.sap.oss.smarttestpicker.runtime.model.EntityEvent;
import com.sap.oss.smarttestpicker.runtime.model.MethodHitEvent;
import com.sap.oss.smarttestpicker.runtime.model.RepositoryInvocationEvent;
import com.sap.oss.smarttestpicker.runtime.model.RuntimeEvent;
import com.sap.oss.smarttestpicker.runtime.model.SpringBeanEvent;
import com.sap.oss.smarttestpicker.runtime.model.TableEvent;
import com.sap.oss.smarttestpicker.runtime.model.UnattributedEvent;
import com.sap.oss.smarttestpicker.runtime.model.UnattributedReason;

final class EventFacts {
	private EventFacts() {
	}

	static UnattributedEvent unattributed(UnattributedReason reason, RuntimeEvent event) {
		return new UnattributedEvent(reason, type(event), identity(event), event.evidence());
	}

	static String type(RuntimeEvent event) {
		if (event instanceof MethodHitEvent) return "METHOD";
		if (event instanceof SpringBeanEvent) return "SPRING_BEAN";
		if (event instanceof EndpointEvent) return "ENDPOINT";
		if (event instanceof RepositoryInvocationEvent) return "REPOSITORY";
		if (event instanceof EntityEvent) return "ENTITY";
		if (event instanceof TableEvent) return "TABLE";
		return "UNATTRIBUTED";
	}

	static String identity(RuntimeEvent event) {
		if (event instanceof MethodHitEvent value) return value.method().canonicalKey();
		if (event instanceof SpringBeanEvent value) return value.beanName() + ":" + value.binaryClassName();
		if (event instanceof EndpointEvent value) return value.httpMethod() + " " + value.routePattern();
		if (event instanceof RepositoryInvocationEvent value) return value.canonicalMethodKey();
		if (event instanceof EntityEvent value) return value.binaryClassName();
		if (event instanceof TableEvent value) return value.access() + ":" + value.tableName();
		if (event instanceof UnattributedEvent value) return value.eventIdentity();
		throw new IllegalArgumentException("unsupported runtime event: " + event.getClass().getName());
	}
}

