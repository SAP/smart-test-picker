// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.runtime;

import com.sap.oss.smarttestpicker.runtime.RuntimeEventAggregator.TestBucketSnapshot;
import com.sap.oss.smarttestpicker.runtime.model.EndpointEvent;
import com.sap.oss.smarttestpicker.runtime.model.EntityEvent;
import com.sap.oss.smarttestpicker.runtime.model.Evidence;
import com.sap.oss.smarttestpicker.runtime.model.MethodHitEvent;
import com.sap.oss.smarttestpicker.runtime.model.RepositoryInvocationEvent;
import com.sap.oss.smarttestpicker.runtime.model.SpringBeanEvent;
import com.sap.oss.smarttestpicker.runtime.model.TableAccess;
import com.sap.oss.smarttestpicker.runtime.model.TableEvent;
import com.sap.oss.smarttestpicker.runtime.model.UnattributedEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Writes the timestamp-free canonical {@code spike-2} JSON representation. */
public final class RuntimeJsonSerializer {
	public String serialize(RuntimeEventAggregator aggregator) {
		StringBuilder json = new StringBuilder(1024);
		json.append("{\n");
		field(json, 1, "schemaVersion", "spike-2", true);
		field(json, 1, "runId", aggregator.runId(), true);
		field(json, 1, "jvmId", aggregator.jvmId(), true);
		json.append(indent(1)).append("\"tests\": [");
		List<TestBucketSnapshot> tests = aggregator.testSnapshots();
		if (tests.isEmpty()) {
			json.append("],\n");
		} else {
			json.append('\n');
		}
		for (int i = 0; i < tests.size(); i++) {
			writeTest(json, tests.get(i), 2);
			json.append(i + 1 < tests.size() ? ",\n" : "\n");
		}
		if (!tests.isEmpty()) json.append(indent(1)).append("],\n");
		json.append(indent(1)).append("\"unattributedEvents\": ");
		writeArray(json, sorted(aggregator.unattributedSnapshot(), RuntimeJsonSerializer::unattributedKey), 1,
				RuntimeJsonSerializer::writeUnattributed);
		json.append('\n').append("}\n");
		return json.toString();
	}

	private static void writeTest(StringBuilder json, TestBucketSnapshot test, int level) {
		json.append(indent(level)).append("{\n");
		field(json, level + 1, "testId", test.identity.platformUniqueId(), true);
		field(json, level + 1, "displayName", test.identity.displayName(), true);
		field(json, level + 1, "testClass", test.identity.testClass(), true);
		field(json, level + 1, "testMethod", test.identity.testMethod(), true);
		field(json, level + 1, "engineId", test.identity.engineId(), true);
		json.append(indent(level + 1)).append("\"result\": ");
		writeResult(json, test.result);
		json.append(",\n");
		writeNamedArray(json, level + 1, "methods", sorted(test.methods, e -> e.method().canonicalKey()),
				RuntimeJsonSerializer::writeMethod, true);
		writeNamedArray(json, level + 1, "springBeans", sorted(test.springBeans,
				e -> e.beanName() + "\u0000" + e.binaryClassName()), RuntimeJsonSerializer::writeBean, true);
		writeNamedArray(json, level + 1, "endpoints", sorted(test.endpoints,
				e -> e.httpMethod() + "\u0000" + e.routePattern() + "\u0000" + e.handler().canonicalKey()),
				RuntimeJsonSerializer::writeEndpoint, true);
		writeNamedArray(json, level + 1, "repositories", sorted(test.repositories,
				RuntimeJsonSerializer::repositoryKey),
				RuntimeJsonSerializer::writeRepository, true);
		writeNamedArray(json, level + 1, "entities", sorted(test.entities,
				e -> e.binaryClassName() + evidenceKey(e.evidence())), RuntimeJsonSerializer::writeEntity, true);
		json.append(indent(level + 1)).append("\"tables\": {\n");
		writeNamedArray(json, level + 2, "mapped", sorted(filterTables(test.tables, true),
				e -> tableKey(e)), RuntimeJsonSerializer::writeTable, true);
		writeNamedArray(json, level + 2, "observed", sorted(filterTables(test.tables, false),
				e -> tableKey(e)), RuntimeJsonSerializer::writeTable, false);
		json.append(indent(level + 1)).append("},\n");
		writeNamedArray(json, level + 1, "unattributedEvents", sorted(test.unattributed,
				RuntimeJsonSerializer::unattributedKey), RuntimeJsonSerializer::writeUnattributed, true);
		int rawMethods = test.methods.values().stream().mapToInt(Integer::intValue).sum();
		json.append(indent(level + 1)).append("\"metrics\": {\n");
		numberField(json, level + 2, "rawMethodHits", rawMethods, true);
		numberField(json, level + 2, "uniqueMethodHits", test.methods.size(), false);
		json.append(indent(level + 1)).append("}\n");
		json.append(indent(level)).append('}');
	}

	private static void writeResult(StringBuilder json, com.sap.oss.smarttestpicker.runtime.model.TestResult result) {
		if (result == null) {
			json.append("null");
			return;
		}
		json.append('{');
		property(json, "status", result.status().name(), true);
		property(json, "failureType", result.failureType(), true);
		property(json, "failureMessage", result.failureMessage(), false);
		json.append('}');
	}

	private static Map<TableEvent, Integer> filterTables(Map<TableEvent, Integer> source, boolean mapped) {
		java.util.LinkedHashMap<TableEvent, Integer> result = new java.util.LinkedHashMap<>();
		source.forEach((event, count) -> {
			if ((event.access() == TableAccess.MAPPED) == mapped) result.put(event, count);
		});
		return result;
	}

	private static void writeMethod(StringBuilder json, MethodHitEvent event, int count, int level) {
		objectStart(json, level);
		property(json, "methodId", event.methodId() == null ? null : Long.toUnsignedString(event.methodId()), true);
		property(json, "method", event.method().canonicalKey(), true);
		evidenceProperties(json, event.evidence(), true);
		numberProperty(json, "count", count, false);
		json.append('}');
	}

	private static void writeBean(StringBuilder json, SpringBeanEvent event, int count, int level) {
		objectStart(json, level);
		property(json, "beanName", event.beanName(), true);
		property(json, "className", event.binaryClassName(), true);
		evidenceProperties(json, event.evidence(), true);
		numberProperty(json, "count", count, false);
		json.append('}');
	}

	private static void writeEndpoint(StringBuilder json, EndpointEvent event, int count, int level) {
		objectStart(json, level);
		property(json, "httpMethod", event.httpMethod(), true);
		property(json, "routePattern", event.routePattern(), true);
		property(json, "handler", event.handler().canonicalKey(), true);
		evidenceProperties(json, event.evidence(), true);
		numberProperty(json, "count", count, false);
		json.append('}');
	}

	private static void writeRepository(StringBuilder json, RepositoryInvocationEvent event, int count, int level) {
		objectStart(json, level);
		property(json, "repositoryKind", event.repositoryKind().name(), true);
		property(json, "repositoryInterface", event.repositoryInterface(), true);
		property(json, "beanName", event.beanName(), true);
		property(json, "methodName", event.methodName(), true);
		property(json, "jvmDescriptor", event.jvmDescriptor(), true);
		property(json, "domainType", event.domainType(), true);
		property(json, "outcome", event.outcome().name(), true);
		evidenceProperties(json, event.evidence(), true);
		numberProperty(json, "count", count, false);
		json.append('}');
	}

	static String repositoryKey(RepositoryInvocationEvent event) {
		return event.repositoryKind() + "\u0000" + event.repositoryInterface() + "\u0000" + event.beanName()
				+ "\u0000" + event.methodName() + "\u0000" + event.jvmDescriptor() + "\u0000" + event.domainType()
				+ "\u0000" + event.outcome() + evidenceKey(event.evidence());
	}

	private static void writeEntity(StringBuilder json, EntityEvent event, int count, int level) {
		objectStart(json, level);
		property(json, "className", event.binaryClassName(), true);
		evidenceProperties(json, event.evidence(), true);
		numberProperty(json, "count", count, false);
		json.append('}');
	}

	private static void writeTable(StringBuilder json, TableEvent event, int count, int level) {
		objectStart(json, level);
		property(json, "tableName", event.tableName(), true);
		property(json, "access", event.access().name(), true);
		evidenceProperties(json, event.evidence(), true);
		numberProperty(json, "count", count, false);
		json.append('}');
	}

	private static void writeUnattributed(StringBuilder json, UnattributedEvent event, int count, int level) {
		objectStart(json, level);
		property(json, "reason", event.reason().name(), true);
		property(json, "eventType", event.eventType(), true);
		property(json, "eventIdentity", event.eventIdentity(), true);
		evidenceProperties(json, event.evidence(), true);
		numberProperty(json, "count", count, false);
		json.append('}');
	}

	private static <T> List<Map.Entry<T, Integer>> sorted(Map<T, Integer> values, Function<T, String> key) {
		List<Map.Entry<T, Integer>> result = new ArrayList<>(values.entrySet());
		result.sort(Comparator.comparing(entry -> key.apply(entry.getKey())));
		return result;
	}

	private static <T> void writeNamedArray(StringBuilder json, int level, String name,
			List<Map.Entry<T, Integer>> values, ItemWriter<T> writer, boolean comma) {
		json.append(indent(level)).append(quote(name)).append(": ");
		writeArray(json, values, level, writer);
		json.append(comma ? ",\n" : "\n");
	}

	private static <T> void writeArray(StringBuilder json, List<Map.Entry<T, Integer>> values, int level,
			ItemWriter<T> writer) {
		if (values.isEmpty()) {
			json.append("[]");
			return;
		}
		json.append("[\n");
		for (int i = 0; i < values.size(); i++) {
			Map.Entry<T, Integer> entry = values.get(i);
			writer.write(json, entry.getKey(), entry.getValue(), level + 1);
			json.append(i + 1 < values.size() ? ",\n" : "\n");
		}
		json.append(indent(level)).append(']');
	}

	private static String tableKey(TableEvent event) {
		return event.tableName() + "\u0000" + event.access() + evidenceKey(event.evidence());
	}

	private static String unattributedKey(UnattributedEvent event) {
		return event.reason() + "\u0000" + event.eventType() + "\u0000" + event.eventIdentity()
				+ evidenceKey(event.evidence());
	}

	private static String evidenceKey(Evidence evidence) {
		return "\u0000" + evidence.source() + "\u0000" + evidence.certainty();
	}

	private static void evidenceProperties(StringBuilder json, Evidence evidence, boolean comma) {
		property(json, "evidenceSource", evidence.source().name(), true);
		property(json, "certainty", evidence.certainty().name(), comma);
	}

	private static void objectStart(StringBuilder json, int level) {
		json.append(indent(level)).append('{');
	}

	private static void field(StringBuilder json, int level, String name, String value, boolean comma) {
		json.append(indent(level)).append(quote(name)).append(": ").append(nullableQuote(value));
		json.append(comma ? ",\n" : "\n");
	}

	private static void numberField(StringBuilder json, int level, String name, int value, boolean comma) {
		json.append(indent(level)).append(quote(name)).append(": ").append(value);
		json.append(comma ? ",\n" : "\n");
	}

	private static void property(StringBuilder json, String name, String value, boolean comma) {
		json.append(quote(name)).append(':').append(nullableQuote(value));
		if (comma) json.append(',');
	}

	private static void numberProperty(StringBuilder json, String name, int value, boolean comma) {
		json.append(quote(name)).append(':').append(value);
		if (comma) json.append(',');
	}

	private static String nullableQuote(String value) {
		return value == null ? "null" : quote(value);
	}

	private static String quote(String value) {
		StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '"' -> escaped.append("\\\"");
				case '\\' -> escaped.append("\\\\");
				case '\b' -> escaped.append("\\b");
				case '\f' -> escaped.append("\\f");
				case '\n' -> escaped.append("\\n");
				case '\r' -> escaped.append("\\r");
				case '\t' -> escaped.append("\\t");
				default -> {
					if (c < 0x20) escaped.append(String.format("\\u%04x", (int) c));
					else escaped.append(c);
				}
			}
		}
		return escaped.append('"').toString();
	}

	private static String indent(int level) {
		return "  ".repeat(level);
	}

	@FunctionalInterface
	private interface ItemWriter<T> {
		void write(StringBuilder json, T value, int count, int level);
	}
}
