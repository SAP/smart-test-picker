// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Only the measured public callback-registration signatures. The caller still invokes Method.invoke. */
public final class RemoteReflectiveCallbacks {
    private RemoteReflectiveCallbacks() { }

    public static Object[] arguments(Method method, Object receiver, Object[] arguments) {
        if (method == null || arguments == null) return arguments;
        Class<?> owner = method.getDeclaringClass();
        int callback;
        Class<?> type;
        if (owner == CompletableFuture.class && receiver != null && receiver.getClass() == CompletableFuture.class
                && method.getReturnType() == CompletableFuture.class) {
            if (matches(method, "thenApply", Function.class)) { callback = 0; type = Function.class; }
            else if (matches(method, "thenApplyAsync", Function.class, Executor.class)) { callback = 0; type = Function.class; }
            else if (matches(method, "thenCombine", CompletionStage.class, BiFunction.class)) { callback = 1; type = BiFunction.class; }
            else return arguments;
        } else if (owner == ScheduledExecutorService.class && receiver instanceof ScheduledExecutorService
                && method.getReturnType() == ScheduledFuture.class
                && (matches(method, "scheduleAtFixedRate", Runnable.class, long.class, long.class, TimeUnit.class)
                    || matches(method, "scheduleWithFixedDelay", Runnable.class, long.class, long.class, TimeUnit.class))) {
            callback = 0; type = Runnable.class;
        } else return arguments;

        // Leave malformed calls entirely to Method.invoke, including primitive widening and its
        // access/argument checks. Never mutate the caller's reusable Object[] or change the receiver.
        Class<?>[] parameters = method.getParameterTypes();
        if (parameters.length != arguments.length || !type.isInstance(arguments[callback])) return arguments;
        for (int i = 0; i < parameters.length; i++) {
            Object value = arguments[i];
            if (parameters[i] == long.class) {
                if (!(value instanceof Byte || value instanceof Short || value instanceof Integer
                        || value instanceof Long || value instanceof Character)) return arguments;
            } else if (value != null && !parameters[i].isInstance(value)) return arguments;
        }
        // clone() would retain a covariant concrete callback[] type and reject the wrapper with
        // ArrayStoreException even though that array is a valid Method.invoke argument array.
        Object[] copy = Arrays.copyOf(arguments, arguments.length, Object[].class);
        if (type == Runnable.class) copy[callback] = RemoteTestContext.wrap((Runnable) arguments[callback]);
        else if (type == Function.class) copy[callback] = RemoteTestContext.wrapFunction((Function<?, ?>) arguments[callback]);
        else copy[callback] = RemoteTestContext.wrapBiFunction((BiFunction<?, ?, ?>) arguments[callback]);
        return copy;
    }

    private static boolean matches(Method method, String name, Class<?>... parameters) {
        return method.getName().equals(name) && Arrays.equals(method.getParameterTypes(), parameters);
    }
}
