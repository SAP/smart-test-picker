// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.remote;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ErrorPageErrorHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

import java.io.IOException;
import java.util.EnumSet;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

/** Child JVM fixture: real HTTP requests enter a separately instrumented Servlet server. */
public final class RemoteServletFixtureMain {
	private static final CyclicBarrier CONCURRENT_LISTENER_BARRIER = new CyclicBarrier(2);
	private static final CyclicBarrier CONCURRENT_IO_LISTENER_BARRIER = new CyclicBarrier(2);
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

	public static final class FixtureAsyncListener implements jakarta.servlet.AsyncListener {
		private final String scenario;
		public FixtureAsyncListener(String scenario) { this.scenario = scenario; }
		@Override public void onStartAsync(jakarta.servlet.AsyncEvent event) {
			FixtureAsyncListenerApplication.onStart(scenario);
		}
		@Override public void onComplete(jakarta.servlet.AsyncEvent event) {
			FixtureAsyncListenerApplication.onComplete(scenario);
		}
		@Override public void onTimeout(jakarta.servlet.AsyncEvent event) {
			FixtureAsyncListenerApplication.onTimeout(scenario);
			event.getAsyncContext().complete();
		}
		@Override public void onError(jakarta.servlet.AsyncEvent event) {
			FixtureAsyncListenerApplication.onError(scenario);
		}
	}

	public static final class FixtureAsyncListenerApplication {
		public static void onStart(String scenario) {
			if (scenario.endsWith("-a")) FixtureRepository.listenerStartA();
			else if (scenario.endsWith("-b")) FixtureRepository.listenerStartB();
			else FixtureRepository.listenerStart();
		}
		public static void onComplete(String scenario) {
			if (scenario.startsWith("concurrent-")) {
				long start = System.nanoTime();
				System.out.println("ASYNC_LISTENER_EVENT phase=START scenario=" + scenario + " nanos=" + start);
				try { CONCURRENT_LISTENER_BARRIER.await(5, TimeUnit.SECONDS); }
				catch (Exception failure) { throw new IllegalStateException("concurrent callback barrier failed", failure); }
				if (scenario.endsWith("-a")) FixtureRepository.listenerCompleteA();
				else FixtureRepository.listenerCompleteB();
				System.out.println("ASYNC_LISTENER_EVENT phase=END scenario=" + scenario + " nanos=" + System.nanoTime());
				return;
			}
			if (scenario.endsWith("-a")) FixtureRepository.listenerCompleteA();
			else if (scenario.endsWith("-b")) FixtureRepository.listenerCompleteB();
			else FixtureRepository.listenerComplete();
		}
		public static void onTimeout(String scenario) { FixtureRepository.listenerTimeout(); }
		public static void onError(String scenario) { FixtureRepository.listenerError(); }
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
			if (isIoPath(request.getPathInfo())) {
				runIo(request, response);
				return;
			}
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
			if ("/async-listener-start-target".equals(path)) {
				AsyncContext next = request.startAsync();
				next.addListener(new FixtureAsyncListener("complete"), request, response);
				next.start(next::complete);
				return;
			}
			if ("/async-listener-auto-complete".equals(path)) {
				response.getWriter().write("auto-complete");
				return;
			}
			if (path != null && path.startsWith("/async-listener-")) {
				String scenario = path.substring("/async-listener-".length());
				AsyncContext async = request.startAsync();
				async.addListener(new FixtureAsyncListener(scenario), request, response);
				if (scenario.startsWith("start")) async.dispatch("/async-listener-start-target");
				else if (scenario.startsWith("timeout")) async.setTimeout(120);
				else if (scenario.startsWith("error")) async.start(() -> {
					var state = Request.getBaseRequest(request).getHttpChannelState();
					long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
					while (!"WAITING".equals(state.getState().name()) && System.nanoTime() < deadline) {
						try { Thread.sleep(1); }
						catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
					}
					if (!"WAITING".equals(state.getState().name())) {
						System.err.println("ASYNC_ERROR_FIXTURE_DID_NOT_REACH_WAITING");
						async.complete();
						return;
					}
					state.asyncError(new IllegalStateException("fixture async error"));
				});
				else if (scenario.startsWith("complete") || scenario.startsWith("concurrent-"))
					async.start(() -> async.dispatch("/async-listener-auto-complete"));
				else async.start(async::complete);
				return;
			}
			if (path != null && path.startsWith("/async-start")) {
				AsyncContext async = request.startAsync();
				async.setTimeout(5000);
				long requestThread = Thread.currentThread().getId();
				boolean fail = "/async-start-fail".equals(path);
				async.start(() -> {
					try {
						if (fail) service.asyncFailure();
						else if (path.endsWith("-a")) service.asyncWorkA();
						else if (path.endsWith("-b")) service.asyncWorkB();
						else service.asyncWork();
						response.getWriter().write(requestThread + ":" + Thread.currentThread().getId());
					} catch (IOException writeFailure) {
						throw new RuntimeException(writeFailure);
					} finally {
						async.complete();
					}
				});
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
		@Override protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
			if (!isIoPath(request.getPathInfo())) { response.sendError(404); return; }
			runIo(request, response);
		}
		private static boolean isIoPath(String path) { return path != null && path.startsWith("/io-"); }
		private static void runIo(HttpServletRequest request, HttpServletResponse response) throws IOException {
			String scenario = request.getPathInfo().substring("/io-".length());
			AsyncContext async = request.startAsync();
			async.setTimeout(5000);
			if (scenario.startsWith("read")) {
				ServletInputStream input = request.getInputStream();
				input.setReadListener(new FixtureReadListener(scenario, input, async, Thread.currentThread().getId()));
			} else {
				ServletOutputStream output = response.getOutputStream();
				output.setWriteListener(new FixtureWriteListener(scenario, output, async, Thread.currentThread().getId()));
			}
		}
	}

	public static final class FixtureReadListener implements ReadListener {
		private final String scenario;
		private final ServletInputStream input;
		private final AsyncContext async;
		private final long registrationThread;
		FixtureReadListener(String scenario, ServletInputStream input, AsyncContext async, long registrationThread) {
			this.scenario = scenario; this.input = input; this.async = async; this.registrationThread = registrationThread;
		}
		@Override public void onDataAvailable() throws IOException {
			logIoCallback("read-data", scenario, registrationThread);
			if (scenario.equals("read-headerless")) FixtureIoListenerApplication.readHeaderlessData();
			else FixtureIoListenerApplication.readData(scenario);
			if (scenario.equals("read-error")) throw new IOException("fixture read callback failure");
			while (input.isReady() && !input.isFinished()) if (input.read() < 0) break;
		}
		@Override public void onAllDataRead() {
			logIoCallback("read-all", scenario, registrationThread);
			if (scenario.equals("read-headerless")) FixtureIoListenerApplication.readHeaderlessAll();
			else FixtureIoListenerApplication.readAll(scenario);
			async.complete();
		}
		@Override public void onError(Throwable failure) {
			logIoCallback("read-error", scenario, registrationThread);
			FixtureIoListenerApplication.readError(scenario);
			async.complete();
		}
	}

	public static final class FixtureWriteListener implements WriteListener {
		private final String scenario;
		private final ServletOutputStream output;
		private final AsyncContext async;
		private final long registrationThread;
		FixtureWriteListener(String scenario, ServletOutputStream output, AsyncContext async, long registrationThread) {
			this.scenario = scenario; this.output = output; this.async = async; this.registrationThread = registrationThread;
		}
		@Override public void onWritePossible() throws IOException {
			logIoCallback("write-possible", scenario, registrationThread);
			FixtureIoListenerApplication.writePossible(scenario);
			if (scenario.equals("write-error")) throw new IOException("fixture write callback failure");
			while (output.isReady()) { output.write('o'); break; }
			async.complete();
		}
		@Override public void onError(Throwable failure) {
			logIoCallback("write-error", scenario, registrationThread);
			FixtureIoListenerApplication.writeError(scenario);
			async.complete();
		}
	}

	private static void logIoCallback(String kind, String scenario, long registrationThread) {
		System.out.println("IO_CALLBACK type=" + kind + " scenario=" + scenario + " registrationThread="
				+ registrationThread + " callbackThread=" + Thread.currentThread().getId());
	}

	public static final class FixtureIoListenerApplication {
		public static void readData(String scenario) { FixtureIoRepository.readData(scenario); }
		public static void readAll(String scenario) { FixtureIoRepository.readAll(scenario); }
		public static void readError(String scenario) { FixtureIoRepository.readError(scenario); }
		public static void writePossible(String scenario) { FixtureIoRepository.writePossible(scenario); }
		public static void writeError(String scenario) { FixtureIoRepository.writeError(scenario); }
		public static void readHeaderlessData() { FixtureIoRepository.readHeaderlessData(); }
		public static void readHeaderlessAll() { FixtureIoRepository.readHeaderlessAll(); }
	}
	public static final class FixtureIoRepository {
		public static void readData(String scenario) { awaitConcurrentIo(scenario); }
		public static void readAll(String scenario) { }
		public static void readError(String scenario) { }
		public static void writePossible(String scenario) { awaitConcurrentIo(scenario); }
		public static void writeError(String scenario) { }
		public static void readHeaderlessData() { }
		public static void readHeaderlessAll() { }
	}
	private static void awaitConcurrentIo(String scenario) {
		if (!scenario.equals("read-concurrent") && !scenario.equals("write-concurrent")) return;
		System.out.println("IO_CALLBACK_EVENT phase=START scenario=" + scenario + " nanos=" + System.nanoTime());
		try { CONCURRENT_IO_LISTENER_BARRIER.await(5, TimeUnit.SECONDS); }
		catch (Exception failure) { throw new IllegalStateException("concurrent I/O callback barrier failed", failure); }
		System.out.println("IO_CALLBACK_EVENT phase=END scenario=" + scenario + " nanos=" + System.nanoTime());
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
		public String asyncWork() { return FixtureRepository.asyncWork(); }
		public String asyncWorkA() { return FixtureRepository.asyncWorkA(); }
		public String asyncWorkB() { return FixtureRepository.asyncWorkB(); }
		public String asyncFailure() { return FixtureRepository.asyncFailure(); }
		public String errorTarget() { return FixtureRepository.errorTarget(); }
		public String fail() { return FixtureRepository.fail(); }
		public static void afterFilterChain() { FixtureRepository.afterFilterChain(); }
	}

	public static final class FixtureRepository {
		public static void servletService() { }
		public static void listenerInitialized() { }
		public static void listenerDestroyed() { }
		public static void listenerStart() { }
		public static void listenerStartA() { }
		public static void listenerStartB() { }
		public static void listenerComplete() { }
		public static void listenerCompleteA() { }
		public static void listenerCompleteB() { }
		public static void listenerTimeout() { }
		public static void listenerError() { }
		public static String readA() { return "a"; }
		public static String readB() { return "b"; }
		public static String readOrdinary() { return "ordinary"; }
		public static String forwardSource() { return "forward-source"; }
		public static String forwardTarget() { return "forward-target"; }
		public static String includeSource() { return "include-source"; }
		public static String includeTarget() { return "include-target"; }
		public static String asyncTarget() { return "async-target"; }
		public static String asyncWork() { return "async-work"; }
		public static String asyncWorkA() { return "async-work-a"; }
		public static String asyncWorkB() { return "async-work-b"; }
		public static String asyncFailure() { throw new IllegalStateException("fixture async failure"); }
		public static String errorTarget() { return "error-target"; }
		public static String fail() { throw new IllegalStateException("fixture failure"); }
		public static void afterFilterChain() { }
	}
}
