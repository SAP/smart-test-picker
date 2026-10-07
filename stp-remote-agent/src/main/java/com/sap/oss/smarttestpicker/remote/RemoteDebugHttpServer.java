// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Opt-in, loopback-only read access to the remote recorder's current and finalized state. */
final class RemoteDebugHttpServer {
	private final HttpServer server;
	private final ExecutorService executor;
	private volatile boolean accepting = true;

	private RemoteDebugHttpServer(HttpServer server, ExecutorService executor) {
		this.server = server;
		this.executor = executor;
	}

	static RemoteDebugHttpServer startIfConfigured(Integer port) {
		return port == null ? null : start(port);
	}

	static RemoteDebugHttpServer start(int port) {
		HttpServer server = null;
		ExecutorService executor = null;
		try {
			InetAddress loopback = InetAddress.getLoopbackAddress();
			server = HttpServer.create(new InetSocketAddress(loopback, port), 0);
			RemoteDebugHttpServer debug = new RemoteDebugHttpServer(server, daemonExecutor());
			executor = debug.executor;
			server.createContext("/stp/debug/", debug::handle);
			server.setExecutor(executor);
			server.start();
			System.err.println("[stp-remote-agent] debug HTTP listening on " + server.getAddress());
			return debug;
		} catch (IOException | RuntimeException failure) {
			if (server != null) server.stop(0);
			if (executor != null) executor.shutdownNow();
			throw new IllegalStateException("[stp-remote-agent] cannot start loopback debug HTTP server on port " + port, failure);
		}
	}

	InetSocketAddress address() { return server.getAddress(); }

	/** Stops dispatching new debug requests before the recorder's final snapshot is written. */
	void stopAccepting() { accepting = false; }

	void stop() {
		accepting = false;
		server.stop(0);
		executor.shutdownNow();
	}

	private void handle(HttpExchange exchange) throws IOException {
		try (exchange) {
			if (!accepting) {
				respondError(exchange, 503, "shutting_down", RemoteRecorder.configuredOutput());
				return;
			}
			String path = exchange.getRequestURI().getPath();
			if (!path.equals("/stp/debug/memory") && !path.equals("/stp/debug/output")) {
				respondError(exchange, 404, "not_found", RemoteRecorder.configuredOutput());
				return;
			}
			if (!exchange.getRequestMethod().equals("GET")) {
				respondError(exchange, 405, "method_not_allowed", RemoteRecorder.configuredOutput());
				return;
			}
			if (path.equals("/stp/debug/memory")) {
				respond(exchange, 200, RemoteRecorder.memorySnapshot().getBytes(StandardCharsets.UTF_8));
				return;
			}
			try {
				RemoteRecorder.FinalizedOutput output = RemoteRecorder.finalizedOutput();
				if (output == null) {
					respondError(exchange, 404, "not_finalized", RemoteRecorder.configuredOutput());
				} else {
					respond(exchange, 200, output.content());
				}
			} catch (IOException failure) {
				respondError(exchange, 500, "output_unavailable", RemoteRecorder.configuredOutput());
			}
		}
	}

	private static void respondError(HttpExchange exchange, int status, String reason, Path output) throws IOException {
		String json = "{\"status\":\"" + reason + "\",\"output\":"
				+ (output == null ? "null" : "\"" + escape(output.toString()) + "\"") + "}";
		respond(exchange, status, json.getBytes(StandardCharsets.UTF_8));
	}

	private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
		exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		exchange.getResponseHeaders().set("Cache-Control", "no-store");
		exchange.sendResponseHeaders(status, body.length);
		exchange.getResponseBody().write(body);
	}

	private static String escape(String value) {
		StringBuilder escaped = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); i++) {
			char current = value.charAt(i);
			switch (current) {
				case '\\' -> escaped.append("\\\\");
				case '"' -> escaped.append("\\\"");
				case '\n' -> escaped.append("\\n");
				case '\r' -> escaped.append("\\r");
				case '\t' -> escaped.append("\\t");
				default -> {
					if (current < 0x20) escaped.append(String.format("\\u%04x", (int) current));
					else escaped.append(current);
				}
			}
		}
		return escaped.toString();
	}

	private static ExecutorService daemonExecutor() {
		return Executors.newCachedThreadPool(task -> {
			Thread thread = new Thread(task, "stp-remote-debug-http");
			thread.setDaemon(true);
			return thread;
		});
	}
}
