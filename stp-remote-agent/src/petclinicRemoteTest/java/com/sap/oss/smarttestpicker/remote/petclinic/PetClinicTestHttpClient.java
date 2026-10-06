// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote.petclinic;

import com.sap.oss.smarttestpicker.runtime.RuntimeContextRegistry;
import com.sap.oss.smarttestpicker.runtime.RuntimeContextService;
import com.sap.oss.smarttestpicker.runtime.model.TestIdentity;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** JUnit validation-only HTTP client that forwards the active STP platform unique ID. */
final class PetClinicTestHttpClient {
	static final String EXECUTION_ID_HEADER = "X-STP-Test-Execution-Id";
	private static final HttpClient CLIENT = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5)).build();
	private static final URI BASE_URI = URI.create(System.getProperty("petclinic.baseUrl"));

	private PetClinicTestHttpClient() { }

	static HttpResponse<String> get(String path) throws IOException, InterruptedException {
		Optional<RuntimeContextService> registered = RuntimeContextRegistry.current();
		Optional<TestIdentity> active = registered.flatMap(RuntimeContextService::currentTest);
		HttpRequest.Builder request = HttpRequest.newBuilder(BASE_URI.resolve(path))
				.timeout(Duration.ofSeconds(15)).GET();
		String uniqueId = null;
		String testMethod = "<no-active-test>";
		if (active.isPresent()) {
			TestIdentity identity = active.orElseThrow();
			uniqueId = identity.platformUniqueId();
			testMethod = identity.testMethod();
			String invalidReason = invalidIdentityReason(uniqueId);
			if (invalidReason == null) {
				request.header(EXECUTION_ID_HEADER, uniqueId);
			} else {
				System.err.printf("Rejected STP execution ID %s: %s; sending request without %s%n",
						escapeControls(uniqueId), invalidReason, EXECUTION_ID_HEADER);
			}
		}

		String executionId = uniqueId;
		String currentMethod = testMethod;
		long started = System.nanoTime();
		System.out.printf("HTTP_EVENT phase=START testId=%s testMethod=%s path=%s nanos=%d time=%s%n",
				logValue(executionId), currentMethod, path, started, Instant.now());
		try {
			return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
		} finally {
			long finished = System.nanoTime();
			System.out.printf("HTTP_EVENT phase=END testId=%s testMethod=%s path=%s nanos=%d time=%s%n",
					logValue(executionId), currentMethod, path, finished, Instant.now());
		}
	}

	private static String invalidIdentityReason(String value) {
		if (value.isEmpty()) return "platformUniqueId is empty";
		if (value.length() > 256) return "platformUniqueId exceeds 256 characters (length=" + value.length() + ")";
		if (value.codePoints().anyMatch(Character::isISOControl)) return "platformUniqueId contains an ISO control character";
		return null;
	}

	private static String escapeControls(String value) {
		StringBuilder escaped = new StringBuilder();
		value.codePoints().forEach(codePoint -> {
			if (Character.isISOControl(codePoint)) escaped.append(String.format("\\u%04X", codePoint));
			else escaped.appendCodePoint(codePoint);
		});
		return '"' + escaped.toString() + '"';
	}

	private static String logValue(String value) {
		return value == null ? "<none>" : value.replaceAll("\\s+", "_");
	}
}
