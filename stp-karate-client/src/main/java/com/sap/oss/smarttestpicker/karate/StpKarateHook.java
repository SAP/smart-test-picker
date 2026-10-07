// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.karate;

import com.intuit.karate.RuntimeHook;
import com.intuit.karate.core.ScenarioRuntime;
import com.intuit.karate.http.HttpRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Install once on a Karate 1.x Runner. Immutable and safe to share across scenarios. */
public final class StpKarateHook implements RuntimeHook {

	private final String suiteId;

	public StpKarateHook(String suiteId) {
		this.suiteId = StpKarateClient.resolveSuiteId(suiteId, null);
	}

	public static StpKarateHook fromSystemProperties() {
		return new StpKarateHook(StpKarateClient.resolveSuiteId(
				System.getProperty(StpKarateClient.SUITE_ID_PROPERTY), System.getenv("STP_TEST_SUITE_ID")));
	}

	@Override public void beforeHttpCall(HttpRequest request, ScenarioRuntime runtime) {
		var scenario = runtime.scenario;
		Map<String, String> propagation = StpKarateClient.headers(request.getHeaders(), suiteId,
				scenario.getFeature().getResource().getPrefixedPath(), scenario.getSection().getIndex(),
				scenario.getLine(), scenario.getExampleIndex());
		// Karate 1.5.1 shares this map with its request builder, including across retries.
		// Never put generated identity back into the builder or mutate feature/config headers.
		Map<String, List<String>> headers = new LinkedHashMap<>();
		if (request.getHeaders() != null) {
			request.getHeaders().forEach((key, values) -> headers.put(key, new ArrayList<>(values)));
		}
		propagation.forEach((key, value) -> {
			headers.keySet().removeIf(existing -> existing.equalsIgnoreCase(key));
			headers.put(key, List.of(value));
		});
		request.setHeaders(headers);
	}
}
