// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.remote.javax;

public final class JavaxServletFixture implements javax.servlet.Servlet {
	@Override public void init(javax.servlet.ServletConfig config) { }
	@Override public javax.servlet.ServletConfig getServletConfig() { return null; }
	@Override public void service(javax.servlet.ServletRequest request, javax.servlet.ServletResponse response) { }
	@Override public String getServletInfo() { return "fixture"; }
	@Override public void destroy() { }
}
