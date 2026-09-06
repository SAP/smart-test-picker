// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.runtime;

import com.sap.oss.smarttestpicker.runtime.model.MethodIdentity;

/** Explicit setup uncertainty; ERROR prevents a locally-complete fragment. */
public record SetupDiagnostic(Kind kind, Severity severity, MethodIdentity method, String detail)
		implements Comparable<SetupDiagnostic>
{
	public SetupDiagnostic {
		if (kind == null || severity == null) throw new IllegalArgumentException("setup diagnostic kind and severity are required");
		detail = detail == null ? "" : detail;
	}

	public enum Kind {
		UNATTRIBUTABLE_SETUP,
		PARALLEL_CONTAINER_SETUP_UNSUPPORTED,
		SHARED_CONTEXT_SETUP_UNSUPPORTED,
		INHERITED_SETUP_UNSUPPORTED,
		ASYNC_SETUP_UNSUPPORTED
	}
	public enum Severity { INFO, WARNING, ERROR }

	@Override public int compareTo(SetupDiagnostic other) { return canonicalText().compareTo(other.canonicalText()); }
	public String canonicalText() {
		return kind + ":" + severity + ":" + (method == null ? "unknown" : method.canonicalKey()) + ":" + detail;
	}
}
