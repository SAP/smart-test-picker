// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.karate;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Framework-neutral identity and W3C Baggage support for the Karate request hook. */
public final class StpKarateClient {
	public static final String SUITE_ID_PROPERTY = "stp.testSuiteId";
	public static final String BAGGAGE_HEADER = "baggage";
	public static final String SUITE_ID_KEY = "stp.test.suite.id";
	public static final String TEST_ID_KEY = "stp.test.id";
	public static final String REQUEST_ID_KEY = "stp.request.id";
	private static final TextMapPropagator PROPAGATOR = TextMapPropagator.composite(
			W3CTraceContextPropagator.getInstance(), W3CBaggagePropagator.getInstance());
	private static final TextMapGetter<Map<String, List<String>>> REQUEST_GETTER = new TextMapGetter<>() {
		@Override public Iterable<String> keys(Map<String, List<String>> request) {
			return request == null ? Collections.emptyList() : request.keySet();
		}
		@Override public String get(Map<String, List<String>> request, String key) {
			if (request == null) return null;
			return request.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(key))
					.flatMap(entry -> entry.getValue().stream()).findFirst().orElse(null);
		}
	};

	private StpKarateClient() { }

	/** Injects W3C trace context and W3C Baggage for one concrete outgoing HTTP request. */
	static Map<String, String> headers(Map<String, List<String>> request, String suiteId, String featurePath,
			int sectionIndex, int scenarioLine, int exampleIndex) {
		suiteId = validate(suiteId, SUITE_ID_PROPERTY);
		featurePath = validate(featurePath, "Karate feature path");
		String testId = testId(featurePath, sectionIndex, scenarioLine, exampleIndex);
		String requestId = requestId(request);
		Context base = W3CBaggagePropagator.getInstance().extract(Context.current(), request, REQUEST_GETTER);
		Baggage existing = Baggage.fromContext(base);
		verifyExisting(existing, SUITE_ID_KEY, suiteId);
		verifyExisting(existing, TEST_ID_KEY, testId);
		verifyExisting(existing, REQUEST_ID_KEY, requestId);
		Baggage baggage = existing.toBuilder()
				.put(SUITE_ID_KEY, suiteId)
				.put(TEST_ID_KEY, testId)
				.put(REQUEST_ID_KEY, requestId)
				.build();
		Context requestContext = baggage.storeInContext(base);
		Map<String, String> injected = new LinkedHashMap<>();
		PROPAGATOR.inject(requestContext, injected, Map::put);
		return Map.copyOf(injected);
	}

	/** Resolves the run-wide suite identity from the documented property and environment variable. */
	public static String resolveSuiteId(String propertyValue, String environmentValue) {
		boolean propertySet = propertyValue != null;
		boolean environmentSet = environmentValue != null;
		if (propertySet && environmentSet && !propertyValue.equals(environmentValue))
			throw new IllegalArgumentException("Conflicting TestSuiteID values in -D" + SUITE_ID_PROPERTY + " and STP_TEST_SUITE_ID");
		return validate(propertySet ? propertyValue : environmentValue, SUITE_ID_PROPERTY);
	}

	static String testId(String featurePath, int sectionIndex, int scenarioLine, int exampleIndex) {
		return "karate-" + sha256(validate(featurePath, "Karate feature path") + "\n"
				+ sectionIndex + "\n" + scenarioLine + "\n" + exampleIndex);
	}

	private static void verifyExisting(Baggage baggage, String key, String expected) {
		String value = baggage.getEntryValue(key);
		if (value != null && !expected.equals(value))
			throw new IllegalStateException("Conflicting STP baggage entry " + key + ": expected '" + expected + "' but found '" + value + "'");
	}

	private static String requestId(Map<String, List<String>> request) {
		if (request != null) {
			List<Map.Entry<String, List<String>>> matches = request.entrySet().stream()
					.filter(entry -> entry.getKey().equalsIgnoreCase(BAGGAGE_HEADER)).toList();
			if (matches.size() > 1) throw new IllegalStateException("Duplicate W3C Baggage headers are not supported for STP identity");
			if (!matches.isEmpty()) {
				for (String member : String.join(",", matches.get(0).getValue()).split(",")) {
					String key = member.trim().split("=", 2)[0].trim();
					if (REQUEST_ID_KEY.equals(key)) {
						throw new IllegalStateException("Preexisting STP RequestID is not allowed: stp-karate-client generates a new RequestID for each HTTP attempt; remove STP configure headers integration or manually supplied RequestID");
					}
				}
			}
		}
		return UUID.randomUUID().toString();
	}

	private static String validate(String value, String source) {
		if (value == null || value.isBlank()) throw new IllegalArgumentException(source + " must be configured and non-empty");
		if (value.length() > 256) throw new IllegalArgumentException(source + " exceeds 256 characters");
		if (value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException(source + " contains an ISO control character");
		return value;
	}
	private static String sha256(String value) {
		try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
		catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable", impossible); }
	}
}
