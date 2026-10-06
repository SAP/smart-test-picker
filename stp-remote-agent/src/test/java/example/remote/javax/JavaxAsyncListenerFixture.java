// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.remote.javax;

public abstract class JavaxAsyncListenerFixture implements javax.servlet.AsyncListener {
	@Override public void onStartAsync(javax.servlet.AsyncEvent event) { }
	@Override public void onComplete(javax.servlet.AsyncEvent event) { }
	@Override public void onTimeout(javax.servlet.AsyncEvent event) { }
	@Override public void onError(javax.servlet.AsyncEvent event) { }
}
