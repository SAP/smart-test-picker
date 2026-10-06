// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.karate;

import io.karatelabs.http.HttpRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Helpers called by Karate's run-wide {@code configure headers} callback. */
public final class StpKarateClient {
	public static final String SUITE_ID_PROPERTY = "stp.testSuiteId";
	public static final String TEST_SUITE_HEADER = "X-STP-Test-Suite-Id";
	public static final String TEST_ID_HEADER = "X-STP-Test-Id";
	public static final String REQUEST_ID_HEADER = "X-STP-Request-Id";

	private StpKarateClient() { }

	/**
	 * Returns the three STP headers for one concrete outgoing request.
	 * The feature and scenario fields are supplied by Karate's scenario-bound dynamic headers function.
	 */
	public static Map<String, String> headers(HttpRequest request, String suiteId, String featurePath,
			int sectionIndex, int scenarioLine, int exampleIndex) {
		suiteId = validate(suiteId, SUITE_ID_PROPERTY);
		featurePath = validate(featurePath, "Karate feature path");
		String testId = testId(featurePath, sectionIndex, scenarioLine, exampleIndex);
		String requestId = requestId(request);
		verifyExisting(request, TEST_SUITE_HEADER, suiteId);
		verifyExisting(request, TEST_ID_HEADER, testId);
		Map<String, String> result = new LinkedHashMap<>();
		result.put(TEST_SUITE_HEADER, suiteId);
		result.put(TEST_ID_HEADER, testId);
		result.put(REQUEST_ID_HEADER, requestId);
		return Map.copyOf(result);
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

	private static void verifyExisting(HttpRequest request, String name, String expected) {
		if (request == null || request.getHeaders() == null) return;
		List<Map.Entry<String, List<String>>> matches = request.getHeaders().entrySet().stream()
				.filter(entry -> entry.getKey().equalsIgnoreCase(name)).toList();
		if (matches.isEmpty()) return;
		List<String> values = matches.stream().flatMap(entry -> entry.getValue().stream()).toList();
		if (matches.size() != 1 || values.size() != 1 || !expected.equals(values.getFirst())) {
			throw new IllegalStateException("Conflicting STP request identity header " + name
					+ ": expected '" + expected + "' but found " + values);
		}
	}

	private static String requestId(HttpRequest request) {
		if (request == null || request.getHeaders() == null) return UUID.randomUUID().toString();
		List<Map.Entry<String, List<String>>> matches = request.getHeaders().entrySet().stream()
				.filter(entry -> entry.getKey().equalsIgnoreCase(REQUEST_ID_HEADER)).toList();
		if (matches.isEmpty()) return UUID.randomUUID().toString();
		List<String> values = matches.stream().flatMap(entry -> entry.getValue().stream()).toList();
		if (matches.size() != 1 || values.size() != 1 || !validUuid(values.getFirst()))
			throw new IllegalStateException("Malformed or duplicate STP request identity header " + REQUEST_ID_HEADER);
		return values.getFirst();
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
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 is unavailable", impossible);
		}
	}
}
