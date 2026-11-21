// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.policy;

/** Whether an operation may be retried immediately, after a user fix, or not at all. */
public enum Retryability { NOT_RETRYABLE, TRANSIENT, AFTER_USER_FIX, UNKNOWN }
