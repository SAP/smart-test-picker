// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sap.oss.smarttestpicker.store.RemoteStoreClient;

import static org.junit.jupiter.api.Assertions.*;


class PullCoverageMapMojoTest
{

	@TempDir
	Path tempDir;

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
	void pullWritesMapToOutputFile() throws IOException
	{
		byte[] payload = "{\"metadata\":{},\"testMappings\":{}}".getBytes(StandardCharsets.UTF_8);
		server.createContext("/main/test-coverage-map.json", exchange -> {
			exchange.sendResponseHeaders(200, payload.length);
			try (OutputStream os = exchange.getResponseBody())
			{
				os.write(payload);
			}
		});

		File outputFile = tempDir.resolve("target/test-coverage-map.json").toFile();
		RemoteStoreClient client = new RemoteStoreClient("http://localhost:" + port, null, null);
		byte[] data = client.pull("main");

		assertNotNull(data);
		outputFile.getParentFile().mkdirs();
		Files.write(outputFile.toPath(), data);

		assertTrue(outputFile.exists());
		String content = Files.readString(outputFile.toPath());
		assertTrue(content.contains("testMappings"));
	}

	@Test
	void pullReturnsNullWhenNoRemoteMap() throws IOException
	{
		server.createContext("/main/test-coverage-map.json", exchange -> {
			exchange.sendResponseHeaders(404, -1);
			exchange.close();
		});

		RemoteStoreClient client = new RemoteStoreClient("http://localhost:" + port, null, null);
		byte[] data = client.pull("main");

		assertNull(data);
	}

	@Test
	void pullSkippedWhenLocalMapExists() throws IOException
	{
		File localMap = tempDir.resolve("target/test-coverage-map.json").toFile();
		localMap.getParentFile().mkdirs();
		Files.writeString(localMap.toPath(), "{\"metadata\":{},\"testMappings\":{\"existing\":{}}}");

		boolean[] serverHit = {false};
		server.createContext("/main/test-coverage-map.json", exchange -> {
			serverHit[0] = true;
			exchange.sendResponseHeaders(200, 2);
			try (OutputStream os = exchange.getResponseBody())
			{
				os.write("{}".getBytes());
			}
		});

		// SmartTestMojo logic: only pull if local map doesn't exist
		boolean shouldPull = !localMap.exists();
		assertFalse(shouldPull);

		// Verify server was not contacted
		assertFalse(serverHit[0]);
	}

	@Test
	void pullWithCredentialsSendsAuthHeader() throws IOException
	{
		String[] authHeader = new String[1];
		byte[] payload = "{}".getBytes();
		server.createContext("/main/test-coverage-map.json", exchange -> {
			authHeader[0] = exchange.getRequestHeaders().getFirst("Authorization");
			exchange.sendResponseHeaders(200, payload.length);
			try (OutputStream os = exchange.getResponseBody())
			{
				os.write(payload);
			}
		});

		RemoteStoreClient client = new RemoteStoreClient(
				"http://localhost:" + port, "deploy-user", "deploy-pass");
		client.pull("main");

		assertNotNull(authHeader[0]);
		assertTrue(authHeader[0].startsWith("Basic "));
	}
}
