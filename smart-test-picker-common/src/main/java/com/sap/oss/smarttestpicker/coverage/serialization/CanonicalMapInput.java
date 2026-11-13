// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.serialization;

import java.io.IOException;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

/** Re-emits consumed tokens to the canonical digest stream without retaining a JSON tree. */
final class CanonicalMapInput
{
    final JsonReader reader;
    private final JsonWriter canonical;
    CanonicalMapInput(JsonReader reader, JsonWriter canonical)
    {
        this.reader = reader;
        this.canonical = canonical;
    }
    void object() throws IOException { reader.beginObject(); canonical.beginObject(); }
    void endObject() throws IOException { reader.endObject(); canonical.endObject(); }
    void array() throws IOException { reader.beginArray(); canonical.beginArray(); }
    void endArray() throws IOException { reader.endArray(); canonical.endArray(); }
    boolean more() throws IOException { return reader.hasNext(); }
    void name(String expected) throws IOException
    {
        String actual = reader.nextName();
        if (!expected.equals(actual))
        {
            throw new IllegalArgumentException("Expected map field " + expected + ", got " + actual);
        }
        canonical.name(actual);
    }
    String name() throws IOException
    {
        String name = reader.nextName(); canonical.name(name); return name;
    }
    String string() throws IOException
    {
        if (reader.peek() != JsonToken.STRING) { throw new IllegalArgumentException("Expected a map string"); }
        String value = reader.nextString(); canonical.value(value); return value;
    }
    String nullableString() throws IOException { return nil() ? null : string(); }
    boolean nil() throws IOException
    {
        if (reader.peek() != JsonToken.NULL) { return false; }
        reader.nextNull(); canonical.nullValue(); return true;
    }
    long number() throws IOException
    {
        if (reader.peek() != JsonToken.NUMBER) { throw new IllegalArgumentException("Expected a map integer"); }
        String token = reader.nextString();
        long value = Long.parseLong(token);
        if (!Long.toString(value).equals(token)) { throw new IllegalArgumentException("Non-canonical map integer: " + token); }
        canonical.value(value); return value;
    }
    int integer() throws IOException { return Math.toIntExact(number()); }
    void finishCanonicalBody() throws IOException { canonical.endObject(); canonical.flush(); }
}
