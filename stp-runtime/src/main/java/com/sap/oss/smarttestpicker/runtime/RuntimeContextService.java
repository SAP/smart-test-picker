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
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicLong;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Explicit attribution scope. Cross-thread use is limited to snapshots installed
 * around wrapped executor tasks; reactive and request propagation are not implied.
 */
public final class RuntimeContextService {
	private final RuntimeEventAggregator aggregator;
	private final ThreadLocal<TestExecutionContext> current = new ThreadLocal<>();
	private final ThreadLocal<TestIdentity> lastFinished = new ThreadLocal<>();
	private final ThreadLocal<Deque<TaskIdentity>> currentTasks = ThreadLocal.withInitial(ArrayDeque::new);
	private final ThreadLocal<Deque<ContainerIdentity>> currentContainers = ThreadLocal.withInitial(ArrayDeque::new);
	private final AtomicLong logicalContexts = new AtomicLong();
	private final boolean debug;

	public RuntimeContextService(RuntimeEventAggregator aggregator) {
		this(aggregator, false);
	}

	public RuntimeContextService(RuntimeEventAggregator aggregator, boolean debug) {
		this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
		this.debug = debug;
	}

	public void beginTest(TestIdentity identity) {
		Objects.requireNonNull(identity, "identity");
		TestExecutionContext active = current.get();
		if (active != null) {
			throw new IllegalStateException("conflicting active test: " + active.testIdentity().platformUniqueId());
		}
		aggregator.beginTest(identity);
		lastFinished.remove();
		current.set(new TestExecutionContext(identity, logicalContexts.incrementAndGet()));
	}

	public void endTest(TestIdentity identity, TestResult result) {
		Objects.requireNonNull(identity, "identity");
		Objects.requireNonNull(result, "result");
		TestExecutionContext active = current.get();
		if (active == null || !identity.equals(active.testIdentity())) {
			throw new IllegalStateException("test is not active on this thread: " + identity.platformUniqueId());
		}
		try {
			aggregator.endTest(identity, result);
		} finally {
			current.remove();
			// Once a leaf ends, a still-active container owns synchronous lifecycle work such as AfterAll.
			if (currentContainers.get().isEmpty()) lastFinished.set(identity); else lastFinished.remove();
		}
	}

	public Optional<TestIdentity> currentTest() {
		return currentContext().map(TestExecutionContext::testIdentity);
	}

	public Optional<TestExecutionContext> currentContext() {
		return Optional.ofNullable(current.get());
	}

	/** Captures the active test at submission and restores the worker's state after execution. */
	public Runnable wrap(Runnable task) {
		Objects.requireNonNull(task, "task");
		TestExecutionContext captured = current.get();
		debug("capture", task, captured);
		return captured == null ? task : () -> runWith(captured, task);
	}

	/** Captures the active test at submission and restores the worker's state after execution. */
	public <V> Callable<V> wrap(Callable<V> task) {
		Objects.requireNonNull(task, "task");
		TestExecutionContext captured = current.get();
		debug("capture", task, captured);
		return captured == null ? task : () -> callWith(captured, task);
	}

	public <V> Supplier<V> wrapSupplier(Supplier<V> task) {
		Objects.requireNonNull(task, "task");
		TestExecutionContext captured = current.get();
		debug("capture", task, captured);
		return captured == null ? task : () -> supplyWith(captured, task);
	}

	public <T, R> Function<T, R> wrapFunction(Function<T, R> task) {
		Objects.requireNonNull(task, "task");
		TestExecutionContext captured = current.get();
		debug("capture", task, captured);
		return captured == null ? task : value -> applyWith(captured, task, value);
	}

	private void runWith(TestExecutionContext captured, Runnable task) {
		TestExecutionContext previous = installCaptured(captured, task);
		TestIdentity previousFinished = lastFinished.get();
		lastFinished.remove();
		try {
			task.run();
		} finally {
			restore(previous, previousFinished, task);
		}
	}

	private <V> V callWith(TestExecutionContext captured, Callable<V> task) throws Exception {
		TestExecutionContext previous = installCaptured(captured, task);
		TestIdentity previousFinished = lastFinished.get();
		lastFinished.remove();
		try {
			return task.call();
		} finally {
			restore(previous, previousFinished, task);
		}
	}

	private <V> V supplyWith(TestExecutionContext captured, Supplier<V> task) {
		TestExecutionContext previous = installCaptured(captured, task);
		TestIdentity previousFinished = lastFinished.get();
		lastFinished.remove();
		try {
			return task.get();
		} finally {
			restore(previous, previousFinished, task);
		}
	}

	private <T, R> R applyWith(TestExecutionContext captured, Function<T, R> task, T value) {
		TestExecutionContext previous = installCaptured(captured, task);
		TestIdentity previousFinished = lastFinished.get();
		lastFinished.remove();
		try {
			return task.apply(value);
		} finally {
			restore(previous, previousFinished, task);
		}
	}

	private TestExecutionContext installCaptured(TestExecutionContext captured, Object task) {
		TestExecutionContext previous = current.get();
		current.set(captured);
		currentTasks.get().push(new TaskIdentity(task.getClass().getName(), System.identityHashCode(task)));
		debug("attach", task, captured);
		return previous;
	}

	private void restore(TestExecutionContext previous, TestIdentity previousFinished, Object task) {
		if (previous == null) current.remove(); else current.set(previous);
		if (previousFinished == null) lastFinished.remove(); else lastFinished.set(previousFinished);
		Deque<TaskIdentity> tasks = currentTasks.get();
		tasks.pop();
		if (tasks.isEmpty()) currentTasks.remove();
		debug("restore/cleanup", task, previous);
	}

	public void record(RuntimeEvent event) {
		Objects.requireNonNull(event, "event");
		TestExecutionContext active = current.get();
		if (active != null) {
			aggregator.record(active.testIdentity(), event);
			return;
		}
		TestIdentity finished = lastFinished.get();
		if (finished != null) {
			aggregator.record(finished, event);
		} else if (event instanceof com.sap.oss.smarttestpicker.runtime.model.MethodHitEvent method
				&& !currentContainers.get().isEmpty()) {
			ContainerIdentity container = currentContainers.get().peek();
			aggregator.recordSetup(container.binaryName(), container.nested(), method);
		} else {
			aggregator.recordUnattributed(UnattributedReason.NO_ACTIVE_TEST, event);
		}
	}

	public void beginContainer(String uniqueId, String binaryName, boolean nested) {
		currentContainers.get().push(new ContainerIdentity(uniqueId, binaryName, nested));
		lastFinished.remove();
	}

	public void recordUnsupportedSetup(SetupDiagnostic.Kind kind,
			com.sap.oss.smarttestpicker.runtime.model.MethodIdentity method, String detail) {
		aggregator.recordSetupDiagnostic(new SetupDiagnostic(kind, SetupDiagnostic.Severity.ERROR, method, detail));
	}

	public void endContainer(String uniqueId) {
		Deque<ContainerIdentity> values = currentContainers.get();
		if (!values.isEmpty() && values.peek().uniqueId().equals(uniqueId)) values.pop();
		else values.removeIf(value -> value.uniqueId().equals(uniqueId));
		if (values.isEmpty()) currentContainers.remove();
	}

	public RuntimeEventAggregator aggregator() {
		return aggregator;
	}

	/** ROUND-10-only read-only state used by the bounded causal trace. */
	public DiagnosticContext diagnosticContext() {
		TestExecutionContext active = current.get();
		TestIdentity owner = active == null ? lastFinished.get() : active.testIdentity();
		Deque<TaskIdentity> tasks = currentTasks.get();
		TaskIdentity task = tasks.peek();
		if (tasks.isEmpty()) currentTasks.remove();
		return new DiagnosticContext(owner, active == null ? null : active.logicalContextId(), task,
				owner != null && aggregator.isFinished(owner));
	}

	public record TaskIdentity(String className, int identityHash) {}
	private record ContainerIdentity(String uniqueId, String binaryName, boolean nested) {}
	public record DiagnosticContext(TestIdentity testIdentity, Long logicalContextId, TaskIdentity task,
			boolean ownerFinished) {}

	public String runId() {
		return aggregator.runId();
	}

	public String jvmId() {
		return aggregator.jvmId();
	}

	private void debug(String operation, Object task, TestExecutionContext context) {
		if (!debug) return;
		String owner = context == null ? "none" : context.testIdentity().platformUniqueId();
		System.err.println("[stp-context] " + operation + " task=" + task.getClass().getName() + "@"
				+ Integer.toHexString(System.identityHashCode(task)) + " test=" + owner + " thread="
				+ Thread.currentThread().getName());
	}
}
