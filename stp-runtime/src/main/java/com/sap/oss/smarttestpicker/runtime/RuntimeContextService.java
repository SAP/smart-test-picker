// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.runtime;

import com.sap.oss.smarttestpicker.runtime.model.RuntimeEvent;
import com.sap.oss.smarttestpicker.runtime.model.TestIdentity;
import com.sap.oss.smarttestpicker.runtime.model.TestResult;
import com.sap.oss.smarttestpicker.runtime.model.UnattributedReason;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Explicit attribution scope. Cross-thread use is limited to snapshots installed
 * around wrapped executor tasks; reactive and request propagation are not implied.
 */
public final class RuntimeContextService {
	private final RuntimeEventAggregator aggregator;
	private final ThreadLocal<TestIdentity> current = new ThreadLocal<>();
	private final ThreadLocal<TestIdentity> lastFinished = new ThreadLocal<>();

	public RuntimeContextService(RuntimeEventAggregator aggregator) {
		this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
	}

	public void beginTest(TestIdentity identity) {
		Objects.requireNonNull(identity, "identity");
		TestIdentity active = current.get();
		if (active != null) {
			throw new IllegalStateException("conflicting active test: " + active.platformUniqueId());
		}
		aggregator.beginTest(identity);
		lastFinished.remove();
		current.set(identity);
	}

	public void endTest(TestIdentity identity, TestResult result) {
		Objects.requireNonNull(identity, "identity");
		Objects.requireNonNull(result, "result");
		TestIdentity active = current.get();
		if (!identity.equals(active)) {
			throw new IllegalStateException("test is not active on this thread: " + identity.platformUniqueId());
		}
		try {
			aggregator.endTest(identity, result);
		} finally {
			current.remove();
			lastFinished.set(identity);
		}
	}

	public Optional<TestIdentity> currentTest() {
		return Optional.ofNullable(current.get());
	}

	/** Captures the active test at submission and restores the worker's state after execution. */
	public Runnable wrap(Runnable task) {
		Objects.requireNonNull(task, "task");
		TestIdentity captured = current.get();
		return captured == null ? task : () -> runWith(captured, task);
	}

	/** Captures the active test at submission and restores the worker's state after execution. */
	public <V> Callable<V> wrap(Callable<V> task) {
		Objects.requireNonNull(task, "task");
		TestIdentity captured = current.get();
		return captured == null ? task : () -> callWith(captured, task);
	}

	private void runWith(TestIdentity captured, Runnable task) {
		TestIdentity previous = installCaptured(captured);
		TestIdentity previousFinished = lastFinished.get();
		lastFinished.remove();
		try {
			task.run();
		} finally {
			restore(previous, previousFinished);
		}
	}

	private <V> V callWith(TestIdentity captured, Callable<V> task) throws Exception {
		TestIdentity previous = installCaptured(captured);
		TestIdentity previousFinished = lastFinished.get();
		lastFinished.remove();
		try {
			return task.call();
		} finally {
			restore(previous, previousFinished);
		}
	}

	private TestIdentity installCaptured(TestIdentity captured) {
		TestIdentity previous = current.get();
		current.set(captured);
		return previous;
	}

	private void restore(TestIdentity previous, TestIdentity previousFinished) {
		if (previous == null) current.remove(); else current.set(previous);
		if (previousFinished == null) lastFinished.remove(); else lastFinished.set(previousFinished);
	}

	public void record(RuntimeEvent event) {
		Objects.requireNonNull(event, "event");
		TestIdentity active = current.get();
		if (active != null) {
			aggregator.record(active, event);
			return;
		}
		TestIdentity finished = lastFinished.get();
		if (finished != null) {
			aggregator.record(finished, event);
		} else {
			aggregator.recordUnattributed(UnattributedReason.NO_ACTIVE_TEST, event);
		}
	}

	public RuntimeEventAggregator aggregator() {
		return aggregator;
	}

	public String runId() {
		return aggregator.runId();
	}

	public String jvmId() {
		return aggregator.jvmId();
	}
}
