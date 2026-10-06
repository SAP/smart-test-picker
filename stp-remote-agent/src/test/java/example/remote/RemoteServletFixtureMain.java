// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.remote;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletHolder;

import java.io.IOException;
import java.util.EnumSet;

/** Child JVM fixture: real HTTP requests enter a separately instrumented Servlet server. */
public final class RemoteServletFixtureMain {
	private RemoteServletFixtureMain() { }
	public static void main(String[] args) throws Exception {
		Server server = new Server(0);
		ServletContextHandler context = new ServletContextHandler();
		context.setContextPath("/");
		context.addFilter(new FilterHolder(new PassThroughFilter()), "/*", EnumSet.of(jakarta.servlet.DispatcherType.REQUEST));
		context.addServlet(new ServletHolder(new FixtureServlet()), "/*");
		server.setHandler(context);
		server.start();
		int port = server.getURI().getPort();
		System.out.println("READY:" + port);
		System.out.flush();
		server.join();
	}

	public static final class PassThroughFilter implements jakarta.servlet.Filter {
		@Override public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response,
				jakarta.servlet.FilterChain chain) throws IOException, jakarta.servlet.ServletException {
			chain.doFilter(request, response);
		}
	}

	public static final class FixtureServlet extends HttpServlet {
		private final FixtureService service = new FixtureService();
		@Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
			String path = request.getPathInfo();
			if ("/fail".equals(path)) service.fail();
			String result = "/a".equals(path) ? service.handleA()
					: "/b".equals(path) ? service.handleB() : service.handleOrdinary();
			response.setStatus(200);
			response.getWriter().write(result);
		}
	}

	public static final class FixtureService {
		public String handleA() { return FixtureRepository.readA(); }
		public String handleB() { return FixtureRepository.readB(); }
		public String handleOrdinary() { return FixtureRepository.readOrdinary(); }
		public String fail() { return FixtureRepository.fail(); }
	}

	public static final class FixtureRepository {
		public static String readA() { return "a"; }
		public static String readB() { return "b"; }
		public static String readOrdinary() { return "ordinary"; }
		public static String fail() { throw new IllegalStateException("fixture failure"); }
	}
}
