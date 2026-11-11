// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.runtime;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/** Owner-local paged ordinal array. First-use publication uses CAS, never the global monitor. */
public final class MethodHitOwner {
	private static final int SHIFT = 8;
	private final AtomicReference<AtomicReferenceArray<Counter>[]> pages = new AtomicReference<>(emptyPages());
	private volatile boolean closed;

	@SuppressWarnings("unchecked")
	private static AtomicReferenceArray<Counter>[] emptyPages() { return new AtomicReferenceArray[1]; }
	public void close() { closed = true; }
	boolean closed() { return closed; }

	/** Returns a counter only to the first registering caller; repeats allocate nothing. */
	Counter firstHit(int ordinal) {
		int pageIndex = ordinal >>> SHIFT;
		var current = pages.get();
		while (pageIndex >= current.length || current[pageIndex] == null) {
			var replacement = Arrays.copyOf(current, Math.max(current.length, pageIndex + 1));
			replacement[pageIndex] = new AtomicReferenceArray<>(1 << SHIFT);
			pages.compareAndSet(current, replacement);
			current = pages.get();
		}
		var page = current[pageIndex];
		int index = ordinal & ((1 << SHIFT) - 1);
		Counter counter = page.get(index);
		if (counter == null) {
			page.compareAndSet(index, null, new Counter());
			counter = page.get(index);
		}
		counter.occurrences.increment();
		if (counter.registered.get() || !counter.registered.compareAndSet(false, true)) return null;
		return counter;
	}

	static final class Counter {
		final LongAdder occurrences = new LongAdder();
		final AtomicBoolean registered = new AtomicBoolean();
	}
}
