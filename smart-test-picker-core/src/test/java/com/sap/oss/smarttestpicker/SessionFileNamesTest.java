// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SessionFileNamesTest {
    @ParameterizedTest
    @ValueSource(strings = {"~", "~0", "~00", "~000", "~zzzz", "~12g4", "~+123", "~-123", "~１２３４"})
    void malformedEscapesAreRejectedWithoutInventingAnIdentity(String escape) {
        String input = "~stp1~Spec#read" + escape;
        var failure = assertThrowsExactly(IllegalArgumentException.class, () -> SessionFileNames.unsanitize(input));
        assertTrue(failure.getMessage().contains("Malformed encoded session filename"));
        assertTrue(failure.getMessage().contains(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Spec#read/write", "Spec#read\\write", "Spec#read:write", "Spec#read\nwrite",
            "Spec#read\u0000write", "Spec#read<>\"|?*write", "Spec#~002f/Upper"})
    void unsafeNamesRemainDirectChildrenAndRoundTrip(String session, @TempDir Path directory) throws Exception {
        String encoded = SessionFileNames.sanitize(session);
        Path output = directory.resolve("session_" + encoded + ".exec");
        assertEquals(directory, output.getParent());
        Files.write(output, new byte[0]);
        assertEquals(session, SessionFileNames.unsanitize(encoded));
    }

    @Test void safeNamesKeepTheirLegacySpelling() {
        assertEquals("MyTest#test~Boolean", SessionFileNames.sanitize("MyTest#testBoolean"));
        assertEquals("Spec#free text.[x];,~X", SessionFileNames.sanitize("Spec#free text.[x];,X"));
        assertEquals("setup_abc", SessionFileNames.sanitize("setup_abc"));
        assertEquals("Spec#read/write", SessionFileNames.unsanitize("~stp1~~0053pec#read~002fwrite"));
        assertEquals("Spec#\u00af", SessionFileNames.unsanitize("~stp1~Spec#~00Af"));
        assertNotEquals(SessionFileNames.sanitize("Spec#read/A"), SessionFileNames.sanitize("Spec#read/a"));
    }
}
