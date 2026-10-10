// SPDX-License-Identifier: Apache-2.0
package example.threadapp;

/** Only exercised on JDK 21; isolated so the platform fixture also loads on JDK 17. */
public final class ThreadBuilders {
    private ThreadBuilders() { }
    public static Thread create(String shape, Runnable work) {
        return switch (shape) {
            case "virtual-static" -> Thread.startVirtualThread(work);
            case "platform-start" -> Thread.ofPlatform().name("supplied-name").start(work);
            case "virtual-start" -> Thread.ofVirtual().name("supplied-name").start(work);
            case "platform-unstarted" -> Thread.ofPlatform().name("supplied-name").unstarted(work);
            case "virtual-unstarted" -> Thread.ofVirtual().name("supplied-name").unstarted(work);
            case "platform-factory" -> Thread.ofPlatform().name("supplied-name").factory().newThread(work);
            case "virtual-factory" -> Thread.ofVirtual().name("supplied-name").factory().newThread(work);
            default -> throw new IllegalArgumentException(shape);
        };
    }
}
