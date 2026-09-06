// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.agent;

import com.sap.oss.smarttestpicker.runtime.RuntimeContextService;
import com.sap.oss.smarttestpicker.runtime.RuntimeContextRegistry;
import com.sap.oss.smarttestpicker.runtime.RuntimeEventAggregator;
import com.sap.oss.smarttestpicker.runtime.RuntimeHooks;
import com.sap.oss.smarttestpicker.runtime.RuntimeJsonSerializer;
import com.sap.oss.smarttestpicker.runtime.model.Certainty;
import com.sap.oss.smarttestpicker.runtime.model.Evidence;
import com.sap.oss.smarttestpicker.runtime.model.EvidenceSource;
import com.sap.oss.smarttestpicker.runtime.model.MethodHitEvent;
import com.sap.oss.smarttestpicker.runtime.model.MethodIdentity;
import com.sap.oss.smarttestpicker.runtime.model.UnattributedEvent;
import com.sap.oss.smarttestpicker.runtime.model.UnattributedReason;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicReference;

final class AgentRuntime {
	private static final AtomicReference<AgentRuntime> INSTALLED = new AtomicReference<>();
	private static final Evidence METHOD_ENTRY_EVIDENCE =
			new Evidence(EvidenceSource.ASM_METHOD_ENTRY, Certainty.OBSERVED);

	private final AgentConfiguration configuration;
	private final AgentMetrics metrics;
	private final String jvmId;
	private final List<String> errors = new CopyOnWriteArrayList<>();
	private final MethodCatalog catalog;
	private final RuntimeContextService runtimeContext;
	private final Map<Long, LongAdder> hits = new ConcurrentHashMap<>();
	private final CausalTraceRecorder causalTrace;
	private final Round13TraceRecorder round13Trace;
	private final RuntimeHooks.Round13Registration round13Registration;
	private final Round14TraceRecorder round14Trace;
	private final RuntimeHooks.Round14Registration round14Registration;
	private final Round15TraceRecorder round15Trace;
	private final RuntimeHooks.Round15Registration round15Registration;
	@SuppressWarnings("unused")
	private final RuntimeHooks.Registration hookRegistration;
	private final RuntimeContextRegistry.Registration contextRegistration;

	private AgentRuntime(AgentConfiguration configuration, AgentMetrics metrics, String jvmId) {
		this.configuration = configuration;
		this.metrics = metrics;
		this.jvmId = jvmId;
		this.catalog = new MethodCatalog(new Fnv1a64MethodIdHasher(), metrics, errors::add);
		this.causalTrace = CausalTraceRecorder.fromSystemProperties();
		this.round13Trace = Round13TraceRecorder.fromSystemProperties();
		this.round14Trace = Round14TraceRecorder.fromSystemProperties();
		this.round15Trace = Round15TraceRecorder.fromSystemProperties();
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
		this.round13Registration = round13Trace.enabled() ? RuntimeHooks.installRound13(round13Trace) : null;
		this.round14Registration = round14Trace.enabled() ? RuntimeHooks.installRound14(round14Trace) : null;
		this.round15Registration = round15Trace.enabled() ? RuntimeHooks.installRound15(round15Trace) : null;
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

	private void methodHit(long methodId) {
		long started = System.nanoTime();
		try {
			metrics.methodHit(methodId);
			hits.computeIfAbsent(methodId, ignored -> new LongAdder()).increment();
			List<MethodIdentity> methods = catalog.resolve(methodId);
			if (methods.size() == 1) {
				causalTrace.hit(methods.get(0), runtimeContext);
				runtimeContext.record(new MethodHitEvent(methodId, methods.get(0), METHOD_ENTRY_EVIDENCE));
			} else {
				runtimeContext.aggregator().recordUnattributed(new UnattributedEvent(UnattributedReason.UNKNOWN_CONTEXT,
						"METHOD_ID_COLLISION", Long.toUnsignedString(methodId), METHOD_ENTRY_EVIDENCE));
			}
		} finally {
			metrics.addRuntimeRecordingNanos(System.nanoTime() - started);
		}
	}

	void writeOutput() {
		Map<Long, Long> hitSnapshot = new java.util.TreeMap<>(Long::compareUnsigned);
		hits.forEach((id, count) -> hitSnapshot.put(id, count.sum()));
		String runtimeJson = new RuntimeJsonSerializer().serialize(runtimeContext.aggregator());
		try {
			AgentOutputWriter.write(configuration.output(), configuration.runId(), jvmId, configuration,
					metrics.snapshot(), errors, catalog.snapshot(), hitSnapshot, runtimeJson);
		} catch (IOException failure) {
			errors.add(failure.getClass().getName() + ": " + String.valueOf(failure.getMessage()));
			System.err.println("[stp-agent] failed to write output: " + failure.getClass().getSimpleName());
		} finally {
			try { causalTrace.write(); } catch (IOException failure) { errors.add(failure.toString()); }
			try { round13Trace.write(); } catch (IOException failure) { errors.add(failure.toString()); }
			try { round14Trace.write(); } catch (IOException failure) { errors.add(failure.toString()); }
			try { round15Trace.write(); } catch (IOException failure) { errors.add(failure.toString()); }
			closeRegistrations();
		}
	}

	void abortInitialization() {
		closeRegistrations();
	}

	private void closeRegistrations() {
		if (round15Registration != null) round15Registration.close();
		if (round14Registration != null) round14Registration.close();
		if (round13Registration != null) round13Registration.close();
		contextRegistration.close();
		hookRegistration.close();
		INSTALLED.compareAndSet(this, null);
	}
}
