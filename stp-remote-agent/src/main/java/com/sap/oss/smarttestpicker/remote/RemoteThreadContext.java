// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/** Start-to-run handoff only. Current-context storage and restoration belong to OpenTelemetry. */
public final class RemoteThreadContext {
    private static final Map<Key, Launch> PENDING = new HashMap<>();
    private static final ReferenceQueue<Thread> QUEUE = new ReferenceQueue<>();
    private static final Scope NOOP = () -> { };
    private RemoteThreadContext() { }

    /** Constructor/factory adapter: deliberately captures no construction-time Context. */
    public static Runnable prepare(Runnable task) {
        if (task == null) return null;
        return () -> {
            try (Scope ignored = enter(Thread.currentThread())) { task.run(); }
        };
    }

    /** Preserves virtual dispatch and the JDK's start failure, without transforming Thread itself. */
    public static void start(Thread thread) {
        if (thread == null) throw new NullPointerException("thread");
        Launch launch;
        synchronized (PENDING) {
            expunge();
            if (thread.getState() == Thread.State.NEW) {
                launch = PENDING.computeIfAbsent(new Key(thread, QUEUE), ignored -> new Launch());
            } else launch = null;
        }
        if (launch == null) { thread.start(); return; }
        // Serialize competing instrumented starts on a private token, not the application's monitor.
        // run() never takes this token's monitor, so the started thread can execute immediately.
        synchronized (launch) {
            synchronized (PENDING) {
                if (thread.getState() == Thread.State.NEW) {
                    launch.context = Context.current();
                    PENDING.put(new Key(thread, QUEUE), launch);
                }
            }
            try { thread.start(); }
            finally {
                synchronized (PENDING) {
                    // Keep an empty weak token after a failed start: waiting retry callers must
                    // continue using the same lock. Never erase a successfully started owner.
                    if (thread.getState() == Thread.State.NEW) launch.context = null;
                }
            }
        }
    }

    /** Consume once, on the actual started thread. Direct run() calls remain synchronous. */
    public static Scope enter(Thread receiver) {
        if (receiver != Thread.currentThread()) return NOOP;
        Launch launch;
        synchronized (PENDING) { expunge(); launch = PENDING.remove(new Key(receiver)); }
        return launch == null || launch.context == null ? NOOP : launch.context.makeCurrent();
    }

    static int pendingCount() { synchronized (PENDING) { expunge(); return (int) PENDING.values().stream().filter(launch -> launch.context != null).count(); } }
    private static void expunge() { Key key; while ((key = (Key) QUEUE.poll()) != null) PENDING.remove(key); }
    private static final class Launch { private Context context; }
    private static final class Key extends WeakReference<Thread> {
        private final int hash;
        Key(Thread thread) { super(thread); hash = System.identityHashCode(thread); }
        Key(Thread thread, ReferenceQueue<Thread> queue) { super(thread, queue); hash = System.identityHashCode(thread); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return this == other || other instanceof Key key && get() != null && get() == key.get();
        }
    }
}
