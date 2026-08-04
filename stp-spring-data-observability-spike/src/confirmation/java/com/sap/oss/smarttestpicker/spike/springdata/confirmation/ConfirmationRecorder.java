// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.spike.springdata.confirmation;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

final class ConfirmationRecorder implements DisposableBean {
	private final AtomicLong sequence = new AtomicLong();
	private final List<ConfirmationRecord> records = new ArrayList<>();
	private final Environment environment;

	ConfirmationRecorder(Environment environment) {
		this.environment = environment;
	}

	synchronized void add(String hook, String phase, Class<?> repository, Class<?> declaringClass, String method,
			String descriptor, Class<?> domain, String state, Throwable error, boolean transaction, String beanName,
			String type) {
		records.add(new ConfirmationRecord(sequence.incrementAndGet(), hook, phase, name(repository), name(declaringClass),
				method, descriptor, name(domain), state, error == null ? null : error.getClass().getName(), transaction,
				beanName, type));
	}

	@Override
	public synchronized void destroy() throws IOException {
		String output = environment.getProperty("stp.confirmation.output");
		if (output == null || output.isBlank()) return;
		Path path = Path.of(output);
		Files.createDirectories(path.getParent());
		List<ConfirmationRecord> sorted = records.stream().sorted(Comparator.comparingLong(ConfirmationRecord::sequence)).toList();
		StringBuilder json = new StringBuilder("{\n  \"schemaVersion\": \"spring-data-petclinic-confirmation-1\",\n")
				.append("  \"mode\": \"").append(environment.getProperty("stp.confirmation.mode")).append("\",\n")
				.append("  \"records\": [");
		for (int index = 0; index < sorted.size(); index++) {
			if (index > 0) json.append(',');
			append(json, sorted.get(index));
		}
		json.append("\n  ]\n}\n");
		Files.writeString(path, json.toString(), StandardCharsets.UTF_8);
	}

	private static void append(StringBuilder json, ConfirmationRecord value) {
		json.append("\n    {")
				.append(field("sequence", Long.toString(value.sequence()), false, true))
				.append(field("hookName", value.hookName(), true, true))
				.append(field("phase", value.phase(), true, true))
				.append(field("repositoryInterface", value.repositoryInterface(), true, true))
				.append(field("declaringClass", value.declaringClass(), true, true))
				.append(field("methodName", value.methodName(), true, true))
				.append(field("jvmDescriptor", value.jvmDescriptor(), true, true))
				.append(field("domainType", value.domainType(), true, true))
				.append(field("resultState", value.resultState(), true, true))
				.append(field("exceptionType", value.exceptionType(), true, true))
				.append(field("transactionActive", Boolean.toString(value.transactionActive()), false, true))
				.append(field("beanName", value.beanName(), true, true))
				.append(field("proxyOrAdvisorType", value.proxyOrAdvisorType(), true, false)).append("\n    }");
	}

	private static String field(String key, String value, boolean quoted, boolean comma) {
		String rendered = value == null ? "null" : quoted ? "\"" + escape(value) + "\"" : value;
		return "\n      \"" + key + "\": " + rendered + (comma ? "," : "");
	}

	private static String escape(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private static String name(Class<?> type) {
		return type == null ? null : type.getName();
	}
}
