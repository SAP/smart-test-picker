// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import java.util.concurrent.*;

/** Test-only coordination around the actual recorder checkpoint path; no new production API. */
public final class ChainCheckpointControl {
    private static CountDownLatch captured, persist;
    private static FutureTask<Void> checkpoint;
    public static synchronized void begin() throws Exception {
        if (checkpoint != null) throw new IllegalStateException("checkpoint already active");
        captured = new CountDownLatch(1); persist = new CountDownLatch(1);
        RemoteRecorder.beforeCheckpointPersistForTests(() -> {
            captured.countDown();
            try { if (!persist.await(20, TimeUnit.SECONDS)) throw new AssertionError("checkpoint not released"); }
            catch (InterruptedException e) { throw new AssertionError(e); }
        });
        checkpoint = new FutureTask<>(() -> { RemoteRecorder.checkpointNow(); return null; });
        new Thread(checkpoint, "chain-checkpoint-control").start();
        if (!captured.await(20, TimeUnit.SECONDS)) throw new AssertionError("checkpoint snapshot not captured");
    }
    public static synchronized void finish() throws Exception {
        persist.countDown();
        try { checkpoint.get(20, TimeUnit.SECONDS); }
        finally { RemoteRecorder.beforeCheckpointPersistForTests(null); checkpoint = null; }
    }
    public static void checkpoint() { RemoteRecorder.checkpointNow(); }
    public static String memory() { return RemoteRecorder.memorySnapshot(); }
    public static String snapshot() { return RemoteRecorder.logicalSnapshot(); }
    public static String output() throws Exception {
        var output = RemoteRecorder.persistedOutput();
        return output == null ? null : new String(output.content(), java.nio.charset.StandardCharsets.UTF_8);
    }
}
