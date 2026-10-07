// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Minimal strict JSON/schema-v2 validator used only to distinguish completed files from crash artifacts. */
final class RemoteObservationJson {
	private RemoteObservationJson() { }

	static boolean isValidSchemaV2(String source) {
		try {
			Object parsed = new Parser(source).parse();
			if (!(parsed instanceof Map<?, ?> root) || !(root.get("schemaVersion") instanceof BigDecimal version)
					|| version.compareTo(BigDecimal.valueOf(2)) != 0 || !(root.get("requests") instanceof List<?> requests)) return false;
			if (root.containsKey("source")) {
				if (!(root.get("source") instanceof Map<?, ?> metadata)
						|| !(metadata.get("serviceId") instanceof String serviceId)
						|| !(metadata.get("instanceId") instanceof String instanceId)
						|| !(metadata.get("revision") instanceof String revision)) return false;
				try { new RemoteFragmentMetadata(serviceId, instanceId, revision); }
				catch (IllegalArgumentException invalidSource) { return false; }
			}
			for (Object item : requests) {
				if (!(item instanceof Map<?, ?> request)
						|| !(request.get("testSuiteId") instanceof String suite)
						|| !(request.get("testId") instanceof String test)
						|| !(request.get("requestId") instanceof String requestId)
						|| !(request.get("methods") instanceof List<?> methods)) return false;
				try { new RemoteRequestIdentity(suite, test, requestId); }
				catch (IllegalArgumentException invalidIdentity) { return false; }
				if (methods.stream().anyMatch(method -> !(method instanceof String))) return false;
			}
			return true;
		} catch (RuntimeException malformed) {
			return false;
		}
	}

	private static final class Parser {
		private final String source;
		private int offset;
		private Parser(String source) { this.source = source; }

		private Object parse() {
			Object value = value();
			whitespace();
			if (offset != source.length()) fail();
			return value;
		}
		private Object value() {
			whitespace();
			if (offset >= source.length()) return fail();
			return switch (source.charAt(offset)) {
				case '{' -> object();
				case '[' -> array();
				case '"' -> string();
				case 't' -> literal("true", Boolean.TRUE);
				case 'f' -> literal("false", Boolean.FALSE);
				case 'n' -> literal("null", null);
				default -> number();
			};
		}
		private Map<String, Object> object() {
			expect('{'); whitespace();
			Map<String, Object> result = new java.util.LinkedHashMap<>();
			if (take('}')) return result;
			while (true) {
				whitespace();
				if (offset >= source.length() || source.charAt(offset) != '"') return fail();
				String key = string(); whitespace(); expect(':');
				if (result.containsKey(key)) return fail();
				result.put(key, value());
				whitespace();
				if (take('}')) return result;
				expect(',');
			}
		}
		private List<Object> array() {
			expect('['); whitespace();
			List<Object> result = new ArrayList<>();
			if (take(']')) return result;
			while (true) {
				result.add(value()); whitespace();
				if (take(']')) return result;
				expect(',');
			}
		}
		private String string() {
			expect('"'); StringBuilder result = new StringBuilder();
			while (offset < source.length()) {
				char current = source.charAt(offset++);
				if (current == '"') return result.toString();
				if (current < 0x20) return fail();
				if (current != '\\') { result.append(current); continue; }
				if (offset >= source.length()) return fail();
				char escaped = source.charAt(offset++);
				switch (escaped) {
					case '"', '\\', '/' -> result.append(escaped);
					case 'b' -> result.append('\b');
					case 'f' -> result.append('\f');
					case 'n' -> result.append('\n');
					case 'r' -> result.append('\r');
					case 't' -> result.append('\t');
					case 'u' -> {
						if (offset + 4 > source.length()) return fail();
						try { result.append((char) Integer.parseInt(source.substring(offset, offset + 4), 16)); }
						catch (NumberFormatException invalid) { return fail(); }
						offset += 4;
					}
					default -> { return fail(); }
				}
			}
			return fail();
		}
		private BigDecimal number() {
			int start = offset;
			if (take('-') && offset >= source.length()) return fail();
			if (take('0')) {
				if (offset < source.length() && Character.isDigit(source.charAt(offset))) return fail();
			} else {
				if (offset >= source.length() || source.charAt(offset) < '1' || source.charAt(offset) > '9') return fail();
				while (offset < source.length() && Character.isDigit(source.charAt(offset))) offset++;
			}
			if (take('.')) {
				int fraction = offset;
				while (offset < source.length() && Character.isDigit(source.charAt(offset))) offset++;
				if (fraction == offset) return fail();
			}
			if (take('e') || take('E')) {
				take('+'); take('-');
				int exponent = offset;
				while (offset < source.length() && Character.isDigit(source.charAt(offset))) offset++;
				if (exponent == offset) return fail();
			}
			try { return new BigDecimal(source.substring(start, offset)); }
			catch (NumberFormatException invalid) { return fail(); }
		}
		private Object literal(String spelling, Object value) {
			if (!source.startsWith(spelling, offset)) return fail();
			offset += spelling.length(); return value;
		}
		private void whitespace() {
			while (offset < source.length() && " \t\r\n".indexOf(source.charAt(offset)) >= 0) offset++;
		}
		private boolean take(char expected) {
			if (offset < source.length() && source.charAt(offset) == expected) { offset++; return true; }
			return false;
		}
		private void expect(char expected) { if (!take(expected)) fail(); }
		private <T> T fail() { throw new IllegalArgumentException("malformed JSON at offset " + offset); }
	}
}
