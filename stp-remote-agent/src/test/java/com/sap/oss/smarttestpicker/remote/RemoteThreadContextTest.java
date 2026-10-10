// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class RemoteThreadContextTest {
    @Test void startCaptureOverridesConstructionAndRestoresTheFullPreviousContextEvenOnErrors() throws Exception {
        var extra = ContextKey.<String>named("thread-unit");
        Context construction = TestRequests.request("construction").toBaggage(Context.root().with(extra, "construction"));
        Context previous = TestRequests.request("previous").toBaggage(Context.root().with(extra, "previous"));
        for (boolean headerless : new boolean[] {false, true}) for (int mode = 0; mode < 3; mode++) {
            Context started = headerless ? Context.root().with(extra, "headerless") : TestRequests.request("started").toBaggage(Context.root().with(extra, "started"));
            int outcome = mode;
            Runnable prepared;
            try (var ignored = construction.makeCurrent()) {
                prepared = RemoteThreadContext.prepare(() -> {
                    assertSame(started, Context.current());
                    if (outcome == 1) throw new IllegalStateException("original");
                    if (outcome == 2) throw new AssertionError("original");
                });
            }
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread thread = new Thread(() -> {
                try (var ignored = previous.makeCurrent()) {
                    if (outcome == 0) prepared.run();
                    else if (outcome == 1) assertEquals("original", assertThrows(IllegalStateException.class, prepared::run).getMessage());
                    else assertEquals("original", assertThrows(AssertionError.class, prepared::run).getMessage());
                    assertSame(previous, Context.current());
                } catch (Throwable e) { failure.set(e); }
            });
            try (var ignored = started.makeCurrent()) { RemoteThreadContext.start(thread); assertSame(started, Context.current()); }
            thread.join(10000); assertFalse(thread.isAlive()); assertNull(failure.get(), String.valueOf(failure.get()));
        }
        assertEquals(0, RemoteThreadContext.pendingCount());
        assertNull(RemoteThreadContext.prepare(null));
        assertThrows(NullPointerException.class, () -> RemoteThreadContext.start(null));
    }

    @Test void reusedRunnableAndFailedSecondStartCannotOverwriteTheOriginalOwner() throws Exception {
        Context a = TestRequests.request("A").toBaggage(Context.root());
        Context b = TestRequests.request("B").toBaggage(Context.root());
        CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
        ConcurrentMap<Thread, Context> actual = new ConcurrentHashMap<>();
        Runnable same = RemoteThreadContext.prepare(() -> {
            entered.countDown();
            try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("release"); }
            catch (InterruptedException e) { throw new AssertionError(e); }
            actual.put(Thread.currentThread(), Context.current());
        });
        Thread first = new Thread(same), second = new Thread(same);
        try {
            try (var ignored = a.makeCurrent()) { RemoteThreadContext.start(first); }
            try (var ignored = b.makeCurrent()) {
                RemoteThreadContext.start(second);
                assertThrows(IllegalThreadStateException.class, () -> RemoteThreadContext.start(first));
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS));
        } finally { release.countDown(); first.join(10000); second.join(10000); }
        assertSame(a, actual.get(first)); assertSame(b, actual.get(second));
        assertEquals(0, RemoteThreadContext.pendingCount());
    }

    @Test void competingStartsRetainTheSuccessfulCallersContext() throws Exception {
        Context a = TestRequests.request("competing-A").toBaggage(Context.root());
        Context b = TestRequests.request("competing-B").toBaggage(Context.root());
        CountDownLatch race = new CountDownLatch(1), ready = new CountDownLatch(2), release = new CountDownLatch(1);
        AtomicReference<Context> winner = new AtomicReference<>(), actual = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger rejected = new AtomicInteger();
        Thread target = new Thread(RemoteThreadContext.prepare(() -> {
            try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("release"); }
            catch (InterruptedException e) { throw new AssertionError(e); }
            actual.set(Context.current());
        }));
        java.util.List<Thread> callers = new java.util.ArrayList<>();
        for (Context owner : java.util.List.of(a, b)) {
            Thread caller = new Thread(() -> {
                try (var ignored = owner.makeCurrent()) {
                    ready.countDown();
                    if (!race.await(10, TimeUnit.SECONDS)) throw new AssertionError("race");
                    try { RemoteThreadContext.start(target); winner.set(owner); }
                    catch (IllegalThreadStateException expected) { rejected.incrementAndGet(); }
                } catch (Throwable e) { failure.set(e); }
            });
            callers.add(caller); caller.start();
        }
        try {
            assertTrue(ready.await(10, TimeUnit.SECONDS)); race.countDown();
            for (Thread caller : callers) { caller.join(10000); assertFalse(caller.isAlive()); }
        } finally { race.countDown(); release.countDown(); target.join(10000); }
        assertNull(failure.get()); assertEquals(1, rejected.get()); assertNotNull(winner.get());
        assertSame(winner.get(), actual.get()); assertEquals(0, RemoteThreadContext.pendingCount());
    }

    @Test void failedStartReleasesPendingContextAndDirectRunDoesNotUseConstructionContext() {
        Thread failing = new Thread() { @Override public synchronized void start() { throw new IllegalStateException("rejected"); } };
        try (var ignored = TestRequests.request("A").toBaggage(Context.root()).makeCurrent()) {
            assertEquals("rejected", assertThrows(IllegalStateException.class, () -> RemoteThreadContext.start(failing)).getMessage());
        }
        assertEquals(0, RemoteThreadContext.pendingCount());
        AtomicReference<Context> actual = new AtomicReference<>();
        Thread direct;
        try (var ignored = TestRequests.request("A").toBaggage(Context.root()).makeCurrent()) {
            direct = new Thread(RemoteThreadContext.prepare(() -> actual.set(Context.current())));
        }
        Context b = TestRequests.request("B").toBaggage(Context.root());
        try (var ignored = b.makeCurrent()) { direct.run(); assertSame(b, actual.get()); }
    }
}
