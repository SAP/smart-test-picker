// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.runner.RunWith;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchUnitRunner;
import com.tngtech.archunit.lang.ArchRule;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

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
	void buildSessionId_declaredOverloads_produceDifferentIds()
	{
		String id1 = JacocoPerTestListener.buildSessionId(
				"FooTest", "overloaded", "com.example.FooTest", "org.junit.jupiter.api.TestInfo");
		String id2 = JacocoPerTestListener.buildSessionId(
				"FooTest", "overloaded", "com.example.FooTest", "org.junit.jupiter.api.TestReporter");

		assertNotEquals(id1, id2, "Declared overloads must not share a JaCoCo session");
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
		// Simulate two in-memory snapshots for parameterized invocations. They are
		// appended into one session file without consulting an agent destfile.
		System.setProperty("stp.exec.dir", tempDir.toString());
		try
		{
			JacocoPerTestListener listener = new JacocoPerTestListener();
			String sessionId = "FooTest#paramTest_abc1234";
			Path sessionFile = tempDir.resolve(
					"session_" + SessionFileNames.sanitize(sessionId) + ".exec");

			byte[] invocation1 = new byte[]{1, 2, 3, 4, 5};

			// Call saveJaCoCoSessionData via reflection (private method)
			var method = JacocoPerTestListener.class.getDeclaredMethod(
					"saveJaCoCoSessionData", String.class, byte[].class);
			method.setAccessible(true);
			method.invoke(listener, sessionId, invocation1);

			assertTrue(Files.exists(sessionFile), "Session file should be created");
			assertEquals(5, Files.size(sessionFile), "Should contain first invocation data");
			byte[] invocation2 = new byte[]{6, 7, 8};
			method.invoke(listener, sessionId, invocation2);

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

	@Test
	void beforeAllAbortWritesPositiveNonExecutionForDescendantTests(@TempDir Path tempDir) throws Exception
	{
		System.setProperty("stp.exec.dir", tempDir.toString());
		try
		{
			JacocoPerTestListener listener = new JacocoPerTestListener();
			Launcher launcher = LauncherFactory.create();
			launcher.registerTestExecutionListeners(listener);
			launcher.execute(LauncherDiscoveryRequestBuilder.request().selectors(selectClass(BeforeAllAbortFixture.class)).build());

			var markers = Files.list(tempDir)
					.filter(path -> path.getFileName().toString().endsWith(".non-executed"))
					.map(path -> {
						try { return Files.readString(path); }
						catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
					})
					.toList();
			assertEquals(2, markers.size());
			assertTrue(markers.stream().anyMatch(value -> value.contains("methodName=testOne\n")));
			assertTrue(markers.stream().anyMatch(value -> value.contains("methodName=testTwo\n")));
		}
		finally
		{
			System.clearProperty("stp.exec.dir");
		}
	}

	@Test
	void disabledParameterizedContainerWritesPositiveNonExecutionForDeclaredMethod(@TempDir Path tempDir) throws Exception
	{
		System.setProperty("stp.exec.dir", tempDir.toString());
		try
		{
			JacocoPerTestListener listener = new JacocoPerTestListener();
			Launcher launcher = LauncherFactory.create();
			launcher.registerTestExecutionListeners(listener);
			launcher.execute(LauncherDiscoveryRequestBuilder.request()
					.selectors(selectClass(DisabledParameterizedFixture.class)).build());

			var markers = Files.list(tempDir)
					.filter(path -> path.getFileName().toString().endsWith(".non-executed"))
					.toList();
			assertEquals(1, markers.size());
			String marker = Files.readString(markers.get(0));
			assertTrue(marker.contains("methodName=disabled\n"));
			assertTrue(marker.contains("methodParameterTypes=java.lang.String\n"));
		}
		finally
		{
			System.clearProperty("stp.exec.dir");
		}
	}

	@Test
	void abortedTestWritesNonExecutionWithoutExecutedIdentity(@TempDir Path tempDir) throws Exception
	{
		System.setProperty("stp.exec.dir", tempDir.toString());
		try
		{
			JacocoPerTestListener listener = new JacocoPerTestListener();
			Launcher launcher = LauncherFactory.create();
			launcher.registerTestExecutionListeners(listener);
			launcher.execute(LauncherDiscoveryRequestBuilder.request()
					.selectors(selectClass(AbortedTestFixture.class)).build());

			var files = Files.list(tempDir).map(path -> path.getFileName().toString()).toList();
			assertTrue(files.stream().anyMatch(name -> name.endsWith(".non-executed")));
			assertFalse(files.stream().anyMatch(name -> name.endsWith(".identity")),
					"an assumption-aborted test must not be reported as executed");
			assertFalse(files.stream().anyMatch(name -> name.endsWith(".exec") && !name.startsWith("session_setup_")),
					"an assumption-aborted test must not leave orphan coverage data");
		}
		finally
		{
			System.clearProperty("stp.exec.dir");
		}
	}

	@Test
	void vintageCustomRunnerClassSourceWritesAuthoritativeIdentity(@TempDir Path tempDir) throws Exception
	{
		System.setProperty("stp.exec.dir", tempDir.toString());
		try
		{
			JacocoPerTestListener listener = new JacocoPerTestListener();
			AtomicReference<Object> observedSource = new AtomicReference<>();
			Launcher launcher = LauncherFactory.create();
			launcher.registerTestExecutionListeners(listener, new TestExecutionListener()
			{
				@Override public void executionStarted(TestIdentifier identifier)
				{
					if (identifier.isTest()) observedSource.set(identifier.getSource().orElse(null));
				}
			});
			launcher.execute(LauncherDiscoveryRequestBuilder.request()
					.selectors(selectClass(VintageCustomRunnerFixture.class)).build());

			assertInstanceOf(ClassSource.class, observedSource.get(),
					"fixture must exercise the Vintage ClassSource fallback rather than MethodSource");
			var identities = Files.list(tempDir)
					.filter(path -> path.getFileName().toString().endsWith(".identity"))
					.map(path -> {
						try { return Files.readString(path); }
						catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
					})
					.toList();
			assertEquals(6, identities.size());
			assertEquals(Set.of("EACH_IGNORED_TEST_HAS_REASON_TO_IGNORE",
					"EACH_IGNORED_TEST_METHOD_HAS_REASON_TO_IGNORE", "EACH_REST_API_TEST_HAS_CODE_OWNER",
					"EACH_TEST_CONTEXT_HAS_CODE_OWNER", "EACH_TEST_HAS_JUNIT_RULE_TO_VALIDATE_AE_LIVENESS",
					"TEST_EXECUTION_ORDER_SET_CORRECTLY_FOR_EACH_TEST"), identities.stream().map(value -> value.lines()
					.filter(line -> line.startsWith("methodName=")).findFirst().orElseThrow().substring("methodName=".length()))
					.collect(java.util.stream.Collectors.toSet()));
			assertTrue(identities.stream().allMatch(value ->
					value.contains("className=" + VintageCustomRunnerFixture.class.getName() + "\n")
							&& value.contains("methodParameterTypes=\n") && value.contains("outcome=PASS\n")));
		}
		finally
		{
			System.clearProperty("stp.exec.dir");
		}
	}

	static class BeforeAllAbortFixture
	{
		@BeforeAll static void setup() { Assumptions.assumeTrue(false); }
		@Test void testOne() { }
		@Test void testTwo() { }
	}

	static class DisabledParameterizedFixture
	{
		@Disabled
		@ParameterizedTest
		@ValueSource(strings = "value")
		void disabled(String value) { }
	}

	static class AbortedTestFixture
	{
		@Test void aborted() { Assumptions.assumeTrue(false); }
	}

	@RunWith(ArchUnitRunner.class)
	@AnalyzeClasses(packagesOf = VintageCustomRunnerFixture.class)
	public static class VintageCustomRunnerFixture
	{
		private static ArchRule passingRule() { return classes().should().haveSimpleNameNotStartingWith("DefinitelyNoClass"); }

		@ArchTest public static final ArchRule EACH_IGNORED_TEST_HAS_REASON_TO_IGNORE = passingRule();
		@ArchTest public static final ArchRule EACH_IGNORED_TEST_METHOD_HAS_REASON_TO_IGNORE = passingRule();
		@ArchTest public static final ArchRule EACH_REST_API_TEST_HAS_CODE_OWNER = passingRule();
		@ArchTest public static final ArchRule EACH_TEST_CONTEXT_HAS_CODE_OWNER = passingRule();
		@ArchTest public static final ArchRule EACH_TEST_HAS_JUNIT_RULE_TO_VALIDATE_AE_LIVENESS = passingRule();
		@ArchTest public static final ArchRule TEST_EXECUTION_ORDER_SET_CORRECTLY_FOR_EACH_TEST = passingRule();
	}
}
