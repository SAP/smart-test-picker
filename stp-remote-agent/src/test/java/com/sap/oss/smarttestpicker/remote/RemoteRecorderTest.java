// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;

class RemoteRecorderTest {
	@Test void storesSeparateMethodSetsForDifferentRequestIdsOfTheSameTest() throws Exception {
		var output = Files.createTempDirectory("remote-schema-v2").resolve("observations.json");
		RemoteRecorder.clearForTests();
		RemoteRecorder.install(output);
		RemoteRequestIdentity first = TestRequests.request("same-test");
		RemoteRequestIdentity second = TestRequests.request("same-test");
		try (var scope = RemoteTestContext.enter(first)) { RemoteRecorder.methodHit("example.Vet", "list", "()V"); }
		try (var scope = RemoteTestContext.enter(second)) { RemoteRecorder.methodHit("example.Owner", "show", "()V"); }
		RemoteRecorder.writeOutput();
		String json = Files.readString(output);
		assertTrue(json.contains("\"schemaVersion\": 2"), json);
		assertTrue(json.contains("\"requests\""), json);
		assertEquals(2, occurrences(json, "\"testSuiteId\":\"fixture-suite\""), json);
		assertEquals(2, occurrences(json, "\"testId\":\"same-test\""), json);
		assertNotEquals(first.requestId(), second.requestId());
		assertTrue(json.contains("Vet#list"), json);
		assertTrue(json.contains("Owner#show"), json);
		assertTrue(methodsFor(json, first.requestId()).contains("Vet#list"));
		assertFalse(methodsFor(json, first.requestId()).contains("Owner#show"));
		assertTrue(methodsFor(json, second.requestId()).contains("Owner#show"));
		assertFalse(methodsFor(json, second.requestId()).contains("Vet#list"));
	}

	private static String methodsFor(String json, String requestId) {
		int start = json.indexOf("\"requestId\":\"" + requestId + "\"");
		assertTrue(start >= 0, json);
		return json.substring(start, json.indexOf('}', start));
	}

	private static int occurrences(String text, String needle) {
		int count = 0;
		for (int offset = 0; (offset = text.indexOf(needle, offset)) >= 0; offset += needle.length()) count++;
		return count;
	}
}
