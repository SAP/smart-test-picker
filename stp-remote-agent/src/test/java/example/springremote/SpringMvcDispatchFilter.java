// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.springremote;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

public class SpringMvcDispatchFilter implements Filter {
	@Override public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws java.io.IOException, jakarta.servlet.ServletException {
		String path = request instanceof jakarta.servlet.http.HttpServletRequest http ? http.getRequestURI() : "?";
		DispatcherType dispatcher = request.getDispatcherType();
		try { chain.doFilter(request, response); }
		finally {
			System.out.println("SPRING_DISPATCH:exit:" + path + ":" + dispatcher + ":" + System.nanoTime());
		}
	}
}
