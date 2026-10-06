// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

record RemoteAgentConfiguration(Path output, String header, List<String> includes, List<String> excludes) {
	static RemoteAgentConfiguration parse(String arguments) {
		if (arguments == null || arguments.isBlank()) throw new IllegalArgumentException("agent arguments are required");
		Path output = null;
		String header = "X-STP-Test-Execution-Id";
		List<String> includes = new ArrayList<>();
		List<String> excludes = new ArrayList<>();
		for (String item : arguments.split(";")) {
			int separator = item.indexOf('=');
			if (separator < 1) throw new IllegalArgumentException("invalid agent argument: " + item);
			String key = item.substring(0, separator).trim();
			String value = item.substring(separator + 1).trim();
			if (value.isEmpty()) throw new IllegalArgumentException(key + " must not be empty");
			switch (key) {
				case "output" -> output = Path.of(value).toAbsolutePath().normalize();
				case "header" -> header = value;
				case "includes" -> addPrefixes(includes, value);
				case "excludes" -> addPrefixes(excludes, value);
				default -> throw new IllegalArgumentException("unknown agent argument: " + key);
			}
		}
		if (output == null) throw new IllegalArgumentException("output is required");
		if (includes.isEmpty()) throw new IllegalArgumentException("at least one application include prefix is required");
		if (!header.matches("[A-Za-z0-9-]{1,128}")) throw new IllegalArgumentException("invalid HTTP header name");
		return new RemoteAgentConfiguration(output, header, List.copyOf(includes), List.copyOf(excludes));
	}

	boolean instruments(String className) {
		if (className == null || excludes.stream().anyMatch(className::startsWith)) return false;
		return includes.stream().anyMatch(className::startsWith);
	}

	private static void addPrefixes(List<String> target, String value) {
		for (String prefix : value.split(",")) {
			String normalized = prefix.trim();
			if (normalized.isEmpty()) throw new IllegalArgumentException("package prefixes must not be empty");
			target.add(normalized);
		}
	}
}
