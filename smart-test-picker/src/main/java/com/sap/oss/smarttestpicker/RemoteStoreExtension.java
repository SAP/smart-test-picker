// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import javax.inject.Inject;

import org.gradle.api.Action;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;


/**
 * DSL extension for configuring a remote store for coverage maps.
 *
 * <p>Usage in {@code build.gradle}:</p>
 * <pre>{@code
 * smartTestPicker {
 *     remoteStore {
 *         url = 'https://nexus.example.com/repository/stp-maps/'
 *         push = true
 *         credentials {
 *             username = providers.environmentVariable('STP_REMOTE_USER')
 *             password = providers.environmentVariable('STP_REMOTE_PASS')
 *         }
 *     }
 * }
 * }</pre>
 */
public abstract class RemoteStoreExtension
{

	private final RemoteStoreCredentials credentials;

	@Inject
	public RemoteStoreExtension(ObjectFactory objects)
	{
		this.credentials = objects.newInstance(RemoteStoreCredentials.class);
	}

	/**
	 * Base URL of the remote store (e.g. Nexus raw repository URL).
	 * Maps are stored at {@code {url}/{baseBranch}/test-coverage-map.json}.
	 */
	public abstract Property<String> getUrl();

	/**
	 * Whether to push the coverage map after generation.
	 * Default: false. Set to true on CI builds on the main branch.
	 */
	public abstract Property<Boolean> getPush();

	/**
	 * HTTP Basic Auth credentials for the remote store.
	 */
	public RemoteStoreCredentials getCredentials()
	{
		return credentials;
	}

	/**
	 * Configures the credentials using a closure/action.
	 */
	public void credentials(Action<? super RemoteStoreCredentials> action)
	{
		action.execute(getCredentials());
	}
}
