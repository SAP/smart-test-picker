// SPDX-License-Identifier: Apache-2.0
package example.cf;

import example.cfsupport.StageCallbacks;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Only this class is included in ASM recording and registration call-site instrumentation. */
public final class ObservedStages {
    private ObservedStages() { }
    public static CompletableFuture<?> register(String shape, CompletableFuture<String> source,
            CompletableFuture<String> other, StageCallbacks cb, Executor executor) {
        return switch (shape) {
            case "thenApply-sync" -> source.thenApply(cb::function);
            case "thenApply-async" -> source.thenApplyAsync(cb::function);
            case "thenApply-executor" -> source.thenApplyAsync(cb::function, executor);
            case "thenAccept-sync" -> source.thenAccept(cb::consumer);
            case "thenAccept-async" -> source.thenAcceptAsync(cb::consumer);
            case "thenAccept-executor" -> source.thenAcceptAsync(cb::consumer, executor);
            case "thenRun-sync" -> source.thenRun(cb::runnable);
            case "thenRun-async" -> source.thenRunAsync(cb::runnable);
            case "thenRun-executor" -> source.thenRunAsync(cb::runnable, executor);
            case "thenCompose-sync" -> source.thenCompose(cb::compose);
            case "thenCompose-async" -> source.thenComposeAsync(cb::compose);
            case "thenCompose-executor" -> source.thenComposeAsync(cb::compose, executor);
            case "handle-sync" -> source.handle(cb::handle);
            case "handle-async" -> source.handleAsync(cb::handle);
            case "handle-executor" -> source.handleAsync(cb::handle, executor);
            case "whenComplete-sync" -> source.whenComplete(cb::whenComplete);
            case "whenComplete-async" -> source.whenCompleteAsync(cb::whenComplete);
            case "whenComplete-executor" -> source.whenCompleteAsync(cb::whenComplete, executor);
            case "exceptionally-sync" -> source.exceptionally(cb::recover);
            case "exceptionally-async" -> source.exceptionallyAsync(cb::recover);
            case "exceptionally-executor" -> source.exceptionallyAsync(cb::recover, executor);
            case "thenCombine-sync" -> source.thenCombine(other, cb::combine);
            case "thenCombine-async" -> source.thenCombineAsync(other, cb::combine);
            case "thenCombine-executor" -> source.thenCombineAsync(other, cb::combine, executor);
            case "thenAcceptBoth-sync" -> source.thenAcceptBoth(other, cb::both);
            case "thenAcceptBoth-async" -> source.thenAcceptBothAsync(other, cb::both);
            case "thenAcceptBoth-executor" -> source.thenAcceptBothAsync(other, cb::both, executor);
            case "runAfterBoth-sync" -> source.runAfterBoth(other, cb::runnable);
            case "runAfterBoth-async" -> source.runAfterBothAsync(other, cb::runnable);
            case "runAfterBoth-executor" -> source.runAfterBothAsync(other, cb::runnable, executor);
            case "applyToEither-sync" -> source.applyToEither(other, cb::function);
            case "applyToEither-async" -> source.applyToEitherAsync(other, cb::function);
            case "applyToEither-executor" -> source.applyToEitherAsync(other, cb::function, executor);
            case "acceptEither-sync" -> source.acceptEither(other, cb::consumer);
            case "acceptEither-async" -> source.acceptEitherAsync(other, cb::consumer);
            case "acceptEither-executor" -> source.acceptEitherAsync(other, cb::consumer, executor);
            case "runAfterEither-sync" -> source.runAfterEither(other, cb::runnable);
            case "runAfterEither-async" -> source.runAfterEitherAsync(other, cb::runnable);
            case "runAfterEither-executor" -> source.runAfterEitherAsync(other, cb::runnable, executor);
            default -> throw new IllegalArgumentException(shape);
        };
    }
    public static CompletableFuture<Void> follow(CompletableFuture<?> source, Runnable callback) {
        return source.thenRun(callback);
    }
    public static void a() { }
    public static void b() { }
    public static void c() { }
    public static void anonymous() { }
    public static void completion() { }
    public static void afterCompletion() { }
    public static void continuation() { }
}
