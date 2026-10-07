// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import java.net.InetAddress;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Producer identity for one persisted Remote STP fragment. */
record RemoteFragmentMetadata(String serviceId, String instanceId, String revision) {
	static final String SERVICE_ENV = "STP_SERVICE_ID";
	static final String INSTANCE_ENV = "STP_INSTANCE_ID";
	static final String REVISION_ENV = "STP_REVISION";

	RemoteFragmentMetadata {
		validate(serviceId, "serviceId");
		validate(instanceId, "instanceId");
		validate(revision, "revision");
	}

	static RemoteFragmentMetadata resolve(String configuredServiceId, String configuredInstanceId,
			String configuredRevision, String instanceIdEnvironmentVariable, Map<String, String> environment,
			Supplier<String> hostname, Supplier<String> runUuid) {
		String serviceId = choose(configuredServiceId, environment.get(SERVICE_ENV));
		String revision = choose(configuredRevision, environment.get(REVISION_ENV));
		if (!RemoteRequestIdentity.valid(serviceId))
			throw new IllegalArgumentException("serviceId is required via serviceId= or environment variable " + SERVICE_ENV);
		if (!RemoteRequestIdentity.valid(revision))
			throw new IllegalArgumentException("revision is required via revision= or environment variable " + REVISION_ENV);

		String instanceId = choose(configuredInstanceId, environment.get(instanceIdEnvironmentVariable));
		if (!RemoteRequestIdentity.valid(instanceId)) {
			String host = safeGet(hostname);
			if (RemoteRequestIdentity.valid(host)) instanceId = host;
			else {
				String uuid = safeGet(runUuid);
				if (!RemoteRequestIdentity.valid(uuid)) throw new IllegalStateException("cannot generate a valid Remote STP JVM run ID");
				instanceId = "jvm-" + uuid;
			}
		}
		return new RemoteFragmentMetadata(serviceId, instanceId, revision);
	}

	static String hostname() {
		try { return InetAddress.getLocalHost().getHostName(); }
		catch (Exception unavailable) { return null; }
	}

	private static String choose(String explicit, String fallback) {
		return explicit != null ? explicit : (fallback == null || fallback.isBlank() ? null : fallback.trim());
	}

	private static String safeGet(Supplier<String> supplier) {
		try { return supplier.get(); }
		catch (RuntimeException unavailable) { return null; }
	}

	private static void validate(String value, String field) {
		if (!RemoteRequestIdentity.valid(value))
			throw new IllegalArgumentException(field + " must be nonblank, at most 256 characters, and contain no ISO control characters");
	}
}
