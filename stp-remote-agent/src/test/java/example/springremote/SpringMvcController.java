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

@RestController
public class SpringMvcController {
	private final SpringMvcService service;
	private final AsyncTaskExecutor taskExecutor;
	public SpringMvcController(SpringMvcService service,
			@Qualifier("applicationTaskExecutor") AsyncTaskExecutor taskExecutor) {
		this.service = service;
		this.taskExecutor = taskExecutor;
	}

	@GetMapping("/callable") public Callable<String> callable() {
		String requestThread = Thread.currentThread().getName();
		System.out.println("SPRING_TRANSITION:callable:request=" + requestThread);
		return () -> {
			System.out.println("SPRING_TRANSITION:callable:worker=" + Thread.currentThread().getName());
			pauseBriefly();
			return service.callable();
		};
	}

	@GetMapping("/web-async-task") public WebAsyncTask<String> webAsyncTask() {
		String requestThread = Thread.currentThread().getName();
		System.out.println("SPRING_TRANSITION:webAsyncTask:request=" + requestThread);
		Callable<String> callback = () -> {
			System.out.println("SPRING_TRANSITION:webAsyncTask:worker=" + Thread.currentThread().getName());
			pauseBriefly();
			return service.webAsyncTask();
		};
		return new WebAsyncTask<>(10_000L, callback);
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
}
