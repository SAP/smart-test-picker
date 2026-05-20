// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker.store;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;


class RemoteStoreClientTest
{

	private HttpServer server;
	private int port;

	@BeforeEach
	void setUp() throws IOException
	{
		server = HttpServer.create(new InetSocketAddress(0), 0);
		port = server.getAddress().getPort();
		server.start();
	}

	@AfterEach
	void tearDown()
	{
		server.stop(0);
	}

	@Test
	void pullReturnsDataOn200()throws IOException
	{
		byte[] payload = "{\"metadata\":{}}".getBytes(StandardCharsets.UTF_8);
		server.createContext("/main/test-coverage-map.json", exchange -> {
			exchange.sendResponseHeaders(200, payload.length);
			try (OutputStream os = exchange.getResponseBody())
			{
				os.write(payload);
			}
		});

		RemoteStoreClient client = new RemoteStoreClient("http://localhost:" + port, null, null);
		byte[] result = client.pull("main");

		assertNotNull(result);
		assertArrayEquals(payload, result);
	}

	@Test
	void pullReturnsNullOn404() throws IOException
	{
		server.createContext("/main/test-coverage-map.json", exchange -> {
			exchange.sendResponseHeaders(404, -1);
			exchange.close();
		});

		RemoteStoreClient client = new RemoteStoreClient("http://localhost:" + port, null, null);
		byte[] result = client.pull("main");

		assertNull(result);
	}

	@Test
	void pullThrowsOnServerError()
	{
		server.createContext("/main/test-coverage-map.json", exchange -> {
			exchange.sendResponseHeaders(500, -1);
			exchange.close();
		});

		RemoteStoreClient client = new RemoteStoreClient("http://localhost:" + port, null, null);
		IOException ex = assertThrows(IOException.class, () -> client.pull("main"));
		assertTrue(ex.getMessage().contains("500"));
	}

	@Test
	void pushSendsDataWithPut() throws IOException
	{
		byte[] received = new byte[1];
		String[] method = new String[1];

		server.createContext("/develop/test-coverage-map.json", exchange -> {
			method[0] = exchange.getRequestMethod();
			byte[] body = exchange.getRequestBody().readAllBytes();
			received[0] = body[0];
			exchange.sendResponseHeaders(201, -1);
			exchange.close();
		});

		byte[] payload = {42};
		RemoteStoreClient client = new RemoteStoreClient("http://localhost:" + port, null, null);
		client.push("develop", payload);

		assertEquals("PUT", method[0]);
		assertEquals(42, received[0]);
	}

	@Test
	void pushThrowsOnServerError()
	{
		server.createContext("/main/test-coverage-map.json", exchange -> {
			exchange.getRequestBody().readAllBytes();
			exchange.sendResponseHeaders(403, -1);
			exchange.close();
		});

		RemoteStoreClient client = new RemoteStoreClient("http://localhost:" + port, null, null);
		IOException ex = assertThrows(IOException.class, () -> client.push("main", new byte[]{1}));
		assertTrue(ex.getMessage().contains("403"));
	}

	@Test
	void authHeaderSentWhenCredentialsProvided() throws IOException
	{
		String[] authHeader = new String[1];

		server.createContext("/main/test-coverage-map.json", exchange -> {
			authHeader[0] = exchange.getRequestHeaders().getFirst("Authorization");
			exchange.sendResponseHeaders(200, 2);
			try (OutputStream os = exchange.getResponseBody())
			{
				os.write("{}".getBytes());
			}
		});

		RemoteStoreClient client = new RemoteStoreClient(
				"http://localhost:" + port, "myuser", "mypass");
		client.pull("main");

		assertNotNull(authHeader[0]);
		assertTrue(authHeader[0].startsWith("Basic "));
		String decoded = new String(java.util.Base64.getDecoder()
				.decode(authHeader[0].substring("Basic ".length())));
		assertEquals("myuser:mypass", decoded);
	}

	@Test
	void noAuthHeaderWhenNoCredentials() throws IOException
	{
		String[] authHeader = {""};

		server.createContext("/main/test-coverage-map.json", exchange -> {
			authHeader[0] = exchange.getRequestHeaders().getFirst("Authorization");
			exchange.sendResponseHeaders(200, 2);
			try (OutputStream os = exchange.getResponseBody())
			{
				os.write("{}".getBytes());
			}
		});

		RemoteStoreClient client = new RemoteStoreClient("http://localhost:" + port, null, null);
		client.pull("main");

		assertNull(authHeader[0]);
	}

	@Test
	void trailingSlashInUrlIsHandled() throws IOException
	{
		byte[] payload = "data".getBytes();
		server.createContext("/main/test-coverage-map.json", exchange -> {
			exchange.sendResponseHeaders(200, payload.length);
			try (OutputStream os = exchange.getResponseBody())
			{
				os.write(payload);
			}
		});

		RemoteStoreClient client = new RemoteStoreClient(
				"http://localhost:" + port + "/", null, null);
		byte[] result = client.pull("main");

		assertNotNull(result);
		assertArrayEquals(payload, result);
	}
}
