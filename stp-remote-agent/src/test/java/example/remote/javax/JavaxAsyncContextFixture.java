// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.remote.javax;

public abstract class JavaxAsyncContextFixture implements javax.servlet.AsyncContext {
	@Override public void start(Runnable task) { }
}
