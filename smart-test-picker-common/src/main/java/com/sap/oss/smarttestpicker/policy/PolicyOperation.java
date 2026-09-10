// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.policy;

/** Bounded operation detail; context remains the policy decision boundary. */
public enum PolicyOperation {
	GENERAL, SCM_RESOLUTION, REVISION_PREFLIGHT, SELECTOR, INVENTORY_DISCOVERY,
	MAPPING_PREPARATION, FRAGMENT_COLLECTION, MAPPING_JOIN, MAP_PUBLICATION,
	EXACT_MAP_LOOKUP, LATEST_MAP_LOOKUP, TEST_EXECUTION
}
