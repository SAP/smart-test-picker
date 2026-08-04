// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.runtime;

import com.sap.oss.smarttestpicker.runtime.model.Certainty;
import com.sap.oss.smarttestpicker.runtime.model.Evidence;
import com.sap.oss.smarttestpicker.runtime.model.EvidenceSource;
import com.sap.oss.smarttestpicker.runtime.model.MethodHitEvent;
import com.sap.oss.smarttestpicker.runtime.model.MethodIdentity;
import com.sap.oss.smarttestpicker.runtime.model.TestExecutionStatus;
import com.sap.oss.smarttestpicker.runtime.model.TestIdentity;
import com.sap.oss.smarttestpicker.runtime.model.TestResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeContextServiceTest {
	private static final RuntimeJsonSerializer JSON = new RuntimeJsonSerializer();
	private static final TestResult SUCCESS = new TestResult(TestExecutionStatus.SUCCESSFUL, null, null);

	@Test
	void immediateSameThreadEventAfterCompletionIsLateForFinishedTest() {
		RuntimeContextService service = service();
		TestIdentity finished = test("finished");
		service.beginTest(finished);
		service.endTest(finished, SUCCESS);
		service.record(method("immediateLate"));

		String json = JSON.serialize(service.aggregator());
		assertTrue(testSection(json, "finished").contains("\"reason\":\"LATE_EVENT\""));
		assertTrue(testSection(json, "finished").contains("Target#immediateLate()V"));
	}

	@Test
	void beginningNextTestClearsPreviousFinishedMarker() {
		RuntimeContextService service = service();
		TestIdentity first = test("first");
		TestIdentity second = test("second");
		service.beginTest(first);
		service.endTest(first, SUCCESS);
		service.beginTest(second);
		service.record(method("onlySecond"));
		service.endTest(second, SUCCESS);

		String json = JSON.serialize(service.aggregator());
		assertFalse(testSection(json, "first").contains("onlySecond"));
		assertTrue(testSection(json, "second").contains("Target#onlySecond()V"));
	}

	@Test
	void unrelatedThreadWithoutLastFinishedMarkerRecordsGlobalNoActiveTest() throws Exception {
		RuntimeContextService service = service();
		TestIdentity finished = test("main-thread-finished");
		service.beginTest(finished);
		service.endTest(finished, SUCCESS);
		Thread unrelated = new Thread(() -> service.record(method("unrelatedThread")), "unrelated-observer");
		unrelated.start();
		unrelated.join();

		String json = JSON.serialize(service.aggregator());
		assertFalse(testSection(json, "main-thread-finished").contains("unrelatedThread"));
		String global = json.substring(json.lastIndexOf("\"unattributedEvents\""));
		assertTrue(global.contains("\"reason\":\"NO_ACTIVE_TEST\""));
		assertTrue(global.contains("Target#unrelatedThread()V"));
	}

	private static RuntimeContextService service() {
		return new RuntimeContextService(new RuntimeEventAggregator("run-1", "jvm-1"));
	}

	private static TestIdentity test(String id) {
		return new TestIdentity(id, id, "fixture.Test", id, "junit-jupiter", "run-1", "jvm-1");
	}

	private static MethodHitEvent method(String name) {
		return new MethodHitEvent(new MethodIdentity("fixture.Target", name, "()V"),
				new Evidence(EvidenceSource.ASM_METHOD_ENTRY, Certainty.OBSERVED));
	}

	private static String testSection(String json, String testId) {
		int start = json.indexOf("\"testId\": \"" + testId + "\"");
		int next = json.indexOf("\"testId\": \"", start + 1);
		return json.substring(start, next < 0 ? json.indexOf("\n  ],", start) : next);
	}
}
