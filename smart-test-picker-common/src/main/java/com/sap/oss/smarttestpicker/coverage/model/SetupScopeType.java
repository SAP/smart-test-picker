// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.model;

/** Kind of container or framework lifecycle scope responsible for setup coverage. */
public enum SetupScopeType { CONTAINER, NESTED_CONTAINER, INHERITED_SETUP, SHARED_CONTEXT, FRAMEWORK_SETUP }
