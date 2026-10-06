package com.sap.oss.smarttestpicker.remote;

import java.util.Map;

final class TestRequests {
	private TestRequests() { }
	static Request request(String testId) { return new Request(testId); }
	static Request noIdentity() { return new Request(null); }
	static final class Request {
		private final Map<String, String> headers = new java.util.concurrent.ConcurrentHashMap<>();
		private final Map<String, Object> attributes = new java.util.concurrent.ConcurrentHashMap<>();
		Request(String testId) { if (testId != null) { headers.put(RemoteRequestIdentity.TEST_SUITE_HEADER, "fixture-suite"); headers.put(RemoteRequestIdentity.TEST_ID_HEADER, testId); headers.put(RemoteRequestIdentity.REQUEST_ID_HEADER, java.util.UUID.randomUUID().toString()); } }
		public String getHeader(String name) { return headers.get(name); }
		public Object getAttribute(String name) { return attributes.get(name); }
		public void setAttribute(String name, Object value) { if (value == null) attributes.remove(name); else attributes.put(name, value); }
		void setHeader(String name, String value) { if (value == null) headers.remove(name); else headers.put(name, value); }
	}
}
