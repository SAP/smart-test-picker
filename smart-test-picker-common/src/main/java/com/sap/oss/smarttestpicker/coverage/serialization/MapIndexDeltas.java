// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.serialization;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import com.google.gson.stream.JsonWriter;

/** Sorted non-negative table indices; the first delta is an absolute index. */
final class MapIndexDeltas
{
    private MapIndexDeltas() {}

    static <T> void write(JsonWriter writer, Collection<T> values, Map<T, Integer> ids) throws IOException
    {
        int[] indices = values.stream().mapToInt(ids::get).sorted().toArray();
        writer.beginArray();
        int previous = 0;
        for (int index : indices)
        {
            writer.value(index - previous);
            previous = index;
        }
        writer.endArray();
    }

    static int resolve(int delta, int previous, boolean first, int size)
    {
        long index = (long) previous + delta;
        if (delta < 0 || (!first && delta == 0) || index >= size)
        {
            throw new IllegalArgumentException("Invalid, duplicate or out-of-range map index delta: " + delta);
        }
        return (int) index;
    }
}
