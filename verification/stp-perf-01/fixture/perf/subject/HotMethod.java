package perf.subject;

public final class HotMethod {
    private HotMethod() { }

    public static long touch(long value) {
        return Long.rotateLeft(value * 6364136223846793005L + 1442695040888963407L, 17);
    }
}
