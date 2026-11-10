// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.runtime;

import com.sap.oss.smarttestpicker.runtime.model.*;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

/** A JVM-local, identity-specific ordinal; the diagnostic hash is not an array index. */
public final class MethodProbe {
	private static final Evidence EVIDENCE = new Evidence(EvidenceSource.ASM_METHOD_ENTRY, Certainty.OBSERVED);
	private final int ordinal;
	private final long hash;
	private final MethodIdentity method;
	private final LongAdder occurrences = new LongAdder();

	public MethodProbe(int ordinal, long hash, MethodIdentity method) {
		if (ordinal < 0) throw new IllegalArgumentException("negative probe ordinal");
		this.ordinal = ordinal;
		this.hash = hash;
		this.method = Objects.requireNonNull(method);
	}
	public int ordinal() { return ordinal; }
	public long hash() { return hash; }
	public MethodIdentity method() { return method; }
	public long occurrences() { return occurrences.sum(); }
	void hit() { occurrences.increment(); }
	MethodHitEvent event() { return new MethodHitEvent(hash, method, EVIDENCE); }
}
