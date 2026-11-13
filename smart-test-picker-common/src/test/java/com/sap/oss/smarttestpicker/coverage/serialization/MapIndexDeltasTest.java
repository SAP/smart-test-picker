// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.serialization;

import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import com.google.gson.stream.JsonWriter;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MapIndexDeltasTest
{
    @Test void sortedDeltaRoundTripIncludesZeroAndGaps() throws Exception
    {
        StringWriter output = new StringWriter();
        JsonWriter writer = new JsonWriter(output);
        MapIndexDeltas.write(writer, List.of("c", "a", "b"), Map.of("a", 0, "b", 3, "c", 9));
        writer.flush();
        assertEquals("[0,3,6]", output.toString());
        assertEquals(0, MapIndexDeltas.resolve(0, 0, true, 10));
        assertEquals(3, MapIndexDeltas.resolve(3, 0, false, 10));
        assertEquals(9, MapIndexDeltas.resolve(6, 3, false, 10));
    }

    @Test void rejectsDuplicateNegativeOverflowAndOutOfRangeIndices()
    {
        assertThrows(IllegalArgumentException.class, () -> MapIndexDeltas.resolve(0, 1, false, 10));
        assertThrows(IllegalArgumentException.class, () -> MapIndexDeltas.resolve(-1, 0, true, 10));
        assertThrows(IllegalArgumentException.class, () -> MapIndexDeltas.resolve(10, 0, true, 10));
        assertThrows(IllegalArgumentException.class, () -> MapIndexDeltas.resolve(Integer.MAX_VALUE, 1, false, Integer.MAX_VALUE));
    }
}
