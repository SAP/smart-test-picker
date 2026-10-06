// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.remote;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Small real-HTTP fixture focused on CompletableFuture call-site attribution. */
public final class CompletableFutureFixtureMain {
	private static final Map<String, CompletableFuture<?>> PENDING = new ConcurrentHashMap<>();
	private static final Map<String, CompletableFuture<?>> SOURCE = new ConcurrentHashMap<>();
	private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "cf-fixture-worker"); thread.setDaemon(true); return thread;
	});
	private static final CyclicBarrier CONCURRENT_STAGES = new CyclicBarrier(2);
	private CompletableFutureFixtureMain() { }
	public static void main(String[] args) throws Exception {
		Server server = new Server(0);
		ServletContextHandler context = new ServletContextHandler();
		context.setContextPath("/");
		context.addServlet(new ServletHolder(new Handler()), "/*");
		server.setHandler(context);
		server.start();
		System.out.println("READY:" + server.getURI().getPort());
		System.out.flush();
		server.join();
	}
	public static final class Handler extends HttpServlet {
		@Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
			String path = request.getPathInfo();
			String body;
			try {
				body = switch (path) {
					case "/run-common" -> { CompletableFuture.runAsync(CompletableFutureApplication::runCommon).get(5, TimeUnit.SECONDS); yield "done"; }
					case "/run-explicit" -> { CompletableFuture.runAsync(CompletableFutureApplication::runExplicit, EXECUTOR).get(5, TimeUnit.SECONDS); yield "done"; }
					case "/supply-common" -> CompletableFuture.supplyAsync(CompletableFutureApplication::supplyCommon).get(5, TimeUnit.SECONDS);
					case "/supply-explicit" -> CompletableFuture.supplyAsync(CompletableFutureApplication::supplyExplicit, EXECUTOR).get(5, TimeUnit.SECONDS);
					case "/then-run-register-common" -> registerRun("then-run-common", false);
					case "/then-run-register-explicit" -> registerRun("then-run-explicit", true);
					case "/then-run-register-concurrent-a" -> registerRun("concurrent-a", false);
					case "/then-run-register-concurrent-b" -> registerRun("concurrent-b", false);
					case "/then-apply-register-common" -> registerApply("then-apply-common", false);
					case "/then-apply-register-explicit" -> registerApply("then-apply-explicit", true);
					case "/complete-then-run-common" -> completeRun("then-run-common");
					case "/complete-then-run-explicit" -> completeRun("then-run-explicit");
					case "/complete-concurrent-a" -> completeRun("concurrent-a");
					case "/complete-concurrent-b" -> completeRun("concurrent-b");
					case "/complete-then-apply-common" -> completeApply("then-apply-common");
					case "/complete-then-apply-explicit" -> completeApply("then-apply-explicit");
					case "/run-no-context" -> { CompletableFuture.runAsync(CompletableFutureApplication::noContext).get(5, TimeUnit.SECONDS); yield "done"; }
					case "/run-failure" -> { try { CompletableFuture.runAsync(CompletableFutureApplication::failure, EXECUTOR).join(); }
						catch (java.util.concurrent.CompletionException expected) { yield "failed"; }
						throw new IllegalStateException("expected callback failure"); }
					case "/run-explicit-after-failure" -> { CompletableFuture.runAsync(CompletableFutureApplication::afterFailure, EXECUTOR).get(5, TimeUnit.SECONDS); yield "done"; }
					default -> { response.sendError(404); yield ""; }
				};
			} catch (Exception failure) { throw new IOException("CompletableFuture fixture failed", failure); }
			response.setStatus(200);
			response.getWriter().write(body);
		}
		private static String registerRun(String key, boolean explicit) {
			CompletableFuture<Void> source = new CompletableFuture<>();
			CompletableFuture<Void> result = explicit ? source.thenRunAsync(() -> CompletableFutureApplication.thenRun(key), EXECUTOR)
					: source.thenRunAsync(() -> CompletableFutureApplication.thenRun(key));
			SOURCE.put(key, source); PENDING.put(key, result); return "registered";
		}
		private static String registerApply(String key, boolean explicit) {
			CompletableFuture<String> source = new CompletableFuture<>();
			Function<String, String> function = value -> CompletableFutureApplication.thenApply(key, value);
			CompletableFuture<String> result = explicit ? source.thenApplyAsync(function, EXECUTOR) : source.thenApplyAsync(function);
			SOURCE.put(key, source); PENDING.put(key, result); return "registered";
		}
		private static String completeRun(String key) {
			take(SOURCE, key).complete(null);
			take(PENDING, key).join(); CompletableFutureApplication.completer(); return "complete";
		}
		private static String completeApply(String key) {
			take(SOURCE, key).complete("input");
			String result = Handler.<String>take(PENDING, key).join(); CompletableFutureApplication.completer(); return result;
		}
		@SuppressWarnings("unchecked")
		private static <T> CompletableFuture<T> take(Map<String, CompletableFuture<?>> futures, String key) {
			return (CompletableFuture<T>) futures.remove(key);
		}
	}
	public static final class CompletableFutureApplication {
		public static void runCommon() { CompletableFutureRepository.runCommon(); }
		public static void runExplicit() { CompletableFutureRepository.runExplicit(); }
		public static String supplyCommon() { CompletableFutureRepository.supplyCommon(); return "common-result"; }
		public static String supplyExplicit() { CompletableFutureRepository.supplyExplicit(); return "explicit-result"; }
		public static void thenRun(String key) { CompletableFutureRepository.thenRun(key); }
		public static String thenApply(String key, String input) { return CompletableFutureRepository.thenApply(key, input); }
		public static void completer() { CompletableFutureRepository.completer(); }
		public static void noContext() { CompletableFutureRepository.noContext(); }
		public static void failure() { CompletableFutureRepository.failure(); throw new IllegalStateException("expected"); }
		public static void afterFailure() { CompletableFutureRepository.afterFailure(); }
	}
	public static final class CompletableFutureRepository {
		public static void runCommon() { }
		public static void runExplicit() { }
		public static void supplyCommon() { }
		public static void supplyExplicit() { }
		public static void thenRun(String key) {
			if (key.equals("concurrent-a")) concurrentA();
			else if (key.equals("concurrent-b")) concurrentB();
			else if (key.endsWith("common")) thenRunCommon();
			else thenRunExplicit();
		}
		public static void thenRunCommon() { }
		public static void thenRunExplicit() { }
		public static void concurrentA() {
			awaitConcurrentStages();
			concurrentLeafA();
		}
		public static void concurrentB() {
			awaitConcurrentStages();
			concurrentLeafB();
		}
		private static void awaitConcurrentStages() {
			try { CONCURRENT_STAGES.await(5, TimeUnit.SECONDS); }
			catch (Exception failure) { throw new IllegalStateException("CompletableFuture callbacks did not overlap", failure); }
		}
		public static void concurrentLeafA() { }
		public static void concurrentLeafB() { }
		public static String thenApply(String key, String input) {
			if (key.endsWith("common")) thenApplyCommon(input); else thenApplyExplicit(input);
			return input + "-result";
		}
		public static void thenApplyCommon(String input) { }
		public static void thenApplyExplicit(String input) { }
		public static void completer() { }
		public static void noContext() { }
		public static void failure() { }
		public static void afterFailure() { }
	}
}
