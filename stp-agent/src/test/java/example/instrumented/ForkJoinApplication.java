// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.instrumented;

public final class ForkJoinApplication {
	public static void fork(int value) { }
	public static void direct(int value) { }
	public static void execute(int value) { }
	public static void submit(int value) { }
	public static int invoke(int value) { return value; }
	public static void recursive(int value) { }
	public static void reuse(int value) { }
	public static void parallelA(int value) { }
	public static void parallelB(String value) { }
	public static void late(int value) { }
	public static void setup(int value) { }
	public static void failure(int value) { }
	public static void unowned(int value) { }
	public static void cancelled(int value) { }
	public static void ambiguous(int value) { }
	public static void custom(int value) { }
}
