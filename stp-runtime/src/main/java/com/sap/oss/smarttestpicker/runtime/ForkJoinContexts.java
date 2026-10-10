// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.runtime;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.RecursiveTask;

/** Execution ownership, never task construction ownership. Weak identity keys do not retain tasks. */
final class ForkJoinContexts {
	static final String MARKER = "$stp$forkJoinExecution";
	private final RuntimeContextService service;
	private final ReferenceQueue<ForkJoinTask<?>> queue = new ReferenceQueue<>();
	private final Map<Key, Execution> executions = new HashMap<>();
	private static final ClassValue<Boolean> INSTRUMENTED = new ClassValue<>() {
		@Override protected Boolean computeValue(Class<?> type) {
			String name = RecursiveAction.class.isAssignableFrom(type) || RecursiveTask.class.isAssignableFrom(type)
					? "compute" : "exec";
			for (Class<?> candidate = type; candidate != null; candidate = candidate.getSuperclass()) {
				for (var method : candidate.getDeclaredMethods()) {
					if (method.getName().equals(name) && method.getParameterCount() == 0 && !method.isBridge()) {
						try { candidate.getDeclaredField(MARKER); return true; }
						catch (NoSuchFieldException absent) { return false; }
					}
				}
			}
			return false;
		}
	};

	ForkJoinContexts(RuntimeContextService service) { this.service = service; }

	synchronized void capture(ForkJoinTask<?> task) {
		if (task == null) return; // Preserve the original API's null handling.
		reap();
		var owner = service.captureForkJoinOwner();
		if (!INSTRUMENTED.get(task.getClass())) {
			if (owner.test() != null || owner.container() != null || owner.sharedSetup() != null)
				service.forkJoinIncomplete("execution boundary not instrumented: " + task.getClass().getName());
			return;
		}
		if (task.isDone()) return; // JDK won't execute a completed/cancelled task until reinitialized.
		Key key = new Key(task, queue);
		Execution previous = executions.get(key);
		if (previous != null) {
			previous.ambiguous = true;
			service.forkJoinIncomplete("repeated submission without completed reinitialize; original="
					+ previous.owner + "; submitting=" + owner);
		} else executions.put(key, new Execution(owner));
	}

	synchronized void reinitialize(ForkJoinTask<?> task) {
		reap();
		Key key = new Key(task, null);
		Execution execution = executions.get(key);
		if (execution != null && (execution.depth != 0 || !task.isDone())) {
			execution.ambiguous = true;
			service.forkJoinIncomplete("reinitialize before completed execution");
		} else executions.remove(key);
	}

	synchronized Runnable enter(ForkJoinTask<?> task) {
		reap();
		Execution execution = executions.get(new Key(task, null));
		if (execution == null) {
			execution = new Execution(new RuntimeContextService.ForkJoinOwner(null, null, null));
			service.forkJoinIncomplete("execution without observed submission: " + task.getClass().getName());
		}
		if (execution.depth > 0 && execution.thread != Thread.currentThread()) {
			execution.ambiguous = true;
			service.forkJoinIncomplete("concurrent execution of the same task");
		}
		execution.thread = Thread.currentThread();
		execution.depth++;
		Runnable restore = service.attachForkJoin(execution.owner, execution, task);
		Execution active = execution;
		return () -> {
			try { restore.run(); }
			finally { synchronized (ForkJoinContexts.this) {
				if (--active.depth == 0) active.thread = null;
			} }
		};
	}

	private void reap() {
		Key key;
		while ((key = (Key) queue.poll()) != null) executions.remove(key);
	}

	static final class Execution {
		final RuntimeContextService.ForkJoinOwner owner;
		volatile boolean ambiguous;
		Thread thread;
		int depth;
		Execution(RuntimeContextService.ForkJoinOwner owner) { this.owner = owner; }
	}

	private static final class Key extends WeakReference<ForkJoinTask<?>> {
		private final int hash;
		Key(ForkJoinTask<?> task, ReferenceQueue<ForkJoinTask<?>> queue) {
			super(task, queue);
			hash = System.identityHashCode(task);
		}
		@Override public int hashCode() { return hash; }
		@Override public boolean equals(Object other) {
			return this == other || other instanceof Key key && get() != null && get() == key.get();
		}
	}
}
