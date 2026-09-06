// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package task19.oracle;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicLong;

final class LifecycleRecorder {
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static BufferedWriter writer;

    static synchronized void record(String identity, String phase, String detail) {
        String output = System.getProperty("task19.lifecycle.output");
        if (output == null || output.isBlank()) return;
        try {
            if (writer == null) {
                Path path = Path.of(output);
                if (path.getParent() != null) Files.createDirectories(path.getParent());
                writer = Files.newBufferedWriter(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                Runtime.getRuntime().addShutdownHook(new Thread(LifecycleRecorder::close, "task19-lifecycle-writer"));
            }
            Thread thread = Thread.currentThread();
            writer.write("{\"sequence\":" + SEQUENCE.incrementAndGet()
                    + ",\"monotonicNanos\":" + System.nanoTime()
                    + ",\"wallClockMillis\":" + System.currentTimeMillis()
                    + ",\"threadId\":" + thread.threadId()
                    + ",\"threadName\":\"" + escape(thread.getName())
                    + "\",\"testIdentity\":\"" + escape(identity)
                    + "\",\"phase\":\"" + phase
                    + "\",\"detail\":\"" + escape(detail) + "\"}\n");
            writer.flush();
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot write TASK 19 lifecycle event", failure);
        }
    }

    private static String escape(String value) {
        return (value == null ? "" : value).replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static synchronized void close() {
        if (writer == null) return;
        try { writer.close(); } catch (IOException ignored) { }
        writer = null;
    }
}
