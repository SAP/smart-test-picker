// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.model;

public record ShardId(String value) implements Comparable<ShardId>
{
	public ShardId { if (value == null || value.isBlank()) throw new IllegalArgumentException("shard id must not be blank"); }
	@Override public int compareTo(ShardId other) { return value.compareTo(other.value); }
}
