// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import org.gradle.api.provider.Property;


/**
 * Credentials for the remote coverage map store.
 *
 * <p>Used within the {@code remoteStore} DSL block:</p>
 * <pre>{@code
 * smartTestPicker {
 *     remoteStore {
 *         credentials {
 *             username = providers.environmentVariable('STP_REMOTE_USER')
 *             password = providers.environmentVariable('STP_REMOTE_PASS')
 *         }
 *     }
 * }
 * }</pre>
 */
public abstract class RemoteStoreCredentials
{

	/** HTTP Basic Auth username. Default: unset. Supply through an external credential provider. */
	public abstract Property<String> getUsername();

	/** HTTP Basic Auth password. Default: unset. Do not store credentials in project source. */
	public abstract Property<String> getPassword();
}
