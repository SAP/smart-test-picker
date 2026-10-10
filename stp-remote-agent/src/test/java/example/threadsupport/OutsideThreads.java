// SPDX-License-Identifier: Apache-2.0
package example.threadsupport;

/** Deliberately outside includes: executable evidence of uninstrumented boundaries. */
public final class OutsideThreads extends Thread {
    private final Runnable body;
    public OutsideThreads(Runnable body) { this.body = body; }
    @Override public void run() { body.run(); }
    public static Thread create(Runnable body) { return new Thread(body); }
    public static void start(Thread thread) { thread.start(); }
}
