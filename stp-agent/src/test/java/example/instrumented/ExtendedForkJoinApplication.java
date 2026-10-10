// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.instrumented;

public final class ExtendedForkJoinApplication {
	public static void adapted(int value) {}
	public static int callable(int value) { return value; }
	public static void countedA(int value) {}
	public static void countedB(String value) {}
	public static void completion(int value) {}
	public static void streamA(int value) {}
	public static void streamB(String value) {}
	public static void rejected(int value) {}
	public static void failure(int value) {}
	public static void unowned(int value) {}
	public static void late(int value) {}
	public static void setup(int value) {}
	public static void workerRestored(int value) {}
}
