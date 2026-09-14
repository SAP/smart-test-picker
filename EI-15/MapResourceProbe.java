import java.nio.file.Files;
import java.nio.file.Path;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableCoverageMapCodec;

/** Bounded EI-15 probe for one retained executable map; not a benchmark harness. */
public final class MapResourceProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("usage: MapResourceProbe MAP.json");
        byte[] bytes = Files.readAllBytes(Path.of(args[0]));
        Runtime runtime = Runtime.getRuntime();
        runtime.gc();
        long before = runtime.totalMemory() - runtime.freeMemory();
        long started = System.nanoTime();
        var map = new ExecutableCoverageMapCodec().deserialize(bytes);
        long elapsed = System.nanoTime() - started;
        long after = runtime.totalMemory() - runtime.freeMemory();
        System.out.printf("bytes=%d mapped=%d expected=%d elapsedMs=%.3f heapDeltaBytes=%d maxHeapBytes=%d%n",
                bytes.length, map.tests().size(), map.completeness().expectedTests().size(),
                elapsed / 1_000_000.0, Math.max(0, after - before), runtime.maxMemory());
    }
}
