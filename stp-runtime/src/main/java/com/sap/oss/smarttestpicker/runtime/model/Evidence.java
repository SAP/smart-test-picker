// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.runtime.model;

import java.util.Objects;

public record Evidence(EvidenceSource source, Certainty certainty) {
	public Evidence {
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(certainty, "certainty");
	}
}

