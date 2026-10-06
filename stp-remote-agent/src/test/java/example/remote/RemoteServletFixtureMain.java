// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.remote;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ErrorPageErrorHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
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
		FilterHolder filter = new FilterHolder(new PassThroughFilter());
		filter.setAsyncSupported(true);
		context.addFilter(filter, "/*", EnumSet.allOf(DispatcherType.class));
		ServletHolder servlet = new ServletHolder(new FixtureServlet());
		servlet.setAsyncSupported(true);
		context.addServlet(servlet, "/*");
		context.addEventListener(new FixtureRequestListener());
		ErrorPageErrorHandler errorHandler = new ErrorPageErrorHandler();
		errorHandler.addErrorPage(500, "/error-target");
		context.setErrorHandler(errorHandler);
		server.setHandler(context);
		server.start();
		int port = server.getURI().getPort();
		System.out.println("READY:" + port);
		System.out.flush();
		server.join();
	}

	public static final class PassThroughFilter implements jakarta.servlet.Filter {
		@Override public void doFilter(ServletRequest request, ServletResponse response,
				jakarta.servlet.FilterChain chain) throws IOException, ServletException {
			chain.doFilter(request, response);
			FixtureService.afterFilterChain();
		}
	}

	public static final class FixtureRequestListener implements jakarta.servlet.ServletRequestListener {
		@Override public void requestInitialized(jakarta.servlet.ServletRequestEvent event) {
			FixtureListenerApplication.initialized();
		}
		@Override public void requestDestroyed(jakarta.servlet.ServletRequestEvent event) {
			FixtureListenerApplication.destroyed();
		}
	}

	public static final class FixtureListenerApplication {
		public static void initialized() { FixtureRepository.listenerInitialized(); }
		public static void destroyed() { FixtureRepository.listenerDestroyed(); }
	}

	public static final class FixtureServlet extends HttpServlet {
		private final FixtureService service = new FixtureService();
		@Override public void service(ServletRequest request, ServletResponse response) throws ServletException, IOException {
			service.serviceBoundary();
			super.service(request, response);
		}
		@Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
			String path = request.getPathInfo();
			if (request.getDispatcherType() == DispatcherType.INCLUDE) {
				if (!"/include-source".equals(path)) throw new IllegalStateException("unexpected include source path: " + path);
				response.getWriter().write(service.includeTarget());
				return;
			}
			if (request.getDispatcherType() == DispatcherType.ERROR) {
				if (!"/fail".equals(path)) throw new IllegalStateException("unexpected error dispatch path: " + path);
				response.setStatus(500);
				response.getWriter().write(service.errorTarget());
				return;
			}
			if ("/forward-target".equals(path) && request.getDispatcherType() != DispatcherType.FORWARD)
				throw new IllegalStateException("forward target did not use FORWARD dispatch");
			if ("/async-target".equals(path) && request.getDispatcherType() != DispatcherType.ASYNC)
				throw new IllegalStateException("async target did not use ASYNC dispatch");
			if (("/a".equals(path) || "/b".equals(path)) && request.getDispatcherType() != DispatcherType.REQUEST)
				throw new IllegalStateException("initial endpoint did not use REQUEST dispatch");
			if ("/fail".equals(path)) service.fail();
			if ("/async".equals(path)) {
				AsyncContext async = request.startAsync();
				async.setTimeout(5000);
				async.dispatch("/async-target");
				return;
			}
			if ("/forward-source".equals(path)) {
				service.forwardSource();
				request.getRequestDispatcher("/forward-target").forward(request, response);
				return;
			}
			if ("/include-source".equals(path)) {
				service.includeSource();
				request.getRequestDispatcher("/include-target").include(request, response);
				return;
			}
			String result = switch (path == null ? "/" : path) {
				case "/interface" -> service.handleInterface();
				case "/a" -> service.handleA();
				case "/b" -> service.handleB();
			case "/forward-target" -> service.forwardTarget();
				case "/include-target" -> service.includeTarget();
				case "/async-target" -> service.asyncTarget();
				case "/error-target" -> service.errorTarget();
				default -> service.handleOrdinary();
			};
			response.setStatus(200);
			response.getWriter().write(result);
		}
	}

	public static final class FixtureService implements FixtureGreeting {
		public void serviceBoundary() { FixtureRepository.servletService(); }
		public String handleA() { return FixtureRepository.readA(); }
		public String handleB() { return FixtureRepository.readB(); }
		public String handleOrdinary() { return FixtureRepository.readOrdinary(); }
		public String handleInterface() { return greet(); }
		public String forwardSource() { return FixtureRepository.forwardSource(); }
		public String forwardTarget() { return FixtureRepository.forwardTarget(); }
		public String includeSource() { return FixtureRepository.includeSource(); }
		public String includeTarget() { return FixtureRepository.includeTarget(); }
		public String asyncTarget() { return FixtureRepository.asyncTarget(); }
		public String errorTarget() { return FixtureRepository.errorTarget(); }
		public String fail() { return FixtureRepository.fail(); }
		public static void afterFilterChain() { FixtureRepository.afterFilterChain(); }
	}

	public static final class FixtureRepository {
		public static void servletService() { }
		public static void listenerInitialized() { }
		public static void listenerDestroyed() { }
		public static String readA() { return "a"; }
		public static String readB() { return "b"; }
		public static String readOrdinary() { return "ordinary"; }
		public static String forwardSource() { return "forward-source"; }
		public static String forwardTarget() { return "forward-target"; }
		public static String includeSource() { return "include-source"; }
		public static String includeTarget() { return "include-target"; }
		public static String asyncTarget() { return "async-target"; }
		public static String errorTarget() { return "error-target"; }
		public static String fail() { throw new IllegalStateException("fixture failure"); }
		public static void afterFilterChain() { }
	}
}
