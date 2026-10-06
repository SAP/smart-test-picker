// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.springremote;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Component
public class SpringMvcDispatchInterceptor implements HandlerInterceptor, WebMvcConfigurer {
	private final SpringMvcRepository repository = new SpringMvcRepository();
	@Override public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(this); }
	@Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (request.getDispatcherType() == DispatcherType.ASYNC && request.getRequestURI().endsWith("/deferred")) {
			repository.hit("deferredRedispatch");
		}
		return true;
	}
}
