// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.agent;

import com.sap.oss.smarttestpicker.runtime.RuntimeContextService;
import com.sap.oss.smarttestpicker.runtime.RuntimeContextRegistry;
import com.sap.oss.smarttestpicker.runtime.RuntimeEventAggregator;
import com.sap.oss.smarttestpicker.runtime.RuntimeHooks;
import com.sap.oss.smarttestpicker.runtime.RuntimeJsonSerializer;
import com.sap.oss.smarttestpicker.runtime.AsmCoverageFragmentProjector;
import com.sap.oss.smarttestpicker.runtime.CollectorIntegrity;
import com.sap.oss.smarttestpicker.runtime.FragmentProjectionConfig;
import com.sap.oss.smarttestpicker.coverage.serialization.CoverageFragmentCodec;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableCoverageFragmentCodec;
import com.sap.oss.smarttestpicker.coverage.ExecutableCoverageFragmentQualifier;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

final class AgentRuntime {
	private static final AtomicReference<AgentRuntime> INSTALLED = new AtomicReference<>();

	private final AgentConfiguration configuration;
	private final AgentMetrics metrics;
	private final String jvmId;
	private final List<String> errors = new CopyOnWriteArrayList<>();
	private final MethodCatalog catalog;
	private final RuntimeContextService runtimeContext;
	@SuppressWarnings("unused")
	private final RuntimeHooks.Registration hookRegistration;
	private final RuntimeContextRegistry.Registration contextRegistration;

	private AgentRuntime(AgentConfiguration configuration, AgentMetrics metrics, String jvmId) {
		this.configuration = configuration;
		this.metrics = metrics;
		this.jvmId = jvmId;
		this.catalog = new MethodCatalog(new Fnv1a64MethodIdHasher(), metrics, errors::add);
		this.runtimeContext = new RuntimeContextService(
				new RuntimeEventAggregator(configuration.runId(), jvmId), configuration.debug());
		RuntimeContextRegistry.Registration registry = null;
		RuntimeHooks.Registration hooks = null;
		try {
			registry = RuntimeContextRegistry.install(runtimeContext);
			hooks = RuntimeHooks.install(this::methodHit);
		}
		catch (Throwable failure) {
			if (hooks != null) hooks.close();
			if (registry != null) registry.close();
			throw failure;
		}
		this.contextRegistration = registry;
		this.hookRegistration = hooks;
	}

	static AgentRuntime install(AgentConfiguration configuration, AgentMetrics metrics) {
		AgentRuntime runtime = new AgentRuntime(configuration, metrics, "pid-" + ProcessHandle.current().pid());
		if (!INSTALLED.compareAndSet(null, runtime)) {
			runtime.closeRegistrations();
			throw new IllegalStateException("STP agent is already installed");
		}
		return runtime;
	}

	RuntimeContextService runtimeContext() {
		return runtimeContext;
	}

	MethodCatalog catalog() {
		return catalog;
	}

	void error(String error) {
		errors.add(error);
	}

	private void methodHit(long ordinal) {
		runtimeContext.recordMethod(catalog.probe(ordinal));
	}

	void writeOutput() {
		Map<Long, Long> hitSnapshot = catalog.hitSnapshot();
		String runtimeJson;
		try {
			runtimeJson = new RuntimeJsonSerializer().serialize(runtimeContext.aggregator());
		} catch (Throwable failure) {
			runtimeJson = null;
			errors.add("runtime-serialization-failure:" + failure.getClass().getName());
		}
		writeFragmentIfConfigured();
		try {
			AgentOutputWriter.write(configuration.output(), configuration.runId(), jvmId, configuration,
					metrics.snapshot(hitSnapshot.values().stream().mapToLong(Long::longValue).sum(), hitSnapshot.size(),
							runtimeContext.methodSlowPathNanos()), errors, catalog.snapshot(), hitSnapshot, runtimeJson);
		} catch (IOException failure) {
			errors.add(failure.getClass().getName() + ": " + String.valueOf(failure.getMessage()));
			System.err.println("[stp-agent] failed to write output: " + failure.getClass().getSimpleName());
		} finally {
			closeRegistrations();
		}
	}

	private void writeFragmentIfConfigured() {
		if (configuration.fragmentOutput() == null) return;
		AgentMetrics.Snapshot snapshot = metrics.snapshot();
		if (configuration.requireInstrumentation() && snapshot.classesTransformed() == 0) {
			String diagnostic = "ASM scope matched zero instrumented classes: classesTransformed=0; includes="
					+ configuration.includes() + "; excludes=" + configuration.excludes() + "; productionSources=" + configuration.productionSources();
			errors.add(diagnostic);
			System.err.println("[stp-agent] " + diagnostic);
			return; // premain invalidated any stale fragment; never publish a complete empty map.
		}
		try {
			var projected = new AsmCoverageFragmentProjector().project(runtimeContext.aggregator().snapshot(),
					FragmentProjectionConfig.of(configuration.revision(), configuration.shardId()),
					new CollectorIntegrity(true, snapshot.transformationErrors(), snapshot.methodIdCollisions(), errors));
			errors.addAll(projected.diagnostics());
			byte[] bytes;
			if (configuration.schemaVersion() == 2) {
				CoverageFragmentCodec codec = new CoverageFragmentCodec();
				bytes = codec.serialize(projected.fragment());
				codec.deserialize(bytes);
			} else if (configuration.schemaVersion() == 3) {
				var qualifier = new ExecutableCoverageFragmentQualifier();
				var executable = qualifier.qualify(projected.fragment(), configuration.executionTarget());
				qualifier.requireTarget(executable, configuration.executionTarget());
				ExecutableCoverageFragmentCodec codec = new ExecutableCoverageFragmentCodec();
				bytes = codec.serialize(executable);
				codec.deserialize(bytes);
			} else {
				throw new IllegalArgumentException("Unsupported runtime schema version: " + configuration.schemaVersion());
			}
			// Validate exactly the bytes that will be published; the final name is the commit point.
			if (!AgentOutputWriter.publish(configuration.fragmentOutput(), bytes)) {
				errors.add("fragment-atomic-move-unsupported:platform-fallback");
			}
		} catch (Throwable failure) {
			errors.add("fragment-serialization-failure:" + failure.getClass().getName());
			System.err.println("[stp-agent] failed to write coverage fragment: " + failure.getClass().getSimpleName()
					+ ": " + String.valueOf(failure.getMessage()));
		}
	}

	void abortInitialization() {
		closeRegistrations();
	}

	private void closeRegistrations() {
		contextRegistration.close();
		hookRegistration.close();
		INSTALLED.compareAndSet(this, null);
	}
}
