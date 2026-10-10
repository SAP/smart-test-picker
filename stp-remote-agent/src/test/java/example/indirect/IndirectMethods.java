// SPDX-License-Identifier: Apache-2.0
package example.indirect;

/** Only these application methods and IncludedCalls are recorded. */
public final class IndirectMethods {
    public static void a() { }
    public static void b() { }
    public static void after() { }
    public static Thread thread(Runnable task) { return new Thread(task, "indirect-thread"); }
}
