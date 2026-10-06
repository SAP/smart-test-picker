// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.remote.jakarta;

public final class JakartaServletFixture implements jakarta.servlet.Servlet {
	@Override public void init(jakarta.servlet.ServletConfig config) { }
	@Override public jakarta.servlet.ServletConfig getServletConfig() { return null; }
	@Override public void service(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) { }
	@Override public String getServletInfo() { return "fixture"; }
	@Override public void destroy() { }
}
