// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package task19.fixtures;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class OracleFixtureMain {
    public static void main(String[] args) throws Exception {
        FixtureService service = new FixtureService();
        service.publicCall();
        service.overloaded(1);
        service.overloaded("x");
        try { service.exceptionPath(); } catch (ExpectedException ignored) { }
        ((GenericContract<String>) new StringContract()).convert("bridge");
        Runnable lambda = () -> FixtureService.lambdaTarget();
        lambda.run();
        Method reflected = FixtureService.class.getDeclaredMethod("reflectionTarget");
        reflected.invoke(null);
        Thread raw = new Thread(FixtureService::rawThreadTarget, "oracle-raw-thread");
        raw.start(); raw.join();
        try (ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "oracle-executor"))) {
            executor.submit(FixtureService::executorTarget).get();
        }
        CompletableFuture.runAsync(FixtureService::completableFutureTarget).join();
        Thread virtual = Thread.ofVirtual().name("oracle-virtual-thread").start(FixtureService::virtualThreadTarget);
        virtual.join();
    }
}

final class FixtureService {
    static { staticInitializer(); }
    static void staticInitializer() { }
    FixtureService() { }
    void publicCall() { privateHelper(); staticHelper(); selfInvocation(); }
    private void privateHelper() { }
    static void staticHelper() { }
    private void selfInvocation() { }
    void overloaded(int ignored) { }
    void overloaded(String ignored) { }
    void exceptionPath() { throw new ExpectedException(); }
    static void lambdaTarget() { }
    static void reflectionTarget() { }
    static void rawThreadTarget() { }
    static void executorTarget() { }
    static void completableFutureTarget() { }
    static void virtualThreadTarget() { }
}

interface GenericContract<T> { T convert(T value); }
final class StringContract implements GenericContract<String> {
    @Override public String convert(String value) { return value; }
}
final class ExpectedException extends RuntimeException { }
