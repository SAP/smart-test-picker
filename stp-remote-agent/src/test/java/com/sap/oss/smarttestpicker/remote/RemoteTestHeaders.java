package com.sap.oss.smarttestpicker.remote;

import java.net.http.HttpRequest;

final class RemoteTestHeaders {
	private RemoteTestHeaders() { }
	static HttpRequest.Builder apply(HttpRequest.Builder builder, String testId) {
		if (testId != null) builder.header(RemoteRequestIdentity.TEST_SUITE_HEADER, "remote-fixture-suite")
				.header(RemoteRequestIdentity.TEST_ID_HEADER, testId)
				.header(RemoteRequestIdentity.REQUEST_ID_HEADER, java.util.UUID.randomUUID().toString());
		return builder;
	}
}
