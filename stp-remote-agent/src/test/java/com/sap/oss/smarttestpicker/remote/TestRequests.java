package com.sap.oss.smarttestpicker.remote;

final class TestRequests {
	private TestRequests() { }
	static RemoteRequestIdentity request(String testId) {
		return new RemoteRequestIdentity("fixture-suite", testId, java.util.UUID.randomUUID().toString());
	}
}
