// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.springremote;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.context.request.async.WebAsyncTask;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

@RestController
public class SpringMvcController {
	private static final CyclicBarrier OVERLAP_BARRIER = new CyclicBarrier(2);
	private final SpringMvcService service;
	private final AsyncTaskExecutor taskExecutor;
	public SpringMvcController(SpringMvcService service,
			@Qualifier("applicationTaskExecutor") AsyncTaskExecutor taskExecutor) {
		this.service = service;
		this.taskExecutor = taskExecutor;
	}

	@GetMapping("/callable") public Callable<String> callable() {
		return callable(false, false);
	}
	@GetMapping("/callable-overlap") public Callable<String> callableOverlap() {
		return callable(true, false);
	}
	@GetMapping("/callable-fail") public Callable<String> callableFailure() {
		return callable(false, true);
	}
	private Callable<String> callable(boolean overlap, boolean fail) {
		String requestThread = Thread.currentThread().getName();
		System.out.println("SPRING_TRANSITION:callable:request=" + requestThread);
		return () -> {
			System.out.println("SPRING_TRANSITION:callable:worker=" + Thread.currentThread().getName());
			pauseBriefly();
			if (overlap) awaitOverlap("callable");
			if (fail) return service.callableFailure();
			return service.callable();
		};
	}

	@GetMapping("/web-async-task") public WebAsyncTask<String> webAsyncTask() {
		return webAsyncTask(false, false);
	}
	@GetMapping("/web-async-task-overlap") public WebAsyncTask<String> webAsyncTaskOverlap() {
		return webAsyncTask(true, false);
	}
	@GetMapping("/web-async-task-fail") public WebAsyncTask<String> webAsyncTaskFailure() {
		return webAsyncTask(false, true);
	}
	private WebAsyncTask<String> webAsyncTask(boolean overlap, boolean fail) {
		String requestThread = Thread.currentThread().getName();
		System.out.println("SPRING_TRANSITION:webAsyncTask:request=" + requestThread);
		Callable<String> callback = () -> {
			System.out.println("SPRING_TRANSITION:webAsyncTask:worker=" + Thread.currentThread().getName());
			pauseBriefly();
			if (overlap) awaitOverlap("webAsyncTask");
			if (fail) return service.webAsyncTaskFailure();
			return service.webAsyncTask();
		};
		return new WebAsyncTask<>(10_000L, taskExecutor, callback);
	}

	@GetMapping("/deferred") public DeferredResult<String> deferred() {
		System.out.println("SPRING_TRANSITION:deferred:request=" + Thread.currentThread().getName());
		DeferredResult<String> result = new DeferredResult<>(10_000L);
		service.deferred().whenComplete((value, failure) -> {
			if (failure == null) result.setResult(value); else result.setErrorResult(failure);
		});
		return result;
	}

	@GetMapping("/async") public CompletableFuture<String> async() {
		System.out.println("SPRING_TRANSITION:async:request=" + Thread.currentThread().getName());
		return service.asyncService();
	}
	@GetMapping("/async-fail") public CompletableFuture<String> asyncFailure() {
		System.out.println("SPRING_TRANSITION:asyncFailure:request=" + Thread.currentThread().getName());
		return service.asyncServiceFailure();
	}
	@GetMapping("/async-overlap-a") public CompletableFuture<String> asyncOverlapA() { return service.asyncOverlapA(); }
	@GetMapping("/async-overlap-b") public CompletableFuture<String> asyncOverlapB() { return service.asyncOverlapB(); }

	@GetMapping("/task-executor/execute") public String taskExecutorExecute() throws Exception {
		return service.asyncTaskExecutorExecute(taskExecutor);
	}
	@GetMapping("/task-executor/submit-runnable") public String taskExecutorSubmitRunnable() throws Exception {
		return service.asyncTaskExecutorSubmitRunnable(taskExecutor);
	}
	@GetMapping("/task-executor/submit-callable") public String taskExecutorSubmitCallable() throws Exception {
		return service.asyncTaskExecutorSubmitCallable(taskExecutor);
	}

	@GetMapping("/health") public String health() { return "ready"; }

	private static void pauseBriefly() {
		try { Thread.sleep(150); }
		catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
	}
	private static void awaitOverlap(String scenario) {
		long started = System.nanoTime();
		System.out.println("SPRING_OVERLAP:START:" + scenario + ":" + started);
		try { OVERLAP_BARRIER.await(5, TimeUnit.SECONDS); }
		catch (Exception failure) { throw new IllegalStateException("async overlap barrier failed", failure); }
		System.out.println("SPRING_OVERLAP:END:" + scenario + ":" + System.nanoTime());
	}
}
