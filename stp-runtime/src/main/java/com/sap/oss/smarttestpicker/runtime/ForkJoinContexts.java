// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.runtime;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.ForkJoinTask;

/** Weak object identity, per-execution ownership; never infer ownership from construction. */
final class ForkJoinContexts {
	private final RuntimeContextService service;
	private final ReferenceQueue<ForkJoinTask<?>> queue = new ReferenceQueue<>();
	private final Map<Key, Execution> executions = new HashMap<>();
	ForkJoinContexts(RuntimeContextService service) { this.service = service; }

	synchronized Object submit(ForkJoinTask<?> task) {
		if (task == null) return null;
		reap();
		var owner = service.captureForkJoinOwner();
		Key key = new Key(task, queue);
		Execution execution = executions.get(key);
		if (execution == null) { execution = new Execution(owner); executions.put(key, execution); }
		// Stream ForEachTask legitimately reforks itself while processing another split.
		// Repeated execution is safe only while every registration has the identical logical owner.
		else if (execution.resetting || !execution.owner.equals(owner)) {
			execution.conflicts++;
			execution.ambiguous = true;
			service.forkJoinIncomplete("repeated submission without completed reinitialize; original="
					+ execution.owner + "; submitting=" + owner);
		}
		execution.submittedWhenDone |= task.isDone();
		execution.registrations++;
		return new Submission(task, execution);
	}

	synchronized void submitted(Object token, Throwable failure) {
		if (token == null || failure == null) return;
		Submission ticket = (Submission) token;
		Execution execution = ticket.execution;
		// Roll back only failed registrations. Another same-owner submission may already be queued.
		if (--execution.registrations == 0 && !execution.started && !execution.ambiguous)
			executions.remove(new Key(ticket.task, null), execution);
	}

	synchronized Object reset(ForkJoinTask<?> task) {
		reap();
		Execution execution = executions.get(new Key(task, null));
		if (execution == null) return null;
		boolean safe = execution.depth == 0 && task.isDone() && !execution.resetting
				&& execution.registrations == 1 && !execution.submittedWhenDone;
		if (!safe) {
			execution.conflicts++;
			execution.ambiguous = true;
			service.forkJoinIncomplete("reinitialize without a single completed, non-racing submission");
		}
		execution.resetting = true;
		return new Reset(task, execution, safe, execution.conflicts);
	}

	synchronized void resetDone(Object token, Throwable failure) {
		if (token == null) return;
		Reset reset = (Reset) token;
		reset.execution.resetting = false;
		if (failure == null && reset.safe && reset.execution.conflicts == reset.conflicts && reset.execution.depth == 0)
			executions.remove(new Key(reset.task, null), reset.execution);
	}

	synchronized Runnable enter(ForkJoinTask<?> task) {
		reap();
		Execution execution = executions.get(new Key(task, null));
		if (execution == null) {
			var ambient = service.captureForkJoinOwner();
			if (ambient.test() != null || ambient.container() != null || ambient.sharedSetup() != null)
				service.forkJoinIncomplete("execution without observed submission: " + task.getClass().getName());
			execution = new Execution(new RuntimeContextService.ForkJoinOwner(null, null, null));
		}
		if (execution.resetting) {
			execution.conflicts++;
			execution.ambiguous = true;
			service.forkJoinIncomplete("execution during task reinitialize");
		}
		execution.started = true;
		execution.depth++;
		Runnable restore = service.attachForkJoin(execution.owner, execution, task);
		Execution active = execution;
		return () -> {
			try { restore.run(); }
			finally { synchronized (ForkJoinContexts.this) { active.depth--; } }
		};
	}

	private record Submission(ForkJoinTask<?> task, Execution execution) {}
	private record Reset(ForkJoinTask<?> task, Execution execution, boolean safe, int conflicts) {}

	private void reap() {
		Key key;
		while ((key = (Key) queue.poll()) != null) executions.remove(key);
	}

	static final class Execution {
		final RuntimeContextService.ForkJoinOwner owner;
		volatile boolean ambiguous;
		boolean started;
		boolean resetting;
		boolean submittedWhenDone;
		int conflicts;
		int registrations;
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
