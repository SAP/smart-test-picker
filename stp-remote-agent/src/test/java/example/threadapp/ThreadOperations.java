// SPDX-License-Identifier: Apache-2.0
package example.threadapp;

/** Included application call sites, with no explicit STP helper calls. */
public final class ThreadOperations {
    private ThreadOperations() { }
    public static Thread create(String shape, Runnable work) {
        ThreadGroup group = new ThreadGroup("supplied-group");
        return switch (shape) {
            case "plain" -> new Thread(work);
            case "named" -> new Thread(work, "supplied-name");
            case "group" -> new Thread(group, work);
            case "group-name" -> new Thread(group, work, "supplied-name");
            case "stack" -> new Thread(group, work, "supplied-name", 1024 * 1024L);
            case "inherit-false" -> new Thread(group, work, "supplied-name", 1024 * 1024L, false);
            case "override" -> new OverrideThread(work, false);
            case "override-target" -> new OverrideThread(work, true);
            case "inherited" -> new InheritedThread(work);
            case "delegating" -> new DelegatingThread(work);
            case "outside-run" -> new example.threadsupport.OutsideThreads(work);
            case "outside-construction" -> example.threadsupport.OutsideThreads.create(work);
            default -> ThreadBuilders.create(shape, work);
        };
    }
    public static void start(Thread thread) { thread.start(); }
    public static void concreteStart(OverrideThread thread) { thread.start(); }
    public static void inheritedStart(InheritedThread thread) { thread.start(); }
    public static void direct(Thread thread) { thread.run(); }
    public static void nested(Runnable task) throws InterruptedException {
        Thread child = new Thread(task, "nested-child");
        child.start(); child.join(10000);
        if (child.isAlive()) throw new AssertionError("nested child did not finish");
    }
    public static void a() { }
    public static void b() { }
    public static void anonymous() { }
    public static void afterRun() { }
    public static void ignoredTarget() { throw new AssertionError("overridden run must not execute supplied target"); }

    public static class OverrideThread extends Thread {
        private final Runnable body;
        public OverrideThread(Runnable body, boolean withTarget) {
            super(withTarget ? ThreadOperations::ignoredTarget : null, "supplied-name");
            this.body = body;
        }
        @Override public void run() { body.run(); }
    }
    public static final class InheritedThread extends OverrideThread {
        public InheritedThread(Runnable work) { super(work, false); }
    }
    public static final class DelegatingThread extends Thread {
        public DelegatingThread(Runnable work) { super(work, "supplied-name"); }
        @Override public void run() { super.run(); afterRun(); }
    }
}
