// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.store;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;


/**
 * HTTP client for pulling and pushing coverage maps to a remote store.
 *
 * <p>Supports any HTTP server that accepts GET (download) and PUT (upload)
 * on the path {@code {baseUrl}/{baseBranch}/test-coverage-map.json}.
 * Compatible with Nexus raw repositories, Artifactory generic repos,
 * S3 via pre-signed URLs, or any simple HTTP file server.</p>
 *
 * <p>Uses {@link HttpClient} (Java 11+) — no external dependencies.</p>
 */
public class RemoteStoreClient
{

	private static final Duration TIMEOUT = Duration.ofSeconds(30);

	private final String baseUrl;
	private final String username;
	private final String password;
	private final HttpClient httpClient;

	public RemoteStoreClient(String baseUrl, String username, String password)
	{
		this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
		this.username = username;
		this.password = password;
		this.httpClient = HttpClient.newBuilder()
				.connectTimeout(TIMEOUT)
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
	}

	/**
	 * Downloads the coverage map for the given branch.
	 *
	 * @param baseBranch the branch name (used as path segment)
	 * @return the response body bytes, or {@code null} if not found (404)
	 * @throws IOException if the request fails or returns a server error
	 */
	public byte[] pull(String baseBranch) throws IOException
	{
		URI uri = URI.create(baseUrl + "/" + baseBranch + "/test-coverage-map.json");

		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(uri)
				.timeout(TIMEOUT)
				.GET();

		addAuthHeader(builder);

		try
		{
			HttpResponse<byte[]> response = httpClient.send(builder.build(),
					HttpResponse.BodyHandlers.ofByteArray());

			if (response.statusCode() == 404)
			{
				return null;
			}

			if (response.statusCode() >= 300)
			{
				throw new IOException("GET " + uri + " returned HTTP " + response.statusCode());
			}

			return response.body();
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Request interrupted: " + uri, e);
		}
	}

	/**
	 * Uploads the coverage map for the given branch.
	 *
	 * @param baseBranch the branch name (used as path segment)
	 * @param data       the coverage map bytes to upload
	 * @throws IOException if the request fails or returns a non-2xx status
	 */
	public void push(String baseBranch, byte[] data) throws IOException
	{
		URI uri = URI.create(baseUrl + "/" + baseBranch + "/test-coverage-map.json");

		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(uri)
				.timeout(TIMEOUT)
				.PUT(HttpRequest.BodyPublishers.ofByteArray(data));

		addAuthHeader(builder);

		try
		{
			HttpResponse<String> response = httpClient.send(builder.build(),
					HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() >= 300)
			{
				throw new IOException("PUT " + uri + " returned HTTP " + response.statusCode()
						+ ": " + response.body());
			}
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Request interrupted: " + uri, e);
		}
	}

	private void addAuthHeader(HttpRequest.Builder builder)
	{
		if (username != null && !username.isEmpty()
				&& password != null && !password.isEmpty())
		{
			String credentials = Base64.getEncoder()
					.encodeToString((username + ":" + password).getBytes());
			builder.header("Authorization", "Basic " + credentials);
		}
	}
}
