// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import com.sap.oss.smarttestpicker.StpCollectorSelection;

/** Maven mapping defaults: ASM for schema-v3, JaCoCo for legacy schemas. */
final class MavenCollectorDefaults {
    private MavenCollectorDefaults() { }

    static StpCollectorSelection resolve(String configured, int schemaVersion) {
        if (schemaVersion == 3 && (configured == null || configured.isBlank())) {
            return StpCollectorSelection.ASM;
        }
        return StpCollectorSelection.parse(configured);
    }
}
