// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.remote.jakarta;

public abstract class JakartaAsyncListenerFixture implements jakarta.servlet.AsyncListener {
	@Override public void onStartAsync(jakarta.servlet.AsyncEvent event) { }
	@Override public void onComplete(jakarta.servlet.AsyncEvent event) { }
	@Override public void onTimeout(jakarta.servlet.AsyncEvent event) { }
	@Override public void onError(jakarta.servlet.AsyncEvent event) { }
}
