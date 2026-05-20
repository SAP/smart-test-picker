// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker;

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

	public abstract Property<String> getUsername();

	public abstract Property<String> getPassword();
}
