package perf;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import perf.subject.HotMethod;

public class BenchTest {
    private static volatile long observed;

    private static long loop(long calls, long seed) {
        long value = seed;
        for (long i = 0; i < calls; i++) value = HotMethod.touch(value);
        return value;
    }

    @Test
    void measure() throws Exception {
        long calls = Long.getLong("bench.calls");
        int threads = Integer.getInteger("bench.threads");
        boolean contention = Boolean.getBoolean("bench.contention");
        observed = loop(1_000_000, 123);
        long started;
        long nanos;
        long checksum;
        if (!contention) {
            started = System.nanoTime();
            checksum = loop(calls, 456);
            nanos = System.nanoTime() - started;
        } else {
            if (calls % threads != 0) throw new AssertionError("Unequal workload");
            var ready = new CountDownLatch(threads);
            var start = new CountDownLatch(1);
            var failures = new AtomicReference<Throwable>();
            var workers = new Thread[threads];
            var results = new long[threads];
            for (int i = 0; i < threads; i++) {
                final int index = i;
                Runnable work = () -> {
                    ready.countDown();
                    try {
                        start.await();
                        results[index] = loop(calls / threads, 456 + index);
                    } catch (Throwable failure) {
                        failures.compareAndSet(null, failure);
                    }
                };
                if (Boolean.getBoolean("bench.agent")) {
                    work = (Runnable) Class.forName("com.sap.oss.smarttestpicker.runtime.RuntimeHooks")
                            .getMethod("wrap", Runnable.class).invoke(null, work);
                }
                workers[i] = new Thread(work, "bench-worker-" + i);
                workers[i].start();
            }
            ready.await();
            started = System.nanoTime();
            start.countDown();
            for (Thread worker : workers) worker.join();
            nanos = System.nanoTime() - started;
            if (failures.get() != null) throw new AssertionError("Worker failed", failures.get());
            checksum = 0;
            for (long value : results) checksum ^= value;
        }
        observed = checksum;
        System.out.printf("BENCH {\"calls\":%d,\"threads\":%d,\"contention\":%s,\"nanos\":%d,\"checksum\":%d}%n",
                calls, threads, contention, nanos, observed);
    }
}
