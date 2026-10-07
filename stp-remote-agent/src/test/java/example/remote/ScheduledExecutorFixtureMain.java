// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.remote;

import com.sap.oss.smarttestpicker.remote.RemoteTestContext;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Real HTTP fixture exercising delayed and periodic scheduled work in a separate server JVM. */
public final class ScheduledExecutorFixtureMain {
	private static final InspectingScheduler SINGLE = new InspectingScheduler(1);
	private static final InspectingScheduler PARALLEL = new InspectingScheduler(2);
	private static final Map<String, Future<?>> TASKS = new ConcurrentHashMap<>();
	private static final Map<String, Long> TASK_THREADS = new ConcurrentHashMap<>();
	private static final Map<String, PeriodicState> PERIODIC = new ConcurrentHashMap<>();
	private static final CyclicBarrier CONCURRENT = new CyclicBarrier(2);
	private ScheduledExecutorFixtureMain() { }

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
				if (path.startsWith("/schedule-runnable/")) {
					String key = suffix(path);
					TASKS.put(key, SINGLE.schedule(() -> ScheduledApplication.delayedRunnable(key), 1000, TimeUnit.MILLISECONDS));
					body = "scheduled";
				} else if (path.startsWith("/schedule-callable/")) {
					String key = suffix(path);
					TASKS.put(key, SINGLE.schedule((Callable<String>) () -> ScheduledApplication.delayedCallable(key), 1000, TimeUnit.MILLISECONDS));
					body = "scheduled";
				} else if (path.startsWith("/await/")) {
					String key = suffix(path);
					Object result = TASKS.remove(key).get(8, TimeUnit.SECONDS);
					body = result == null ? Long.toString(TASK_THREADS.get(key)) : result.toString();
				} else if (path.startsWith("/fixed-rate/")) {
					String key = suffix(path);
					PeriodicState state = new PeriodicState(); PERIODIC.put(key, state);
					state.future = SINGLE.scheduleAtFixedRate(() -> ScheduledApplication.fixedRate(key, state), 400, 1600, TimeUnit.MILLISECONDS);
					body = "registered";
				} else if (path.startsWith("/fixed-delay/")) {
					String key = suffix(path);
					PeriodicState state = new PeriodicState(); PERIODIC.put(key, state);
					state.future = SINGLE.scheduleWithFixedDelay(() -> ScheduledApplication.fixedDelay(key, state), 400, 700, TimeUnit.MILLISECONDS);
					body = "registered";
				} else if (path.startsWith("/wait-first/")) {
					PeriodicState state = PERIODIC.get(suffix(path));
					if (!state.first.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("first periodic execution timed out");
					body = Long.toString(state.firstThread);
				} else if (path.startsWith("/wait-second/")) {
					String key = suffix(path); PeriodicState state = PERIODIC.get(key);
					if (!state.second.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("second periodic execution timed out");
					state.future.cancel(false);
					body = state.count + ":" + state.firstThread;
				} else if (path.startsWith("/schedule-worker-b/")) {
					String key = suffix(path);
					body = Long.toString(SINGLE.schedule((Callable<Long>) () -> ScheduledApplication.workerB(key), 0, TimeUnit.MILLISECONDS)
							.get(5, TimeUnit.SECONDS));
				} else if (path.startsWith("/failure/")) {
					String key = suffix(path);
					try { SINGLE.schedule(() -> ScheduledApplication.failure(key), 0, TimeUnit.MILLISECONDS).get(5, TimeUnit.SECONDS); }
					catch (ExecutionException expected) { body = "failed"; response.setStatus(200); response.getWriter().write(body); return; }
					throw new IllegalStateException("scheduled failure did not fail");
				} else if (path.startsWith("/after-failure/")) {
					String key = suffix(path);
					body = Long.toString(SINGLE.schedule((Callable<Long>) () -> ScheduledApplication.afterFailure(key), 0, TimeUnit.MILLISECONDS)
							.get(5, TimeUnit.SECONDS));
				} else if (path.equals("/no-context")) {
					body = SINGLE.schedule((Callable<String>) ScheduledApplication::noContext,
							0, TimeUnit.MILLISECONDS).get(5, TimeUnit.SECONDS);
				} else if (path.startsWith("/concurrent/")) {
					String key = suffix(path);
					TASKS.put(key, PARALLEL.schedule(() -> ScheduledApplication.concurrent(key), 100, TimeUnit.MILLISECONDS));
					body = "scheduled";
				} else if (path.startsWith("/await-concurrent/")) {
					TASKS.remove(suffix(path)).get(8, TimeUnit.SECONDS); body = "done";
				} else { response.sendError(404); return; }
			} catch (Exception failure) { throw new IOException("scheduled executor fixture failed", failure); }
			response.setStatus(200);
			response.getWriter().write(body);
		}
		private static String suffix(String path) { return path.substring(path.lastIndexOf('/') + 1); }
	}

	private static final class PeriodicState {
		private final CountDownLatch first = new CountDownLatch(1);
		private final CountDownLatch second = new CountDownLatch(1);
		private volatile int count;
		private volatile long firstThread;
		private volatile ScheduledFuture<?> future;
	}

	/** Records context from a later no-context task on the reused worker. */
	public static final class InspectingScheduler extends ScheduledThreadPoolExecutor implements FixtureScheduledExecutor {
		InspectingScheduler(int threads) { super(threads, task -> { Thread t = new Thread(task, "scheduled-fixture-worker"); t.setDaemon(true); return t; }); }
	}

	public static final class ScheduledApplication {
		public static void delayedRunnable(String key) { ScheduledRepository.delayedRunnable(key); TASK_THREADS.put(key, Thread.currentThread().getId()); }
		public static String delayedCallable(String key) { ScheduledRepository.delayedCallable(key); return key + "-result:" + Thread.currentThread().getId(); }
		public static void fixedRate(String key, PeriodicState state) {
			ScheduledRepository.fixedRate(key);
			int execution = ++state.count;
			if (execution == 1) { state.firstThread = Thread.currentThread().getId(); state.first.countDown(); }
			if (execution >= 2) state.second.countDown();
		}
		public static void fixedDelay(String key, PeriodicState state) {
			ScheduledRepository.fixedDelay(key);
			int execution = ++state.count;
			if (execution == 1) { state.firstThread = Thread.currentThread().getId(); state.first.countDown(); }
			if (execution >= 2) state.second.countDown();
		}
		public static long workerB(String key) { ScheduledRepository.workerB(key); return Thread.currentThread().getId(); }
		public static void failure(String key) { ScheduledRepository.failure(key); throw new IllegalStateException("expected scheduled failure"); }
		public static long afterFailure(String key) { ScheduledRepository.afterFailure(key); return Thread.currentThread().getId(); }
		public static String noContext() {
			ScheduledRepository.noContext();
			String id = RemoteTestContext.currentId();
			return id == null ? "clear" : "leaked:" + id;
		}
		public static void concurrent(String key) {
			ScheduledRepository.concurrent(key);
			try { CONCURRENT.await(5, TimeUnit.SECONDS); }
			catch (Exception failure) { throw new IllegalStateException("scheduled callbacks did not overlap", failure); }
		}
	}
	public static final class ScheduledRepository {
		public static void delayedRunnable(String key) { }
		public static void delayedCallable(String key) { }
		public static void fixedRate(String key) { }
		public static void fixedDelay(String key) { }
		public static void workerB(String key) { }
		public static void failure(String key) { }
		public static void afterFailure(String key) { }
		public static void noContext() { }
		public static void concurrent(String key) { if (key.endsWith("A")) concurrentA(); else concurrentB(); }
		public static void concurrentA() { }
		public static void concurrentB() { }
	}
}
