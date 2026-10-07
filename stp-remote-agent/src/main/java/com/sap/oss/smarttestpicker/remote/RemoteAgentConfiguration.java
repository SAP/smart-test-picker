// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import java.nio.file.Path;
import java.nio.file.InvalidPathException;
import java.util.ArrayList;
import java.util.List;

record RemoteAgentConfiguration(Path output, List<String> includes, List<String> excludes, Integer debugPort,
		int flushIntervalSeconds) {
	static final int DEFAULT_FLUSH_INTERVAL_SECONDS = 60;
	static RemoteAgentConfiguration parse(String arguments) {
		if (arguments == null || arguments.isBlank()) throw new IllegalArgumentException("agent arguments are required");
		Path output = null;
		List<String> includes = new ArrayList<>();
		List<String> excludes = new ArrayList<>();
		Integer debugPort = null;
		int flushIntervalSeconds = DEFAULT_FLUSH_INTERVAL_SECONDS;
		for (String item : arguments.split(";")) {
			int separator = item.indexOf('=');
			if (separator < 1) throw new IllegalArgumentException("invalid agent argument: " + item);
			String key = item.substring(0, separator).trim();
			String value = item.substring(separator + 1).trim();
			if (value.isEmpty()) throw new IllegalArgumentException(key + " must not be empty");
			switch (key) {
				case "output" -> {
					try { output = Path.of(value).toAbsolutePath().normalize(); }
					catch (InvalidPathException invalid) { throw new IllegalArgumentException("invalid output path '" + value + "'", invalid); }
				}
				case "includes" -> addPrefixes(includes, value);
				case "excludes" -> addPrefixes(excludes, value);
				case "debugPort" -> {
					try { debugPort = Integer.valueOf(value); }
					catch (NumberFormatException invalid) { throw new IllegalArgumentException("debugPort must be an integer from 1 to 65535: " + value, invalid); }
					if (debugPort < 1 || debugPort > 65535)
						throw new IllegalArgumentException("debugPort must be an integer from 1 to 65535: " + value);
				}
				case "flushIntervalSeconds" -> {
					try { flushIntervalSeconds = Integer.parseInt(value); }
					catch (NumberFormatException invalid) {
						throw new IllegalArgumentException("flushIntervalSeconds must be an integer >= 1: " + value, invalid);
					}
					if (flushIntervalSeconds < 1)
						throw new IllegalArgumentException("flushIntervalSeconds must be an integer >= 1: " + value);
				}
				default -> throw new IllegalArgumentException("unknown agent argument: " + key);
			}
		}
		if (output == null) throw new IllegalArgumentException("output is required");
		if (includes.isEmpty()) throw new IllegalArgumentException("at least one application include prefix is required");
		return new RemoteAgentConfiguration(output, List.copyOf(includes), List.copyOf(excludes), debugPort,
				flushIntervalSeconds);
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
