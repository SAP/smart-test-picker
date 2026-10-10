// SPDX-License-Identifier: Apache-2.0
package example.indirectsupport;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import example.indirectsupport.IndirectWork;

/** The same invocation bytecode is compiled inside and outside the configured includes. */
public final class OutsideCalls {
    public static Object invoke(String route, String shape, Method method, MethodHandle handle,
                                Object receiver, Object[] args) throws Throwable {
        if (route.equals("reflection")) return method.invoke(receiver, args);
        boolean bound = route.startsWith("bound-");
        if (route.endsWith("arguments")) {
            List<Object> values = new ArrayList<>();
            if (!bound) values.add(receiver);
            Collections.addAll(values, args);
            return handle.invokeWithArguments(values);
        }
        if (route.endsWith("invoke")) {
            // Object-typed invoke exercises MethodHandle's normal argument/return adaptation.
            if (bound) switch (args.length) {
                case 0: return handle.invoke();
                case 1: return handle.invoke(args[0]);
                case 2: return handle.invoke(args[0], args[1]);
                case 3: return handle.invoke(args[0], args[1], args[2]);
                case 4: return handle.invoke(args[0], args[1], args[2], args[3]);
                default: throw new AssertionError();
            }
            switch (args.length) {
                case 0: return handle.invoke(receiver);
                case 1: return handle.invoke(receiver, args[0]);
                case 2: return handle.invoke(receiver, args[0], args[1]);
                case 3: return handle.invoke(receiver, args[0], args[1], args[2]);
                case 4: return handle.invoke(receiver, args[0], args[1], args[2], args[3]);
                default: throw new AssertionError();
            }
        }
        switch (shape) {
            case "execute":
                if (bound) handle.invokeExact((Runnable) args[0]); else handle.invokeExact((Executor) receiver, (Runnable) args[0]);
                return null;
            case "customExecute":
                if (bound) handle.invokeExact((Runnable) args[0]); else handle.invokeExact((IndirectWork.MeasuredExecutor) receiver, (Runnable) args[0]);
                return null;
            case "submitRunnable":
                if (bound) return (Future) handle.invokeExact((Runnable) args[0]);
                return (Future) handle.invokeExact((ExecutorService) receiver, (Runnable) args[0]);
            case "submitResult":
                if (bound) return (Future) handle.invokeExact((Runnable) args[0], (Object) args[1]);
                return (Future) handle.invokeExact((ExecutorService) receiver, (Runnable) args[0], (Object) args[1]);
            case "submitCallable":
                if (bound) return (Future) handle.invokeExact((Callable) args[0]);
                return (Future) handle.invokeExact((ExecutorService) receiver, (Callable) args[0]);
            case "all":
                if (bound) return (List) handle.invokeExact((Collection) args[0]);
                return (List) handle.invokeExact((ExecutorService) receiver, (Collection) args[0]);
            case "allTimed":
                if (bound) return (List) handle.invokeExact((Collection) args[0], ((Long) args[1]).longValue(), (TimeUnit) args[2]);
                return (List) handle.invokeExact((ExecutorService) receiver, (Collection) args[0], ((Long) args[1]).longValue(), (TimeUnit) args[2]);
            case "any":
                if (bound) return (Object) handle.invokeExact((Collection) args[0]);
                return (Object) handle.invokeExact((ExecutorService) receiver, (Collection) args[0]);
            case "anyTimed":
                if (bound) return (Object) handle.invokeExact((Collection) args[0], ((Long) args[1]).longValue(), (TimeUnit) args[2]);
                return (Object) handle.invokeExact((ExecutorService) receiver, (Collection) args[0], ((Long) args[1]).longValue(), (TimeUnit) args[2]);
            case "scheduleRunnable":
                if (bound) return (ScheduledFuture) handle.invokeExact((Runnable) args[0], ((Long) args[1]).longValue(), (TimeUnit) args[2]);
                return (ScheduledFuture) handle.invokeExact((ScheduledExecutorService) receiver, (Runnable) args[0], ((Long) args[1]).longValue(), (TimeUnit) args[2]);
            case "scheduleCallable":
                if (bound) return (ScheduledFuture) handle.invokeExact((Callable) args[0], ((Long) args[1]).longValue(), (TimeUnit) args[2]);
                return (ScheduledFuture) handle.invokeExact((ScheduledExecutorService) receiver, (Callable) args[0], ((Long) args[1]).longValue(), (TimeUnit) args[2]);
            case "fixedRate":
                if (bound) return (ScheduledFuture) handle.invokeExact((Runnable) args[0], ((Long) args[1]).longValue(), ((Long) args[2]).longValue(), (TimeUnit) args[3]);
                return (ScheduledFuture) handle.invokeExact((ScheduledExecutorService) receiver, (Runnable) args[0], ((Long) args[1]).longValue(), ((Long) args[2]).longValue(), (TimeUnit) args[3]);
            case "fixedDelay":
                if (bound) return (ScheduledFuture) handle.invokeExact((Runnable) args[0], ((Long) args[1]).longValue(), ((Long) args[2]).longValue(), (TimeUnit) args[3]);
                return (ScheduledFuture) handle.invokeExact((ScheduledExecutorService) receiver, (Runnable) args[0], ((Long) args[1]).longValue(), ((Long) args[2]).longValue(), (TimeUnit) args[3]);
            case "apply":
                if (bound) return (CompletableFuture) handle.invokeExact((Function) args[0]);
                return (CompletableFuture) handle.invokeExact((CompletableFuture) receiver, (Function) args[0]);
            case "applyAsync":
                if (bound) return (CompletableFuture) handle.invokeExact((Function) args[0], (Executor) args[1]);
                return (CompletableFuture) handle.invokeExact((CompletableFuture) receiver, (Function) args[0], (Executor) args[1]);
            case "combine":
                if (bound) return (CompletableFuture) handle.invokeExact((CompletionStage) args[0], (BiFunction) args[1]);
                return (CompletableFuture) handle.invokeExact((CompletableFuture) receiver, (CompletionStage) args[0], (BiFunction) args[1]);
            case "start":
                if (bound) handle.invokeExact(); else handle.invokeExact((Thread) receiver);
                return null;
            default: throw new AssertionError(shape);
        }
    }
}
