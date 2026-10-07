// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RemoteObservationJsonTest {
	@Test void distinguishesValidSchemaV2FromMalformedOrOtherSchemaFiles() {
		assertTrue(RemoteObservationJson.isValidSchemaV2("""
				{"schemaVersion":2,"requests":[{"testSuiteId":"suite","testId":"test","requestId":"request","methods":["example.A#run()V"]}]}
				"""));
		assertTrue(RemoteObservationJson.isValidSchemaV2("{\"schemaVersion\":2,\"source\":{\"serviceId\":\"svc\",\"instanceId\":\"i1\",\"revision\":\"abc\"},\"requests\":[]}"));
		assertFalse(RemoteObservationJson.isValidSchemaV2("{\"schemaVersion\":2,\"source\":{\"serviceId\":\"svc\",\"revision\":\"abc\"},\"requests\":[]}"));
		assertTrue(RemoteObservationJson.isValidSchemaV2("{\"schemaVersion\":2,\"requests\":[]}"));
		assertFalse(RemoteObservationJson.isValidSchemaV2("{\"schemaVersion\":1,\"requests\":[]}"));
		assertFalse(RemoteObservationJson.isValidSchemaV2("{\"schemaVersion\":2,\"requests\":["));
		assertFalse(RemoteObservationJson.isValidSchemaV2("{\"schemaVersion\":2,\"schemaVersion\":2,\"requests\":[]}"));
		assertFalse(RemoteObservationJson.isValidSchemaV2("{\"schemaVersion\":2,\"requests\":[{\"testId\":\"test\",\"requestId\":\"r\",\"methods\":[]}] }"));
	}
}
