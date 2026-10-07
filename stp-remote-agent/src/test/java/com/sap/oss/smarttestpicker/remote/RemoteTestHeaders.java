package com.sap.oss.smarttestpicker.remote;

import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.context.Context;

import java.net.http.HttpRequest;
import java.util.HashMap;
import java.util.Map;

final class RemoteTestHeaders {
	private RemoteTestHeaders() { }
	static HttpRequest.Builder apply(HttpRequest.Builder builder, String testId) {
		if (testId != null) {
			RemoteRequestIdentity identity = new RemoteRequestIdentity("remote-fixture-suite", testId, java.util.UUID.randomUUID().toString());
			var baggage = new HashMap<String, String>();
			W3CBaggagePropagator.getInstance().inject(identity.toBaggage(Context.root()), baggage, Map::put);
			baggage.forEach(builder::header);
		}
		return builder;
	}
}
