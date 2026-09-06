// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.runtime;

import com.sap.oss.smarttestpicker.runtime.model.Certainty;
import com.sap.oss.smarttestpicker.runtime.model.Evidence;
import com.sap.oss.smarttestpicker.runtime.model.EvidenceSource;
import com.sap.oss.smarttestpicker.runtime.model.MethodHitEvent;
import com.sap.oss.smarttestpicker.runtime.model.MethodIdentity;
import com.sap.oss.smarttestpicker.runtime.model.TestIdentity;
import com.sap.oss.smarttestpicker.runtime.model.UnattributedEvent;
import com.sap.oss.smarttestpicker.runtime.model.UnattributedReason;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeEventAggregatorTest {
	private static final Evidence ASM = new Evidence(EvidenceSource.ASM_METHOD_ENTRY, Certainty.OBSERVED);
	private static final RuntimeJsonSerializer JSON = new RuntimeJsonSerializer();

	@Test
	void deduplicatesAndCountsMethodHits() {
		RuntimeEventAggregator aggregator = aggregatorWithTest("test-a");
		TestIdentity test = test("test-a");
		aggregator.record(test, method("example.Owner", "save", "()V"));
		aggregator.record(test, method("example.Owner", "save", "()V"));
		String json = JSON.serialize(aggregator);
		assertEquals(1, occurrences(json, "example.Owner#save()V"));
		assertTrue(json.contains("\"count\":2"));
		assertTrue(json.contains("\"rawMethodHits\": 2"));
		assertTrue(json.contains("\"uniqueMethodHits\": 1"));
	}

	@Test
	void convertsAnEventAfterFinishToLateEvent() {
		RuntimeEventAggregator aggregator = aggregatorWithTest("test-a");
		TestIdentity test = test("test-a");
		aggregator.finishTest(test);
		aggregator.record(test, method("example.Owner", "late", "()V"));
		String json = JSON.serialize(aggregator);
		assertTrue(json.contains("\"reason\":\"LATE_EVENT\""));
		assertTrue(json.contains("\"eventIdentity\":\"example.Owner#late()V\""));
		assertTrue(json.contains("\"methods\": []"));
	}

	@Test
	void recordsUnknownContextInTheGlobalUnattributedBucket() {
		RuntimeEventAggregator aggregator = new RuntimeEventAggregator("run-1", "jvm-1");
		aggregator.record(test("missing"), method("example.Owner", "save", "()V"));
		assertTrue(JSON.serialize(aggregator).contains("\"reason\":\"UNKNOWN_CONTEXT\""));
	}

	@Test
	void preservesExplicitNoActiveTestAccounting() {
		RuntimeEventAggregator aggregator = new RuntimeEventAggregator("run-1", "jvm-1");
		aggregator.recordUnattributed(new UnattributedEvent(UnattributedReason.NO_ACTIVE_TEST,
				"METHOD", "example.Owner#save()V", ASM));
		assertTrue(JSON.serialize(aggregator).contains("\"reason\":\"NO_ACTIVE_TEST\""));
	}

	@Test
	void keepsTwoTestsFreeFromCrossContamination() {
		RuntimeEventAggregator aggregator = new RuntimeEventAggregator("run-1", "jvm-1");
		TestIdentity first = test("test-a");
		TestIdentity second = test("test-b");
		aggregator.beginTest(first);
		aggregator.beginTest(second);
		aggregator.record(first, method("example.First", "onlyFirst", "()V"));
		aggregator.record(second, method("example.Second", "onlySecond", "()V"));
		String json = JSON.serialize(aggregator);
		String firstSection = json.substring(json.indexOf("\"testId\": \"test-a\""),
				json.indexOf("\"testId\": \"test-b\""));
		String secondSection = json.substring(json.indexOf("\"testId\": \"test-b\""));
		assertTrue(firstSection.contains("example.First#onlyFirst()V"));
		assertFalse(firstSection.contains("example.Second#onlySecond()V"));
		assertTrue(secondSection.contains("example.Second#onlySecond()V"));
		assertFalse(secondSection.contains("example.First#onlyFirst()V"));
	}

	@Test
	void outputOrderingDoesNotDependOnInsertionOrder() {
		RuntimeEventAggregator forward = aggregatorWithTest("test-a");
		RuntimeEventAggregator reverse = aggregatorWithTest("test-a");
		forward.record(test("test-a"), method("z.Type", "z", "()V"));
		forward.record(test("test-a"), method("a.Type", "a", "()V"));
		reverse.record(test("test-a"), method("a.Type", "a", "()V"));
		reverse.record(test("test-a"), method("z.Type", "z", "()V"));
		assertEquals(JSON.serialize(forward), JSON.serialize(reverse));
	}

	private static RuntimeEventAggregator aggregatorWithTest(String id) {
		RuntimeEventAggregator aggregator = new RuntimeEventAggregator("run-1", "jvm-1");
		aggregator.beginTest(test(id));
		return aggregator;
	}

	private static TestIdentity test(String id) {
		return new TestIdentity(id, id, "example.Test", id, "junit-jupiter", "run-1", "jvm-1");
	}

	private static MethodHitEvent method(String type, String name, String descriptor) {
		return new MethodHitEvent(new MethodIdentity(type, name, descriptor), ASM);
	}

	private static int occurrences(String value, String needle) {
		return (value.length() - value.replace(needle, "").length()) / needle.length();
	}
}
