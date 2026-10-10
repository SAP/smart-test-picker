// SPDX-License-Identifier: Apache-2.0
package example.chains;

import example.chainsupport.ChainState;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Function;

/** Real included application call sites. Coordination/callback objects live outside includes. */
public final class ChainBoundaries {
    public static void execute(Executor executor, Runnable work) { executor.execute(work); }
    public static Future<?> submit(ExecutorService executor, Runnable work) { return executor.submit(work); }
    public static void fork(Runnable work) { ForkJoinPool.commonPool().execute(work); }
    public static List<Future<String>> bulk(ExecutorService executor, List<Callable<String>> work) throws InterruptedException {
        return executor.invokeAll(work);
    }
    public static CompletableFuture<String> callback(CompletableFuture<ChainState> source,
            Function<ChainState, String> callback, Executor executor) {
        return source.thenApplyAsync(callback, executor);
    }
    public static ScheduledFuture<?> schedule(ScheduledExecutorService executor, Runnable work) {
        return executor.schedule(work, 0, TimeUnit.MILLISECONDS);
    }
    public static Thread thread(Runnable work) {
        Thread thread = new Thread(work, "chain-platform"); thread.start(); return thread;
    }
    public static Thread virtual(Runnable work) { return Thread.ofVirtual().name("chain-virtual").start(work); }
    public static Future<?> reflection(ExecutorService executor, Callable<String> work) throws Exception {
        Method method = ExecutorService.class.getMethod("submit", Callable.class);
        return (Future<?>) method.invoke(executor, work);
    }
    public static Future<?> handle(ExecutorService executor, Callable<String> work) throws Throwable {
        MethodHandle handle = java.lang.invoke.MethodHandles.publicLookup().findVirtual(ExecutorService.class,
                "submit", java.lang.invoke.MethodType.methodType(Future.class, Callable.class));
        return (Future<?>) handle.invokeExact(executor, work);
    }
}
