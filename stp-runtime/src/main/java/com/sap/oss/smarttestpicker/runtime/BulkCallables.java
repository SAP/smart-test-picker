// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.runtime;

import java.util.AbstractCollection;
import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.Callable;

/** Read-only, ordered view. Validation/traversal stays at the executor's original consumption point. */
final class BulkCallables<V> extends AbstractCollection<Callable<V>> {
	private final Collection<? extends Callable<V>> tasks;
	private final RuntimeContextService service;
	private final RuntimeContextService.ForkJoinOwner owner;
	private final ForkJoinContexts.Execution ambiguous;
	private final boolean observedCapture;

	BulkCallables(Collection<? extends Callable<V>> tasks, RuntimeContextService service,
			RuntimeContextService.ForkJoinOwner owner, ForkJoinContexts.Execution ambiguous, boolean observedCapture) {
		this.tasks = tasks;
		this.service = service;
		this.owner = owner;
		this.ambiguous = ambiguous;
		this.observedCapture = observedCapture;
	}

	@Override public int size() { return tasks.size(); }
	@Override public Iterator<Callable<V>> iterator() {
		Iterator<? extends Callable<V>> iterator = tasks.iterator();
		return new Iterator<>() {
			@Override public boolean hasNext() { return iterator.hasNext(); }
			@Override public Callable<V> next() {
				Callable<V> task = iterator.next();
				if (task == null) return null; // Preserve the original executor's null validation timing.
				// A delegated bulk call must not stack scopes or change the original submission owner.
				if (capturedBy(task, service)) return task;
				return new CapturedCallable<>(task, service, owner, ambiguous, observedCapture);
			}
		};
	}

	static boolean capturedBy(Object task, RuntimeContextService service) {
		return task instanceof CapturedCallable<?> captured && captured.service == service;
	}

	private static final class CapturedCallable<V> implements Callable<V> {
		private final Callable<V> task;
		private final RuntimeContextService service;
		private final RuntimeContextService.ForkJoinOwner owner;
		private final ForkJoinContexts.Execution ambiguous;
		private final boolean observedCapture;
		CapturedCallable(Callable<V> task, RuntimeContextService service,
				RuntimeContextService.ForkJoinOwner owner, ForkJoinContexts.Execution ambiguous, boolean observedCapture) {
			this.task = task; this.service = service; this.owner = owner; this.ambiguous = ambiguous;
			this.observedCapture = observedCapture;
		}
		@Override public V call() throws Exception {
			// Reuse the full scope save/restore used by ForkJoin, including empty and setup ownership.
			Runnable restore = service.attachBulk(owner, ambiguous, task, observedCapture);
			try { return task.call(); } finally { restore.run(); }
		}
	}
}
