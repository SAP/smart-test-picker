// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.mapper;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

class SessionFileNameDecodingTest {
    @ParameterizedTest
    @ValueSource(strings = {"~", "~0", "~00", "~000", "~zzzz", "~12g4", "~+123", "~-123", "~１２３４"})
    void malformedEscapesAreRejectedWithoutInventingAnIdentity(String escape) {
        String input = "~stp1~Spec#read" + escape;
        var failure = assertThrowsExactly(IllegalArgumentException.class,
                () -> CoverageMapperJaxb.unsanitizeSessionFileName(input));
        assertTrue(failure.getMessage().contains("Malformed encoded session filename"));
        assertTrue(failure.getMessage().contains(input));
    }

    @Test void corruptReportFilenameCannotBecomeAnInventedCoverageOwner(@TempDir Path directory) throws Exception {
        String encoded = "~stp1~Spec#read~zzzz";
        try (var input = getClass().getResourceAsStream("/session_MyTest#testSomething.xml")) {
            Files.copy(input, directory.resolve("session_" + encoded + ".xml"));
        }
        var mapper = new CoverageMapperJaxb(directory.toFile());
        var failure = assertThrowsExactly(IllegalArgumentException.class, mapper::generateTestCoverageMapping);
        assertTrue(failure.getMessage().contains(encoded));
    }

    @Test void discoversEncodedXmlWithoutRemovingExtensionTextFromTheMethod(@TempDir Path directory) throws Exception {
        try (var input = getClass().getResourceAsStream("/session_MyTest#testSomething.xml")) {
            Files.copy(input, directory.resolve("session_~stp1~~0053pec#reads.xml~002fvalue.xml"));
        }
        var mapped = new CoverageMapperJaxb(directory.toFile()).generateTestCoverageMapping();
        assertEquals(Set.of("Spec#reads.xml/value"), mapped.keySet());
    }

    @Test void readsVersionedAndLegacyCollectorNames() {
        assertEquals("Spec#read/write", CoverageMapperJaxb.unsanitizeSessionFileName(
                "~stp1~~0053pec#read~002fwrite"));
        assertEquals("Spec#read~002f/Upper", CoverageMapperJaxb.unsanitizeSessionFileName(
                "~stp1~~0053pec#read~007e002f~002f~0055pper"));
        assertEquals("MyTest#testBoolean", CoverageMapperJaxb.unsanitizeSessionFileName("MyTest#test~Boolean"));
        assertEquals("Spec#\u00af", CoverageMapperJaxb.unsanitizeSessionFileName("~stp1~Spec#~00Af"));
    }
}
