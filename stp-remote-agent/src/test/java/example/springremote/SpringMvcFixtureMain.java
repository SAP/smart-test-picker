// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.springremote;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.FilterHolder;
import jakarta.servlet.DispatcherType;

import java.util.EnumSet;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;

/** Starts a real Spring MVC application in a separately instrumented child JVM. */
public final class SpringMvcFixtureMain {
	private SpringMvcFixtureMain() { }
	public static void main(String[] args) throws Exception {
		ServletContextHandler context = new ServletContextHandler();
		context.setContextPath("/");
		AnnotationConfigWebApplicationContext application = new AnnotationConfigWebApplicationContext();
		application.setServletContext(context.getServletContext());
		application.register(SpringMvcConfiguration.class);
		application.refresh();
		Server server = new Server(0);
		ServletHolder dispatcher = new ServletHolder(new DispatcherServlet(application));
		dispatcher.setAsyncSupported(true);
		FilterHolder filter = new FilterHolder(new SpringMvcDispatchFilter());
		filter.setAsyncSupported(true);
		context.addFilter(filter, "/*", EnumSet.allOf(DispatcherType.class));
		context.addServlet(dispatcher, "/*");
		server.setHandler(context);
		server.start();
		System.out.println("SPRING_READY:" + server.getURI().getPort());
		System.out.flush();
		server.join();
	}
}
