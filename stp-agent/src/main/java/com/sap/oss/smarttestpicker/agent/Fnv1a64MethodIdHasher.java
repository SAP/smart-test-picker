// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.agent;

import java.nio.charset.StandardCharsets;

/** FNV-1a 64-bit v1: offset basis seed, UTF-8 bytes, and the standard FNV prime. */
final class Fnv1a64MethodIdHasher implements MethodIdHasher {
	public static final String ALGORITHM = "fnv1a64-v1";
	public static final long SEED = 0xcbf29ce484222325L;
	private static final long PRIME = 0x100000001b3L;

	@Override
	public long hash(String canonicalMethodKey) {
		long hash = SEED;
		for (byte value : canonicalMethodKey.getBytes(StandardCharsets.UTF_8)) {
			hash ^= value & 0xffL;
			hash *= PRIME;
		}
		return hash;
	}
}
