// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;


class JacocoPerTestListenerTest
{

	@Test
	void buildSessionId_includesHashSuffix()
	{
		String sessionId = JacocoPerTestListener.buildSessionId(
				"FooTest", "testSomething", "com.example.module1.FooTest");

		assertTrue(sessionId.matches("FooTest#testSomething_[0-9a-f]{7}"),
				"Expected format SimpleClass#method_hash, got: " + sessionId);
	}

	@Test
	void buildSessionId_differentPackages_produceDifferentIds()
	{
		String id1 = JacocoPerTestListener.buildSessionId(
				"FooTest", "testSomething", "com.example.module1.FooTest");
		String id2 = JacocoPerTestListener.buildSessionId(
				"FooTest", "testSomething", "com.example.module2.FooTest");

		assertNotEquals(id1, id2,
				"Same simple name in different packages must produce different session IDs");
	}

	@Test
	void buildSessionId_sameInputs_stableResult()
	{
		String id1 = JacocoPerTestListener.buildSessionId(
				"FooTest", "testBar", "com.example.FooTest");
		String id2 = JacocoPerTestListener.buildSessionId(
				"FooTest", "testBar", "com.example.FooTest");

		assertEquals(id1, id2, "Same test must always produce the same session ID");
	}

	@Test
	void buildSessionId_differentMethods_produceDifferentIds()
	{
		String id1 = JacocoPerTestListener.buildSessionId(
				"FooTest", "testAlpha", "com.example.FooTest");
		String id2 = JacocoPerTestListener.buildSessionId(
				"FooTest", "testBeta", "com.example.FooTest");

		assertNotEquals(id1, id2);
	}

	@Test
	void buildSessionId_preservesReadablePart()
	{
		String sessionId = JacocoPerTestListener.buildSessionId(
				"TypeDescriptorTests", "upCastNotSuper",
				"org.springframework.core.convert.TypeDescriptorTests");

		assertTrue(sessionId.startsWith("TypeDescriptorTests#upCastNotSuper_"),
				"Readable part must be preserved, got: " + sessionId);
	}

	@Test
	void buildSessionId_hashIsSevenChars()
	{
		String sessionId = JacocoPerTestListener.buildSessionId(
				"MyTest", "myMethod", "com.example.MyTest");

		String hash = sessionId.substring(sessionId.lastIndexOf('_') + 1);
		assertEquals(7, hash.length(), "Hash suffix must be 7 hex chars");
		assertTrue(hash.matches("[0-9a-f]{7}"), "Hash must be hex, got: " + hash);
	}
}
