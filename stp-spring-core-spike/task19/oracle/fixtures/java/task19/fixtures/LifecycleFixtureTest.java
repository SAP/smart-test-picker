// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package task19.fixtures;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class LifecycleFixtureTest {
    private static Thread late;
    @BeforeAll static void beforeAll() { LifecycleTargets.beforeAllTarget(); }
    @BeforeEach void beforeEach() { LifecycleTargets.beforeEachTarget(); }
    @Test void lifecycleAndLate() {
        LifecycleTargets.testTarget();
        late = new Thread(() -> {
            try { Thread.sleep(500); } catch (InterruptedException failure) { throw new RuntimeException(failure); }
            LifecycleTargets.lateTarget();
        }, "oracle-late-thread");
        late.start();
    }
    @AfterEach void afterEach() { LifecycleTargets.afterEachTarget(); }
    @AfterAll static void afterAll() throws Exception { late.join(); LifecycleTargets.afterAllTarget(); }
}

final class LifecycleTargets {
    static void beforeAllTarget() { }
    static void beforeEachTarget() { }
    static void testTarget() { }
    static void afterEachTarget() { }
    static void lateTarget() { }
    static void afterAllTarget() { }
}
