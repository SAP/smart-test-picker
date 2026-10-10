// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.instrumented;

public final class BulkApplication {
    public static int first(int value) { return value; }
    public static String second(String value) { return value; }
    public static void failed(int value) { }
    public static void competitor(String value) { }
    public static void winner(int value) { }
    public static void shared(int value) { }
    public static void onlyA(int value) { }
    public static void onlyB(String value) { }
    public static void nested(int value) { }
    public static void setup(int value) { }
    public static void late(int value) { }
    public static void unowned(int value) { }
    public static void never(int value) { throw new AssertionError("cancelled task executed"); }
}
