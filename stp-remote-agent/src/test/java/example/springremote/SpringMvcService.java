// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.springremote;

import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class SpringMvcService {
	private static final CyclicBarrier ASYNC_OVERLAP = new CyclicBarrier(2);
	private final Executor applicationExecutor;
	private final SpringMvcRepository repository = new SpringMvcRepository();

	public SpringMvcService(@Qualifier("applicationTaskExecutor") Executor applicationExecutor) {
		this.applicationExecutor = applicationExecutor;
	}

	public String callable() { return repository.hit("callable"); }
	public String webAsyncTask() { return repository.hit("webAsyncTask"); }
	public String callableFailure() {
		repository.hit("callableFailure");
		throw new IllegalStateException("fixture callable failure");
	}
	public String webAsyncTaskFailure() {
		repository.hit("webAsyncTaskFailure");
		throw new IllegalStateException("fixture WebAsyncTask failure");
	}

	public CompletableFuture<String> deferred() {
		CompletableFuture<String> result = new CompletableFuture<>();
		applicationExecutor.execute(() -> {
			pauseBriefly();
			result.complete(repository.hit("deferredComplete"));
		});
		return result;
	}

	@Async("asyncExecutor")
	public CompletableFuture<String> asyncService() {
		System.out.println("SPRING_TRANSITION:async:worker=" + Thread.currentThread().getName());
		pauseBriefly();
		return CompletableFuture.completedFuture(repository.hit("asyncService"));
	}

	@Async("asyncExecutor")
	public CompletableFuture<String> asyncServiceFailure() {
		System.out.println("SPRING_TRANSITION:asyncFailure:worker=" + Thread.currentThread().getName());
		pauseBriefly();
		repository.hit("asyncFailure");
		throw new IllegalStateException("fixture @Async failure");
	}

	@Async("asyncOverlapExecutor")
	public CompletableFuture<String> asyncOverlapA() {
		return asyncOverlap("asyncOverlapA");
	}

	@Async("asyncOverlapExecutor")
	public CompletableFuture<String> asyncOverlapB() {
		return asyncOverlap("asyncOverlapB");
	}

	private CompletableFuture<String> asyncOverlap(String scenario) {
		System.out.println("SPRING_TRANSITION:" + scenario + ":worker=" + Thread.currentThread().getName());
		long started = System.nanoTime();
		System.out.println("SPRING_ASYNC_OVERLAP:START:" + scenario + ":" + started);
		try { ASYNC_OVERLAP.await(5, TimeUnit.SECONDS); }
		catch (Exception failure) { throw new IllegalStateException("@Async overlap failed", failure); }
		return CompletableFuture.completedFuture(repository.hit(scenario));
	}

	public String asyncTaskExecutorExecute(AsyncTaskExecutor executor) throws InterruptedException {
		CountDownLatch done = new CountDownLatch(1);
		AtomicReference<String> result = new AtomicReference<>();
		executor.execute(() -> { result.set(repository.hit("taskExecutorExecute")); done.countDown(); });
		if (!done.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("execute timed out");
		return result.get();
	}

	public String asyncTaskExecutorSubmitRunnable(AsyncTaskExecutor executor) throws Exception {
		AtomicReference<String> result = new AtomicReference<>();
		executor.submit(() -> result.set(repository.hit("taskExecutorSubmitRunnable"))).get(5, TimeUnit.SECONDS);
		return result.get();
	}

	public String asyncTaskExecutorSubmitCallable(AsyncTaskExecutor executor) throws Exception {
		return executor.submit((Callable<String>) () -> repository.hit("taskExecutorSubmitCallable"))
				.get(5, TimeUnit.SECONDS);
	}

	public String deferredAdvice() { return repository.hit("deferredRedispatch"); }

	private static void pauseBriefly() {
		try { Thread.sleep(100); }
		catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
	}
}
