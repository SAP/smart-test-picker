// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.karate;

import com.intuit.karate.RuntimeHook;
import com.intuit.karate.Suite;
import com.intuit.karate.core.ScenarioRuntime;
import com.intuit.karate.core.Scenario;
import com.intuit.karate.core.Feature;
import com.intuit.karate.core.FeatureSection;
import com.intuit.karate.core.FeatureRuntime;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentHashMap;
import com.intuit.karate.http.HttpRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.file.Path;

/** One instance per Karate 1.x run, safe to share across that run's parallel scenarios. */
public final class StpKarateHook implements RuntimeHook {
	public static final String OUTPUT_DIRECTORY_PROPERTY = "stp.outputDirectory";
	public static final String OUTPUT_PREFIX_PROPERTY = "stp.outputPrefix";

	private final String suiteId;
	private final ExecutionManifest manifest;
	private final ConcurrentMap<Feature, Map<FeatureSection, String>> names = new ConcurrentHashMap<>();
	private final ConcurrentMap<ScenarioRuntime, String> identities = new ConcurrentHashMap<>();
	private final ConcurrentMap<FeatureRuntime, ConcurrentMap<String, Scenario>> examples = new ConcurrentHashMap<>();
	private Suite owner;
	private IllegalStateException lifecycleFailure;

	public StpKarateHook(String suiteId) {
		this(suiteId, configuredDirectory(), System.getProperty(OUTPUT_PREFIX_PROPERTY, "stp-karate"));
	}

	public StpKarateHook(String suiteId, Path outputDirectory) {
		this(suiteId, outputDirectory, "stp-karate");
	}

	public StpKarateHook(String suiteId, Path outputDirectory, String outputPrefix) {
		this.suiteId = StpKarateClient.resolveSuiteId(suiteId, null);
		this.manifest = new ExecutionManifest(this.suiteId, outputDirectory, outputPrefix);
	}

	private static Path configuredDirectory() {
		String value = System.getProperty(OUTPUT_DIRECTORY_PROPERTY);
		if (value == null || value.isBlank()) throw new IllegalArgumentException(OUTPUT_DIRECTORY_PROPERTY + " is required");
		return Path.of(value);
	}

	public Path manifestPath() { return manifest.output(); }
	public String runId() { return manifest.runId(); }

	@Override public synchronized void beforeSuite(Suite suite) {
		if (owner != null) {
			lifecycleFailure = new IllegalStateException("Create a new StpKarateHook for each Runner execution: " + manifest.output());
			throw lifecycleFailure;
		}
		owner = suite;
	}

	@Override public synchronized void afterSuite(Suite suite) {
		// Karate logs exceptions from beforeSuite; rethrow here so reuse cannot look successful.
		if (lifecycleFailure != null) throw lifecycleFailure;
		if (owner != suite) throw new IllegalStateException("Unexpected Karate run for STP manifest: " + manifest.output());
		try { manifest.finish(); } finally { names.clear(); identities.clear(); examples.clear(); }
	}

	public static StpKarateHook fromSystemProperties() {
		return new StpKarateHook(StpKarateClient.resolveSuiteId(
				System.getProperty(StpKarateClient.SUITE_ID_PROPERTY), System.getenv("STP_TEST_SUITE_ID")));
	}

	@Override public boolean beforeScenario(ScenarioRuntime runtime) {
		var scenario = runtime.scenario;
		String name = names.computeIfAbsent(scenario.getFeature(), KarateTestIdentity::definitionNames).get(scenario.getSection());
		Map<String, Object> row = null;
		if (scenario.getSection().isOutline()) {
			row = scenario.getExampleData();
			if (row == null) throw new IllegalArgumentException("STP Outline resolved example data is unavailable; cannot derive a stable TestID");
		}
		String id = KarateTestIdentity.create(scenario.getFeature().getResource().getPrefixedPath(), name, row);
		var previous = examples.computeIfAbsent(runtime.featureRuntime, ignored -> new ConcurrentHashMap<>()).putIfAbsent(id, scenario);
		if (previous != null && previous != scenario)
			throw new IllegalArgumentException("Ambiguous STP example identity in " + scenario.getFeature().getResource().getPrefixedPath()
					+ ": duplicate resolved row or hash collision for " + id);
		identities.put(runtime, id);
		return true;
	}

	@Override public void afterScenario(ScenarioRuntime runtime) { identities.remove(runtime); }
	@Override public void afterFeature(FeatureRuntime runtime) { examples.remove(runtime); }

	@Override public void beforeHttpCall(HttpRequest request, ScenarioRuntime runtime) {
		var scenario = runtime.scenario;
		String testId = identities.get(runtime);
		if (testId == null) throw new IllegalStateException("STP Scenario identity was not initialized");
		var prepared = StpKarateClient.prepare(request.getHeaders(), suiteId, testId);
		// Karate 1.5.1 shares this map with its request builder, including across retries.
		// Never put generated identity back into the builder or mutate feature/config headers.
		Map<String, List<String>> headers = new LinkedHashMap<>();
		if (request.getHeaders() != null) {
			request.getHeaders().forEach((key, values) -> headers.put(key, new ArrayList<>(values)));
		}
		prepared.headers().forEach((key, value) -> {
			headers.keySet().removeIf(existing -> existing.equalsIgnoreCase(key));
			headers.put(key, List.of(value));
		});
		manifest.record(new ExecutionManifest.Request(prepared.testId(), prepared.requestId(),
				scenario.getFeature().getResource().getPrefixedPath(), scenario.getSection().getIndex(),
				scenario.getLine(), scenario.getExampleIndex(), request.getMethod(), ExecutionManifest.safeUri(request.getUrl())));
		request.setHeaders(headers);
	}
}
