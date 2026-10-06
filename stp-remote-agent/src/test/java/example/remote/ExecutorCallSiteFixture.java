// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.remote;

import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

public final class ExecutorCallSiteFixture {
	public void execute(Executor executor, Runnable task) { executor.execute(task); }
	public Future<?> submitRunnable(ExecutorService executor, Runnable task) { return executor.submit(task); }
	public Future<String> submitRunnableResult(ExecutorService executor, Runnable task) {
		return executor.submit(task, "result");
	}
	public <T> Future<T> submitCallable(ExecutorService executor, Callable<T> task) { return executor.submit(task); }
	public void executeCustom(RemoteServletFixtureMain.FixtureExecutor executor, Runnable task) { executor.execute(task); }
	public CompletableFuture<Void> runAsync(Runnable task) { return CompletableFuture.runAsync(task); }
	public CompletableFuture<Void> runAsyncWithExecutor(Runnable task, Executor executor) { return CompletableFuture.runAsync(task, executor); }
	public <T> CompletableFuture<T> supplyAsync(Supplier<T> task) { return CompletableFuture.supplyAsync(task); }
	public <T> CompletableFuture<T> supplyAsyncWithExecutor(Supplier<T> task, Executor executor) { return CompletableFuture.supplyAsync(task, executor); }
	public CompletableFuture<Void> thenRunAsync(CompletableFuture<?> source, Runnable task) { return source.thenRunAsync(task); }
	public CompletableFuture<Void> thenRunAsyncWithExecutor(CompletableFuture<?> source, Runnable task, Executor executor) { return source.thenRunAsync(task, executor); }
	public <T, R> CompletableFuture<R> thenApplyAsync(CompletableFuture<T> source, Function<T, R> task) { return source.thenApplyAsync(task); }
	public <T, R> CompletableFuture<R> thenApplyAsyncWithExecutor(CompletableFuture<T> source, Function<T, R> task, Executor executor) { return source.thenApplyAsync(task, executor); }
}
