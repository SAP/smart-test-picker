// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.fixture;

import example.instrumented.ExtendedForkJoinApplication;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.IntStream;
import static example.fixture.ForkJoinFixtureMain.*;

/** No manual propagation. Exercises bootstrap boundaries, including calls originating in the JDK. */
public final class ExtendedForkJoinFixtureMain {
	public static void main(String[] args) throws Exception {
		check(Class.forName("com.sap.oss.smarttestpicker.agent.bootstrap.ForkJoinBridge").getClassLoader() == null,
				"bridge must be bootstrap loaded");
		check(CONTEXT.getClass().getClassLoader() != null, "runtime must not be duplicated into bootstrap");
		AtomicReference<Throwable> workerFailure = new AtomicReference<>();
		ForkJoinPool pool = new ForkJoinPool(1, p -> new ProbeWorker(p, "workerAmbient", workerFailure), null, false);
		try {
			// Construct outside any test. Identity belongs to submission, not adapt/construction.
			ForkJoinTask<?> adapted = ForkJoinTask.adapt(() -> ExtendedForkJoinApplication.adapted(1));
			run("adaptFork", () -> { check(adapted.fork() == adapted, "fork identity"); adapted.get(5, TimeUnit.SECONDS); });
			adapted.reinitialize();
			run("adaptReuse", () -> check(pool.submit(adapted) == adapted && adapted.get(5, TimeUnit.SECONDS) == null,
					"reused adapted task"));
			run("adaptDirect", () -> ForkJoinTask.adapt(() -> { owner("adaptDirect"); ExtendedForkJoinApplication.adapted(1); }).invoke());
			run("adaptExecute", () -> {
				ForkJoinTask<?> task = ForkJoinTask.adapt(() -> { owner("adaptExecute"); ExtendedForkJoinApplication.adapted(1); });
				pool.execute(task); task.get(5, TimeUnit.SECONDS);
			});
			Object result = new Object();
			run("adaptResult", () -> check(pool.invoke(ForkJoinTask.adapt(() -> {
				owner("adaptResult"); ExtendedForkJoinApplication.adapted(1);
			}, result)) == result, "Runnable result identity"));
			run("adaptCallable", () -> check(pool.invoke(ForkJoinTask.adapt((Callable<Integer>) () -> {
				owner("adaptCallable"); return ExtendedForkJoinApplication.callable(42);
			})) == 42, "Callable result"));
			// Reflection is an untransformed caller; method-body instrumentation still captures.
			run("reflective", () -> {
				ForkJoinTask<?> task = ForkJoinTask.adapt(() -> { owner("reflective"); ExtendedForkJoinApplication.adapted(1); });
				ForkJoinPool.class.getMethod("submit", ForkJoinTask.class).invoke(pool, task);
				task.get(5, TimeUnit.SECONDS);
			});
			rejected(pool);
			failureAndCleanup(pool);
			cancelled(pool);
			lateAndSetup(pool);
			parallel(false);
			parallel(true);
			run("streamCommon", () -> {
				Thread caller = Thread.currentThread();
				Set<Thread> threads = ConcurrentHashMap.newKeySet();
				CountDownLatch worker = new CountDownLatch(1);
				IntStream.range(0, 64).parallel().forEach(value -> {
					threads.add(Thread.currentThread());
					if (Thread.currentThread() != caller) worker.countDown(); else await(worker);
					owner("streamCommon"); ExtendedForkJoinApplication.streamA(value);
				});
				check(threads.stream().anyMatch(t -> t != caller), "common pool actually used");
			});
			check(CONTEXT.aggregator().snapshot().setupDiagnostics().isEmpty(), "supported diagnostics: "
					+ CONTEXT.aggregator().snapshot().setupDiagnostics());
			System.out.println("forkjoin-extended-ok");
		} finally {
            pool.shutdown(); check(pool.awaitTermination(10, TimeUnit.SECONDS), "pool terminated");
            if (workerFailure.get() != null) throw new AssertionError(workerFailure.get());
        }
	}

	private static void rejected(ForkJoinPool pool) throws Exception {
		ForkJoinPool stopped = new ForkJoinPool(1); stopped.shutdown();
		check(stopped.awaitTermination(5, TimeUnit.SECONDS), "stopped pool");
		ForkJoinTask<?> task = ForkJoinTask.adapt(() -> { owner("acceptedB"); ExtendedForkJoinApplication.rejected(1); });
		for (String api : new String[]{"execute", "submit", "invoke"}) {
			run("rejected-" + api, () -> {
				try {
					if (api.equals("execute")) stopped.execute(task);
					else if (api.equals("submit")) stopped.submit(task);
					else stopped.invoke(task);
					throw new AssertionError("rejection expected");
				} catch (RejectedExecutionException expected) { }
			});
			check(!task.isDone(), "rejection preserves unfinished task");
		}
		run("acceptedB", () -> pool.invoke(task));
	}

	private static void failureAndCleanup(ForkJoinPool pool) throws Exception {
		AtomicReference<Thread> worker = new AtomicReference<>();
		for (boolean error : new boolean[]{false, true}) {
			run(error ? "adaptError" : "adaptException", () -> {
				ForkJoinTask<?> failed = ForkJoinTask.adapt(() -> {
					worker.set(Thread.currentThread()); ExtendedForkJoinApplication.failure(1);
					if (error) throw new AssertionError("expected"); else throw new IllegalStateException("expected");
				});
				pool.execute(failed);
				try { failed.get(5, TimeUnit.SECONDS); throw new AssertionError("failure missing"); }
				catch (ExecutionException expected) {
					check(error ? expected.getCause() instanceof AssertionError : expected.getCause() instanceof IllegalStateException,
							"original exception type");
				}
			});
			// No submission wrapper: verifies no-owner execution suppresses the ambient worker owner.
            // ProbeWorker.onTermination separately verifies restoration outside all task scopes.
			Callable<Boolean> probe = () -> Thread.currentThread() == worker.get() && CONTEXT.currentTest().isEmpty();
			Future<?> result = (Future<?>) ForkJoinPool.class.getMethod("submit", ForkJoinTask.class).invoke(pool, ForkJoinTask.adapt(probe));
			check(Boolean.TRUE.equals(result.get(5, TimeUnit.SECONDS)), "worker context clean after failure");
		}
		pool.invoke(ForkJoinTask.adapt(() -> { check(CONTEXT.currentTest().isEmpty(), "no invented owner");
			ExtendedForkJoinApplication.unowned(1); }));
	}

	private static void cancelled(ForkJoinPool pool) throws Exception {
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		ForkJoinTask<?> block = ForkJoinTask.adapt(() -> { entered.countDown(); await(release); });
		pool.execute(block); await(entered);
		try {
			run("adaptCancelled", () -> {
				ForkJoinTask<?> task = ForkJoinTask.adapt(() -> ExtendedForkJoinApplication.adapted(1));
				pool.execute(task); check(task.cancel(false), "cancellation");
				try { task.join(); throw new AssertionError("cancel must throw"); } catch (CancellationException expected) { }
			});
		} finally { release.countDown(); block.get(5, TimeUnit.SECONDS); }
	}

	private static void lateAndSetup(ForkJoinPool pool) throws Exception {
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		ForkJoinTask<?> block = ForkJoinTask.adapt(() -> { entered.countDown(); await(release); });
		pool.execute(block); await(entered);
		ForkJoinTask<?> task = ForkJoinTask.adapt(() -> { owner("adaptLateA"); ExtendedForkJoinApplication.late(1); });
		try {
			run("adaptLateA", () -> pool.execute(task));
			run("adaptLateB", () -> { release.countDown(); task.get(5, TimeUnit.SECONDS); });
		} finally { release.countDown(); block.get(5, TimeUnit.SECONDS); }
		check(observation("adaptLateA").methods().isEmpty(), "late ordinary coverage empty");
		check(observation("adaptLateB").methods().isEmpty(), "late never attributed B");
		CONTEXT.beginContainer("extendedSetup", "fixture.ExtendedSetup", false);
		try { pool.invoke(ForkJoinTask.adapt(() -> IntStream.range(0, 20).parallel()
				.forEach(ExtendedForkJoinApplication::setup))); }
		finally { CONTEXT.endContainer("extendedSetup"); }
	}

	private static void parallel(boolean streams) throws Exception {
		AtomicReference<Throwable> workerFailure = new AtomicReference<>();
		ForkJoinPool pool = new ForkJoinPool(4, p -> new ProbeWorker(p, null, workerFailure), null, false);
		CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread[] callers = new Thread[2];
		for (int index = 0; index < callers.length; index++) {
			String id = (streams ? "stream" : "counted") + (index == 0 ? "A" : "B");
			callers[index] = new Thread(() -> {
				try { run(id, () -> {
					if (streams) {
						pool.invoke(ForkJoinTask.adapt(() -> {
							entered.countDown(); await(release);
							IntStream.range(0, 64).parallel().forEach(value -> {
								owner(id);
								if (id.endsWith("A")) ExtendedForkJoinApplication.streamA(value);
								else ExtendedForkJoinApplication.streamB("B");
							});
						}));
					} else pool.invoke(new CompletionTree(null, 3, id, entered, release));
				}); } catch (Throwable problem) { failure.compareAndSet(null, problem); }
			});
		}
		try {
			for (Thread caller : callers) caller.start();
			await(entered); release.countDown();
			for (Thread caller : callers) { caller.join(15000); check(!caller.isAlive(), "parallel callers joined"); }
			if (failure.get() != null) throw new AssertionError(failure.get());
		} finally {
            release.countDown(); pool.shutdownNow(); check(pool.awaitTermination(10, TimeUnit.SECONDS), "parallel pool stopped");
            if (workerFailure.get() != null) throw new AssertionError(workerFailure.get());
        }
	}

    /** Runs outside every doExec scope; a task-scoped probe alone would mask stale worker context. */
    private static final class ProbeWorker extends ForkJoinWorkerThread {
        private final String ambientId;
        private final AtomicReference<Throwable> failure;
        private com.sap.oss.smarttestpicker.runtime.model.TestIdentity ambient;
        ProbeWorker(ForkJoinPool pool, String ambientId, AtomicReference<Throwable> failure) {
            super(pool); this.ambientId = ambientId; this.failure = failure;
        }
        @Override protected void onStart() {
            super.onStart();
            if (ambientId != null) {
                ambient = new com.sap.oss.smarttestpicker.runtime.model.TestIdentity(ambientId, ambientId,
                        "fixture.ForkJoinTests", ambientId, "fixture", "fixture-run", CONTEXT.jvmId());
                CONTEXT.beginTest(ambient);
            }
        }
        @Override protected void onTermination(Throwable exception) {
            try {
                if (ambient == null) check(CONTEXT.currentTest().isEmpty(), "worker leaked a test after task scopes");
                else {
                    check(CONTEXT.currentTest().orElseThrow().equals(ambient), "worker previous context restored");
                    ExtendedForkJoinApplication.workerRestored(1);
                    CONTEXT.endTest(ambient, SUCCESS);
                }
            } catch (Throwable problem) { failure.compareAndSet(null, problem); }
            finally { super.onTermination(exception); }
        }
    }

	private static final class CompletionTree extends CountedCompleter<Void> {
		private final int depth;
		private final String id;
		private final CountDownLatch entered, release;
		CompletionTree(CompletionTree parent, int depth, String id, CountDownLatch entered, CountDownLatch release) {
			super(parent); this.depth = depth; this.id = id; this.entered = entered; this.release = release;
		}
		@Override public void compute() {
			if (getCompleter() == null) { entered.countDown(); await(release); }
			owner(id);
			if (id.endsWith("A")) ExtendedForkJoinApplication.countedA(depth);
			else ExtendedForkJoinApplication.countedB("B");
			if (depth > 0) {
				setPendingCount(2);
				new CompletionTree(this, depth - 1, id, entered, release).fork();
				new CompletionTree(this, depth - 1, id, entered, release).fork();
			}
			tryComplete();
		}
		@Override public void onCompletion(CountedCompleter<?> caller) {
			owner(id); ExtendedForkJoinApplication.completion(depth);
		}
	}
}
