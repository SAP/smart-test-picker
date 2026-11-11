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
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Assigns logical JUnit leaf-test ownership to runtime observations. */
public final class StpRuntimeTestExecutionListener implements TestExecutionListener {
	private final RuntimeContextService runtime;
	private final Map<String, TestIdentity> started = new ConcurrentHashMap<>();
	private final Set<String> finished = ConcurrentHashMap.newKeySet();
	private final Map<String, MethodSource> methodSources = new ConcurrentHashMap<>();
	private final Map<String, String> containers = new ConcurrentHashMap<>();
	private volatile TestPlan testPlan;

	/** Used only by JUnit Platform ServiceLoader discovery. An empty registry is a no-op. */
	public StpRuntimeTestExecutionListener() {
		this(RuntimeContextRegistry.current().orElse(null));
	}

	public StpRuntimeTestExecutionListener(RuntimeContextService runtime) {
		this.runtime = runtime;
	}

	@Override
	public void testPlanExecutionStarted(TestPlan plan) {
		testPlan = plan;
	}

	@Override
	public void executionStarted(TestIdentifier identifier) {
		if (runtime == null) return;
		identifier.getSource().filter(MethodSource.class::isInstance).map(MethodSource.class::cast)
				.ifPresent(source -> methodSources.put(identifier.getUniqueId(), source));
		identifier.getSource().filter(ClassSource.class::isInstance).map(ClassSource.class::cast)
				.ifPresent(source -> {
					containers.put(identifier.getUniqueId(), source.getClassName());
					runtime.beginContainer(identifier.getUniqueId(), source.getClassName(), source.getClassName().contains("$"));
				});
		if (!identifier.isTest()) return;
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
		if (runtime == null) return;
		if (!identifier.isTest()) {
			if (executionResult.getStatus() == TestExecutionResult.Status.ABORTED) {
				recordNonExecutedDescendants(identifier, executionResult.getThrowable()
						.map(Throwable::getMessage).orElse("container aborted"));
			}
			if (containers.remove(identifier.getUniqueId()) != null) runtime.endContainer(identifier.getUniqueId());
			return;
		}
		String uniqueId = identifier.getUniqueId();
		TestIdentity identity = started.remove(uniqueId);
		if (identity == null || !finished.add(uniqueId)) return;
		runtime.endTest(identity, result(executionResult));
	}

	@Override
	public void executionSkipped(TestIdentifier identifier, String reason) {
		if (runtime == null) return;
		var skipped = identifier.isTest() ? java.util.List.of(identifier)
				: testPlan == null ? java.util.List.<TestIdentifier>of()
				: testPlan.getDescendants(identifier).stream().filter(TestIdentifier::isTest).toList();
		recordNonExecuted(skipped, reason);
	}

	private void recordNonExecutedDescendants(TestIdentifier identifier, String reason) {
		if (testPlan == null) return;
		recordNonExecuted(testPlan.getDescendants(identifier).stream().filter(TestIdentifier::isTest).toList(), reason);
	}

	private void recordNonExecuted(java.util.List<TestIdentifier> skipped, String reason) {
		for (TestIdentifier leaf : skipped) {
			String uniqueId = leaf.getUniqueId();
			if (!finished.add(uniqueId)) continue;
			TestIdentity identity = identity(leaf);
			runtime.beginTest(identity);
			runtime.endTest(identity, new TestResult(TestExecutionStatus.ABORTED, null, reason));
		}
	}

	private TestIdentity identity(TestIdentifier identifier) {
		Optional<MethodSource> source = identifier.getSource()
				.filter(MethodSource.class::isInstance).map(MethodSource.class::cast);
		if (source.isEmpty()) source = nearestMethodSource(identifier.getUniqueId());
		return new TestIdentity(identifier.getUniqueId(), identifier.getDisplayName(),
				source.map(MethodSource::getClassName).orElse(null),
				source.map(MethodSource::getMethodName).orElse(null),
				source.map(MethodSource::getMethodParameterTypes).orElse(null), engineId(identifier.getUniqueId()),
				runtime.runId(), runtime.jvmId());
	}

	private Optional<MethodSource> nearestMethodSource(String uniqueId) {
		return methodSources.entrySet().stream().filter(entry -> isAncestor(entry.getKey(), uniqueId))
				.max(java.util.Comparator.comparingInt(entry -> entry.getKey().length())).map(Map.Entry::getValue);
	}

	private static boolean isAncestor(String candidate, String uniqueId) {
		return uniqueId.startsWith(candidate + "/");
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
