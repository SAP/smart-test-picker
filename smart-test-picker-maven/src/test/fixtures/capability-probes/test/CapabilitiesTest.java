// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CapabilitiesTest {
    @BeforeAll static void setup() { assertEquals(7, SetupOnly.setupOnly()); }
    @Test void probes() {
        assertEquals("x", new OverloadedService().value(" x "));
        // The exception must come from inside the production method, not from an observer.
        NullPointerException failure = assertThrows(NullPointerException.class,
                () -> new ImplicitException().length(null));
        assertEquals("example.ImplicitException", failure.getStackTrace()[0].getClassName());
        assertEquals("length", failure.getStackTrace()[0].getMethodName());
    }
}
