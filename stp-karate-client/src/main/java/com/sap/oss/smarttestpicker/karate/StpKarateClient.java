// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.karate;

import io.karatelabs.http.HttpRequest;
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

/** Helpers called by Karate's run-wide {@code configure headers} callback. */
public final class StpKarateClient {
	public static final String SUITE_ID_PROPERTY = "stp.testSuiteId";
	public static final String BAGGAGE_HEADER = "baggage";
	public static final String SUITE_ID_KEY = "stp.test.suite.id";
	public static final String TEST_ID_KEY = "stp.test.id";
	public static final String REQUEST_ID_KEY = "stp.request.id";
	private static final TextMapPropagator PROPAGATOR = TextMapPropagator.composite(
			W3CTraceContextPropagator.getInstance(), W3CBaggagePropagator.getInstance());
	private static final TextMapGetter<HttpRequest> REQUEST_GETTER = new TextMapGetter<>() {
		@Override public Iterable<String> keys(HttpRequest request) {
			return request == null || request.getHeaders() == null ? Collections.emptyList() : request.getHeaders().keySet();
		}
		@Override public String get(HttpRequest request, String key) {
			if (request == null || request.getHeaders() == null) return null;
			return request.getHeaders().entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(key))
					.flatMap(entry -> entry.getValue().stream()).findFirst().orElse(null);
		}
	};

	private StpKarateClient() { }

	/** Injects W3C trace context and W3C Baggage for one concrete outgoing HTTP request. */
	public static Map<String, String> headers(HttpRequest request, String suiteId, String featurePath,
			int sectionIndex, int scenarioLine, int exampleIndex) {
		suiteId = validate(suiteId, SUITE_ID_PROPERTY);
		featurePath = validate(featurePath, "Karate feature path");		String testId = testId(featurePath, sectionIndex, scenarioLine, exampleIndex);
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

	private static String requestId(HttpRequest request) {
		String existing = null;
		if (request != null && request.getHeaders() != null) {
			List<Map.Entry<String, List<String>>> matches = request.getHeaders().entrySet().stream()
					.filter(entry -> entry.getKey().equalsIgnoreCase(BAGGAGE_HEADER)).toList();
			if (!matches.isEmpty()) {
				if (matches.size() != 1) throw new IllegalStateException("Duplicate W3C Baggage headers are not supported for STP identity");
				String baggage = String.join(",", matches.getFirst().getValue());
				for (String member : baggage.split(",")) {
					String[] pair = member.trim().split("=", 2);
					if (pair.length == 2 && REQUEST_ID_KEY.equals(pair[0].trim())) {
						if (existing != null) throw new IllegalStateException("Duplicate STP RequestID baggage entry");
						existing = decodeBaggageValue(pair[1].split(";", 2)[0].trim());
					}
				}
			}
		}
		if (existing == null) return UUID.randomUUID().toString();
		if (!validUuid(existing)) throw new IllegalStateException("Malformed STP RequestID baggage entry");
		return existing;
	}

	private static String decodeBaggageValue(String value) {
		try { return java.net.URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8); }
		catch (IllegalArgumentException malformed) { throw new IllegalStateException("Malformed STP RequestID baggage entry", malformed); }
	}
	private static boolean validUuid(String value) {
		if (value == null || value.length() > 256 || value.chars().anyMatch(Character::isISOControl)) return false;
		try { return UUID.fromString(value).toString().equalsIgnoreCase(value); }
		catch (IllegalArgumentException invalid) { return false; }
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
