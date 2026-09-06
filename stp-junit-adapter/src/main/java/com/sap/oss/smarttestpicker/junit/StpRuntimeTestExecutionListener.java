// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.junit;

import com.sap.oss.smarttestpicker.runtime.RuntimeContextService;
import com.sap.oss.smarttestpicker.runtime.RuntimeContextRegistry;
import com.sap.oss.smarttestpicker.runtime.model.TestExecutionStatus;
import com.sap.oss.smarttestpicker.runtime.model.TestIdentity;
import com.sap.oss.smarttestpicker.runtime.model.TestResult;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Assigns logical JUnit leaf-test ownership to runtime observations. */
public final class StpRuntimeTestExecutionListener implements TestExecutionListener {
	private final RuntimeContextService runtime;
	private final Map<String, TestIdentity> started = new ConcurrentHashMap<>();
	private final Set<String> finished = ConcurrentHashMap.newKeySet();

	/** Used only by JUnit Platform ServiceLoader discovery. An empty registry is a no-op. */
	public StpRuntimeTestExecutionListener() {
		this(RuntimeContextRegistry.current().orElse(null));
	}

	public StpRuntimeTestExecutionListener(RuntimeContextService runtime) {
		this.runtime = runtime;
	}

	@Override
	public void executionStarted(TestIdentifier identifier) {
		if (runtime == null || !identifier.isTest()) return;
		String uniqueId = identifier.getUniqueId();
		if (finished.contains(uniqueId)) return;
		TestIdentity identity = identity(identifier);
		if (started.putIfAbsent(uniqueId, identity) != null) return;
		try {
			runtime.beginTest(identity);
		} catch (RuntimeException failure) {
			started.remove(uniqueId);
			throw failure;
		}
	}

	@Override
	public void executionFinished(TestIdentifier identifier, TestExecutionResult executionResult) {
		if (runtime == null || !identifier.isTest()) return;
		String uniqueId = identifier.getUniqueId();
		TestIdentity identity = started.remove(uniqueId);
		if (identity == null || !finished.add(uniqueId)) return;
		runtime.endTest(identity, result(executionResult));
	}

	private TestIdentity identity(TestIdentifier identifier) {
		Optional<MethodSource> source = identifier.getSource()
				.filter(MethodSource.class::isInstance).map(MethodSource.class::cast);
		return new TestIdentity(identifier.getUniqueId(), identifier.getDisplayName(),
				source.map(MethodSource::getClassName).orElse(null),
				source.map(MethodSource::getMethodName).orElse(null), engineId(identifier.getUniqueId()),
				runtime.runId(), runtime.jvmId());
	}

	private static String engineId(String uniqueId) {
		try {
			return UniqueId.parse(uniqueId).getSegments().stream()
					.filter(segment -> "engine".equals(segment.getType()))
					.map(UniqueId.Segment::getValue).findFirst().orElse(null);
		} catch (RuntimeException ignored) {
			return null;
		}
	}

	private static TestResult result(TestExecutionResult result) {
		TestExecutionStatus status = switch (result.getStatus()) {
			case SUCCESSFUL -> TestExecutionStatus.SUCCESSFUL;
			case FAILED -> TestExecutionStatus.FAILED;
			case ABORTED -> TestExecutionStatus.ABORTED;
		};
		Throwable throwable = result.getThrowable().orElse(null);
		return new TestResult(status, throwable == null ? null : throwable.getClass().getName(),
				throwable == null ? null : throwable.getMessage());
	}
}
