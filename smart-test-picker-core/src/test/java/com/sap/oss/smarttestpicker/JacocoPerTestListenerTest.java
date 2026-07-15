// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

	@Test
	void saveSessionData_appendsOnRepeatedInvocation(@TempDir Path tempDir) throws Exception
	{
		// Simulate parameterized test: two invocations produce two test.exec files
		// that should be appended into one session file (not overwritten).
		System.setProperty("stp.exec.dir", tempDir.toString());
		try
		{
			JacocoPerTestListener listener = new JacocoPerTestListener();
			String sessionId = "FooTest#paramTest_abc1234";
			Path sessionFile = tempDir.resolve(
					"session_" + SessionFileNames.sanitize(sessionId) + ".exec");

			// First invocation: create test.exec with some bytes
			byte[] invocation1 = new byte[]{1, 2, 3, 4, 5};
			Files.write(tempDir.resolve("test.exec"), invocation1);

			// Call saveJaCoCoSessionData via reflection (private method)
			var method = JacocoPerTestListener.class.getDeclaredMethod(
					"saveJaCoCoSessionData", String.class);
			method.setAccessible(true);
			method.invoke(listener, sessionId);

			assertTrue(Files.exists(sessionFile), "Session file should be created");
			assertEquals(5, Files.size(sessionFile), "Should contain first invocation data");
			assertFalse(Files.exists(tempDir.resolve("test.exec")), "test.exec should be deleted");

			// Second invocation: create another test.exec with different bytes
			byte[] invocation2 = new byte[]{6, 7, 8};
			Files.write(tempDir.resolve("test.exec"), invocation2);

			method.invoke(listener, sessionId);

			// Session file should now contain BOTH invocations appended
			assertEquals(8, Files.size(sessionFile),
					"Session file should contain appended data from both invocations (5 + 3 = 8 bytes)");

			byte[] combined = Files.readAllBytes(sessionFile);
			assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, combined,
					"Content should be first invocation followed by second invocation");
		}
		finally
		{
			System.clearProperty("stp.exec.dir");
		}
	}
}
