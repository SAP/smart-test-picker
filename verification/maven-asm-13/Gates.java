package observation;

import java.nio.file.*;
import java.util.concurrent.*;

/** Disposable support outside measured production outputs; no collector reset or ownership mutation. */
public final class Gates {
    private static final ConcurrentMap<String,CountDownLatch> LATCHES = new ConcurrentHashMap<>();
    private static long sequence;
    public static boolean parallel() { return Boolean.getBoolean("measurement.parallel"); }
    public static void await(String name) {
        mark("WAIT", name);
        try {
            if (!LATCHES.computeIfAbsent(name, n -> new CountDownLatch(1)).await(30, TimeUnit.SECONDS))
                throw new AssertionError("Timed out waiting for " + name);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
        mark("RESUMED", name);
    }
    public static void signal(String name) {
        mark("SIGNAL", name);
        LATCHES.computeIfAbsent(name, n -> new CountDownLatch(1)).countDown();
    }
    public static void completed(int result) {
        if (result != 41327) throw new AssertionError("Distinctive production method did not return its expected value");
        mark("DISTINCTIVE_RETURN", Integer.toString(result));
        signal("LATE_DONE");
    }
    public static synchronized void mark(String event, String detail) {
        try {
            Path path = Path.of(System.getProperty("measurement.events"));
            Files.createDirectories(path.getParent());
            Files.writeString(path, (++sequence) + "\t" + System.nanoTime() + "\t" + Thread.currentThread().getName()
                + "\t" + event + "\t" + detail.replace('\n', ' ') + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) { throw new AssertionError(e); }
    }
}
