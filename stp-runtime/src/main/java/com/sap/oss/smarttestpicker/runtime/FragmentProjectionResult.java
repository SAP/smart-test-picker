// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.runtime;

import com.sap.oss.smarttestpicker.coverage.model.CoverageFragment;

import java.util.List;
import java.util.Optional;

public record FragmentProjectionResult(CoverageFragment fragment, List<String> diagnostics) {
	public FragmentProjectionResult {
		diagnostics = List.copyOf(diagnostics);
	}

	public Optional<CoverageFragment> successfulFragment() {
		return Optional.ofNullable(fragment);
	}

	public boolean successful() {
		return fragment != null && fragment.collectionCompleted();
	}
}
