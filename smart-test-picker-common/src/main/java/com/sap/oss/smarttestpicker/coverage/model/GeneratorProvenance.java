// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.model;

/** Versions of STP, the coverage runtime, JaCoCo and the JDK used to produce an artifact. */
public record GeneratorProvenance(String stpVersion, String coverageRuntimeVersion, String jacocoVersion, String jdkVersion) {}
