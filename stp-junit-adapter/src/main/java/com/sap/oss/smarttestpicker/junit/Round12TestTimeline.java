// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.junit;

import com.sap.oss.smarttestpicker.runtime.model.TestIdentity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Minimal opt-in lifecycle timestamps for correlating ROUND 12 JFR events. */
final class Round12TestTimeline {
	private final Path output;
	private final Map<String, Start> starts = new ConcurrentHashMap<>();

	Round12TestTimeline() {
		String value = System.getProperty("stp.round12.timeline.output");
		this.output = value == null || value.isBlank() ? null : Path.of(value);
	}

	void started(TestIdentity test) {
		if (output == null) return;
		Thread thread = Thread.currentThread();
		Start start = new Start(Instant.now().toString(), System.nanoTime(), thread.getId(), thread.getName());
		starts.put(test.platformUniqueId(), start);
		write("START", test, start, null, null);
	}

	void finished(TestIdentity test) {
		if (output == null) return;
		Thread thread = Thread.currentThread();
		Start start = starts.remove(test.platformUniqueId());
		write("END", test, start, Instant.now().toString(), System.nanoTime());
	}

	private synchronized void write(String event, TestIdentity test, Start start, String endTime, Long endNanos) {
		Thread current = Thread.currentThread();
		String line = "{\"event\":" + quote(event) + ",\"testIdentity\":"
				+ quote(test.testClass() + "#" + test.testMethod()) + ",\"logicalUniqueId\":"
				+ quote(test.platformUniqueId()) + ",\"startTime\":" + quote(start == null ? null : start.time())
				+ ",\"endTime\":" + quote(endTime) + ",\"startNanoTime\":"
				+ (start == null ? "null" : start.nanos()) + ",\"endNanoTime\":"
				+ (endNanos == null ? "null" : endNanos) + ",\"startThreadId\":"
				+ (start == null ? "null" : start.threadId()) + ",\"startThreadName\":"
				+ quote(start == null ? null : start.threadName()) + ",\"eventThreadId\":" + current.getId()
				+ ",\"eventThreadName\":" + quote(current.getName()) + "}\n";
		try {
			Files.writeString(output, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException failure) {
			System.err.println("[stp-round12] timeline write failed: " + failure.getClass().getSimpleName());
		}
	}

	private static String quote(String value) {
		if (value == null) return "null";
		return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	private record Start(String time, long nanos, long threadId, String threadName) { }
}
