// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.runtime;

import com.sap.oss.smarttestpicker.runtime.model.EndpointEvent;
import com.sap.oss.smarttestpicker.runtime.model.EntityEvent;
import com.sap.oss.smarttestpicker.runtime.model.MethodHitEvent;
import com.sap.oss.smarttestpicker.runtime.model.RepositoryInvocationEvent;
import com.sap.oss.smarttestpicker.runtime.model.RuntimeEvent;
import com.sap.oss.smarttestpicker.runtime.model.SpringBeanEvent;
import com.sap.oss.smarttestpicker.runtime.model.TableEvent;
import com.sap.oss.smarttestpicker.runtime.model.TestIdentity;
import com.sap.oss.smarttestpicker.runtime.model.TestResult;
import com.sap.oss.smarttestpicker.runtime.model.UnattributedEvent;
import com.sap.oss.smarttestpicker.runtime.model.UnattributedReason;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Experimental, explicitly driven in-memory event aggregator. */
public final class RuntimeEventAggregator {
	private final String runId;
	private final String jvmId;
	private final Map<TestIdentity, TestBucket> tests = new TreeMap<>();
	private final Map<UnattributedEvent, Integer> unattributed = new LinkedHashMap<>();

	public RuntimeEventAggregator(String runId, String jvmId) {
		this.runId = requireText(runId, "runId");
		this.jvmId = requireText(jvmId, "jvmId");
	}

	public synchronized void beginTest(TestIdentity test) {
		validateScope(test);
		TestBucket existing = tests.get(test);
		if (existing != null) {
			throw new IllegalStateException("test already registered: " + test.platformUniqueId());
		}
		tests.put(test, new TestBucket(test));
	}

	public synchronized void record(TestIdentity test, RuntimeEvent event) {
		validateScope(test);
		Objects.requireNonNull(event, "event");
		TestBucket bucket = tests.get(test);
		if (bucket == null) {
			recordUnattributed(EventFacts.unattributed(UnattributedReason.UNKNOWN_CONTEXT, event));
			return;
		}
		if (bucket.finished) {
			bucket.addUnattributed(EventFacts.unattributed(UnattributedReason.LATE_EVENT, event));
			return;
		}
		bucket.add(event);
	}

	public synchronized void finishTest(TestIdentity test) {
		endTest(test, null);
	}

	public synchronized void endTest(TestIdentity test, TestResult result) {
		validateScope(test);
		TestBucket bucket = tests.get(test);
		if (bucket == null) {
			throw new IllegalStateException("test was not begun: " + test.platformUniqueId());
		}
		if (bucket.finished) {
			throw new IllegalStateException("test already finished: " + test.platformUniqueId());
		}
		bucket.result = result;
		bucket.finished = true;
	}

	public synchronized void recordUnattributed(UnattributedEvent event) {
		Objects.requireNonNull(event, "event");
		unattributed.merge(event, 1, Integer::sum);
	}

	public synchronized void recordUnattributed(UnattributedReason reason, RuntimeEvent event) {
		Objects.requireNonNull(reason, "reason");
		Objects.requireNonNull(event, "event");
		recordUnattributed(EventFacts.unattributed(reason, event));
	}

	String runId() {
		return runId;
	}

	String jvmId() {
		return jvmId;
	}

	synchronized List<TestBucketSnapshot> testSnapshots() {
		List<TestBucketSnapshot> snapshots = new ArrayList<>();
		for (TestBucket bucket : tests.values()) {
			snapshots.add(bucket.snapshot());
		}
		return List.copyOf(snapshots);
	}

	synchronized Map<UnattributedEvent, Integer> unattributedSnapshot() {
		return Collections.unmodifiableMap(new LinkedHashMap<>(unattributed));
	}

	private void validateScope(TestIdentity test) {
		Objects.requireNonNull(test, "test");
		if (!runId.equals(test.runId()) || !jvmId.equals(test.jvmId())) {
			throw new IllegalArgumentException("test identity belongs to a different run or JVM");
		}
	}

	private static String requireText(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return value;
	}

	static final class TestBucketSnapshot {
		final TestIdentity identity;
		final TestResult result;
		final Map<MethodHitEvent, Integer> methods;
		final Map<SpringBeanEvent, Integer> springBeans;
		final Map<EndpointEvent, Integer> endpoints;
		final Map<RepositoryInvocationEvent, Integer> repositories;
		final Map<EntityEvent, Integer> entities;
		final Map<TableEvent, Integer> tables;
		final Map<UnattributedEvent, Integer> unattributed;

		TestBucketSnapshot(TestBucket bucket) {
			identity = bucket.identity;
			result = bucket.result;
			methods = copy(bucket.methods);
			springBeans = copy(bucket.springBeans);
			endpoints = copy(bucket.endpoints);
			repositories = copy(bucket.repositories);
			entities = copy(bucket.entities);
			tables = copy(bucket.tables);
			unattributed = copy(bucket.unattributed);
		}

		private static <T> Map<T, Integer> copy(Map<T, Integer> source) {
			return Collections.unmodifiableMap(new LinkedHashMap<>(source));
		}
	}

	private static final class TestBucket {
		private final TestIdentity identity;
		private final Map<MethodHitEvent, Integer> methods = new LinkedHashMap<>();
		private final Map<SpringBeanEvent, Integer> springBeans = new LinkedHashMap<>();
		private final Map<EndpointEvent, Integer> endpoints = new LinkedHashMap<>();
		private final Map<RepositoryInvocationEvent, Integer> repositories = new LinkedHashMap<>();
		private final Map<EntityEvent, Integer> entities = new LinkedHashMap<>();
		private final Map<TableEvent, Integer> tables = new LinkedHashMap<>();
		private final Map<UnattributedEvent, Integer> unattributed = new LinkedHashMap<>();
		private boolean finished;
		private TestResult result;

		private TestBucket(TestIdentity identity) {
			this.identity = identity;
		}

		private void add(RuntimeEvent event) {
			if (event instanceof MethodHitEvent value) methods.merge(value, 1, Integer::sum);
			else if (event instanceof SpringBeanEvent value) springBeans.merge(value, 1, Integer::sum);
			else if (event instanceof EndpointEvent value) endpoints.merge(value, 1, Integer::sum);
			else if (event instanceof RepositoryInvocationEvent value) repositories.merge(value, 1, Integer::sum);
			else if (event instanceof EntityEvent value) entities.merge(value, 1, Integer::sum);
			else if (event instanceof TableEvent value) tables.merge(value, 1, Integer::sum);
			else if (event instanceof UnattributedEvent value) addUnattributed(value);
		}

		private void addUnattributed(UnattributedEvent event) {
			unattributed.merge(event, 1, Integer::sum);
		}

		private TestBucketSnapshot snapshot() {
			return new TestBucketSnapshot(this);
		}
	}
}
