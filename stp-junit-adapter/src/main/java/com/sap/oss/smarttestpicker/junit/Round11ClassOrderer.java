// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.junit;

import org.junit.jupiter.api.ClassDescriptor;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.ClassOrdererContext;

import java.util.HashMap;
import java.util.Map;

/** Opt-in deterministic class order used only by the ROUND 11 external experiment. */
public final class Round11ClassOrderer implements ClassOrderer {
	@Override
	public void orderClasses(ClassOrdererContext context) {
		String configured = System.getProperty("stp.round11.classOrder", "");
		String[] names = configured.split(",");
		Map<String, Integer> rank = new HashMap<>();
		for (int i = 0; i < names.length; i++) rank.put(names[i].strip(), i);
		context.getClassDescriptors().sort((left, right) -> {
			int leftRank = rank.getOrDefault(left.getTestClass().getName(), Integer.MAX_VALUE);
			int rightRank = rank.getOrDefault(right.getTestClass().getName(), Integer.MAX_VALUE);
			if (leftRank != rightRank) return Integer.compare(leftRank, rightRank);
			return left.getTestClass().getName().compareTo(right.getTestClass().getName());
		});
	}
}
