// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.agent;

import example.fixture.AgentFixtureMain;
import example.fixture.AgentJunitStartupFixtureMain;
import example.fixture.AgentCoverageFragmentFixtureMain;
import example.instrumented.Calculator;
import com.sap.oss.smarttestpicker.coverage.serialization.CoverageFragmentCodec;
import com.sap.oss.smarttestpicker.junit.StpRuntimeTestExecutionListener;
import com.sap.oss.smarttestpicker.runtime.RuntimeContextRegistry;
import com.sap.oss.smarttestpicker.runtime.RuntimeHooks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.File;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor;
import org.junit.platform.launcher.TestIdentifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentShellTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void parsesValidArgumentsAndNormalizesPrefixes() {
		AgentConfiguration configuration = AgentConfiguration.parse(
				"output=result.json;includes=example/app,org.acme.;excludes=vendor.lib;runId=run-7;debug=true");
		assertEquals(Path.of("result.json"), configuration.output());
		assertEquals(List.of("example.app.", "org.acme."), configuration.includes());
		assertTrue(configuration.excludes().contains("vendor.lib."));
		assertEquals("run-7", configuration.runId());
		assertTrue(configuration.debug());
	}

	@Test
	void fragmentBindingInputsAreExplicitAndAtomic() {
		AgentConfiguration configuration = AgentConfiguration.parse(
				"fragmentOutput=fragment.json;revision=abc123;shardId=07");
		assertEquals(Path.of("fragment.json"), configuration.fragmentOutput());
		assertEquals("abc123", configuration.revision());
		assertEquals("07", configuration.shardId());
		assertThrows(IllegalArgumentException.class,
				() -> AgentConfiguration.parse("fragmentOutput=fragment.json;revision=abc123"));
	}

	@Test
	void suppliesSafeDefaults() {
		AgentConfiguration configuration = AgentConfiguration.parse(null);
		assertEquals(Path.of("stp-agent-output.json"), configuration.output());
		assertEquals(List.of(), configuration.includes());
		assertTrue(configuration.excludes().containsAll(List.of("java.", "org.springframework.",
				"org.junit.", "org.mockito.", "net.bytebuddy.", "org.jacoco.",
				"com.sap.oss.smarttestpicker.")));
		assertFalse(configuration.debug());
		assertTrue(configuration.instrumentationEnabled());
	}

	@Test
	void rejectsUnknownArgument() {
		assertThrows(IllegalArgumentException.class, () -> AgentConfiguration.parse("secret=value"));
	}

	@Test
	void rejectsDuplicateArgument() {
		assertThrows(IllegalArgumentException.class,
				() -> AgentConfiguration.parse("runId=one;runId=two"));
	}

	@Test
	void rejectsMalformedPrefixLists() {
		assertThrows(IllegalArgumentException.class, () -> AgentConfiguration.parse("includes=org.good.,"));
		assertThrows(IllegalArgumentException.class, () -> AgentConfiguration.parse("excludes=org..broken"));
	}

	@Test
	void includesConfiguredApplicationClass() {
		NoOpClassFileTransformer transformer = transformer("includes=example.fixture.");
		assertEquals(ClassDecision.INCLUDED, transformer.decision("example/fixture/AgentFixtureMain"));
		assertEquals(ClassDecision.IGNORED, transformer(null).decision("example/fixture/AgentFixtureMain"));
	}

	@Test
	void exclusionOverridesInclusion() {
		NoOpClassFileTransformer transformer = transformer("includes=example.;excludes=example.internal.");
		assertEquals(ClassDecision.EXCLUDED, transformer.decision("example/internal/Hidden"));
	}

	@Test
	void excludesJdkAndFrameworkClasses() {
		NoOpClassFileTransformer transformer = transformer(null);
		for (String name : List.of("java/lang/String", "org/junit/jupiter/api/Test",
				"org/springframework/context/ApplicationContext", "org/hibernate/Session",
				"org/mockito/Mockito", "net/bytebuddy/ByteBuddy", "org/jacoco/agent/Agent")) {
			assertEquals(ClassDecision.EXCLUDED, transformer.decision(name), name);
		}
	}

	@Test
	void transformerAlwaysReturnsNullAndCountsDecisions() throws Exception {
		AgentConfiguration configuration = AgentConfiguration.parse("includes=example.fixture.");
		AgentMetrics metrics = new AgentMetrics(11L);
		ClassFileTransformer transformer = new NoOpClassFileTransformer(configuration, metrics);
		assertNull(transformer.transform(getClass().getClassLoader(), "example/fixture/Included", null,
				getClass().getProtectionDomain(), new byte[] { 1, 2 }));
		assertNull(transformer.transform(null, "java/lang/String", null, null, new byte[] { 3 }));
		assertNull(transformer.transform(getClass().getClassLoader(), "other/Unrelated", null,
				getClass().getProtectionDomain(), new byte[] { 4 }));
		AgentMetrics.Snapshot snapshot = metrics.snapshot();
		assertEquals(3, snapshot.classesSeen());
		assertEquals(1, snapshot.classesIncluded());
		assertEquals(1, snapshot.classesExcluded());
		assertEquals(1, snapshot.classesIgnored());
		assertEquals(0, snapshot.transformationErrors());
		assertEquals(2, snapshot.classLoaderPresent());
		assertEquals(1, snapshot.classLoaderMissing());
		assertTrue(snapshot.transformerTotalNanos() >= 0);
	}

	@Test
	void shutdownJsonIsDeterministicAndConfirmsNoTransformation() {
		AgentConfiguration configuration = AgentConfiguration.parse(
				"output=result.json;includes=example.fixture.;runId=run-1;debug=false");
		AgentMetrics metrics = new AgentMetrics(42L);
		String first = AgentOutputWriter.json("run-1", "jvm-1", configuration, metrics.snapshot(),
				List.of("z-error", "a-error"));
		String second = AgentOutputWriter.json("run-1", "jvm-1", configuration, metrics.snapshot(),
				List.of("a-error", "z-error"));
		assertEquals(first, second);
		assertTrue(first.indexOf("a-error") < first.indexOf("z-error"));
		assertTrue(first.contains("\"classesTransformed\": 0"));
		assertTrue(first.contains("\"bytecodeModified\": false"));
		assertFalse(first.contains("timestamp"));
	}

	@Test
	void fragmentPublicationInvalidatesStaleTargetAndUsesAValidatedCommitPoint() throws Exception {
		Path target = temporaryDirectory.resolve("fragment.json");
		Files.writeString(target, "old-completed-fragment");
		AgentOutputWriter.invalidate(target);
		assertFalse(Files.exists(target));
		assertTrue(AgentOutputWriter.publish(target, "fresh".getBytes(StandardCharsets.UTF_8))
				|| Files.readString(target).equals("fresh"));
		assertEquals("fresh", Files.readString(target));
		try (var siblings = Files.list(temporaryDirectory)) {
			assertFalse(siblings.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
		}
	}

	@Test
	void failedFinalReplacementLeavesNoTemporaryOrOldCompletedFragment() throws Exception {
		Path target = temporaryDirectory.resolve("blocked-fragment.json");
		Files.writeString(target, "old-completed-fragment");
		AgentOutputWriter.invalidate(target);
		Files.createDirectory(target); // simulates a final-path obstruction after collector startup
		assertThrows(IOException.class, () -> AgentOutputWriter.publish(target,
				"new-completed-fragment".getBytes(StandardCharsets.UTF_8)));
		assertTrue(Files.isDirectory(target));
		try (var siblings = Files.list(temporaryDirectory)) {
			assertFalse(siblings.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
		}
	}

	@Test
	void agentJarHasRequiredManifestAndRuntimeClasses() throws IOException {
		try (JarFile jar = new JarFile(agentJar().toFile())) {
			Attributes attributes = jar.getManifest().getMainAttributes();
			assertEquals(StpAgent.class.getName(), attributes.getValue("Premain-Class"));
			assertEquals("false", attributes.getValue("Can-Redefine-Classes"));
			assertEquals("false", attributes.getValue("Can-Retransform-Classes"));
			assertTrue(jar.getEntry("com/sap/oss/smarttestpicker/runtime/model/TestIdentity.class") != null);
			assertTrue(jar.getEntry("com/sap/oss/smarttestpicker/runtime/RuntimeContextRegistry.class") != null);
			assertTrue(jar.getEntry("com/sap/oss/smarttestpicker/runtime/RuntimeObservation$PhysicalTest.class") != null);
			assertTrue(jar.getEntry("com/sap/oss/smarttestpicker/agent/StpAgent.class") != null);
			assertTrue(jar.stream().noneMatch(entry -> entry.getName().contains("/test/")));
			assertEquals(1, jar.stream().filter(entry -> entry.getName()
					.equals("META-INF/services/org.junit.platform.launcher.TestExecutionListener")).count());
		}
	}

	@Test
	void agentJarContainsNoOriginalAsmPackageOrSigningMetadata() throws IOException {
		try (JarFile jar = new JarFile(agentJar().toFile())) {
			assertTrue(jar.stream().noneMatch(entry -> entry.getName().startsWith("org/objectweb/asm/")
					|| entry.getName().matches("META-INF/.*\\.(SF|RSA|DSA)")));
		}
	}

	@Test
	void isolatedJvmStartsAndWritesOutputAndRuntimeIsVisibleToApplicationLoader() throws Exception {
		Path output = temporaryDirectory.resolve("agent-output.json");
		ProcessResult result = runFixture(output.toString());
		assertEquals(0, result.exitCode, result.output);
		assertTrue(result.output.contains("fixture-ok"));
		assertTrue(Files.isRegularFile(output));
		String json = Files.readString(output);
		assertTrue(json.contains("\"classesIncluded\": 1"));
		assertTrue(json.contains("\"classesTransformed\": 0"));
		assertTrue(json.contains("\"bytecodeModified\": false"));
	}

	@Test
	void isolatedJvmInstallsRuntimeBeforeServiceLoadedJunitListenerIsConstructed() throws Exception {
		Path output = temporaryDirectory.resolve("junit-startup-order.json");
		ProcessResult result = runJunitStartupFixture(output);
		assertEquals(0, result.exitCode, result.output);
		assertTrue(result.output.contains("premain-before-listener-ok"), result.output);
		String json = Files.readString(output);
		assertTrue(json.contains("premain-before-listener"));
		assertTrue(json.contains("\"status\":\"SUCCESSFUL\""));
	}

	@Test
	void isolatedJvmAgentAndRealJunitProduceSchemaV2FragmentWithoutJacocoConversion() throws Exception {
		Path shell = temporaryDirectory.resolve("agent-shell.json");
		Path fragment = temporaryDirectory.resolve("coverage-fragment.json");
		Path production = isolatedProductionJar();
		String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		String args = "output=" + shell + ";fragmentOutput=" + fragment
				+ ";revision=task24-revision;shardId=fixture-1;includes=example.instrumented.;runId=task24-e2e;debug=false";
		Process process = new ProcessBuilder(java, "-javaagent:" + agentJar() + "=" + args, "-cp",
				junitFixtureClasspath(), AgentCoverageFragmentFixtureMain.class.getName(), production.toString())
				.redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertEquals(0, process.waitFor(), output);
		assertTrue(output.contains("coverage-fragment-fixture-ok"), output);
		assertTrue(Files.isRegularFile(fragment), output + "\n" + (Files.exists(shell) ? Files.readString(shell) : "shell missing"));
		var decoded = new CoverageFragmentCodec().deserialize(Files.readAllBytes(fragment));
		assertEquals("task24-revision", decoded.revision().value());
		assertEquals("fixture-1", decoded.shardId().value());
		assertTrue(decoded.collectionCompleted());
		assertEquals(1, decoded.tests().size());
		assertTrue(decoded.tests().values().iterator().next().coveredMethods().contains(
				new com.sap.oss.smarttestpicker.coverage.model.MethodIdentity(
						"example.instrumented.Calculator", "add", "(II)I")));
		assertTrue(decoded.tests().values().iterator().next().coveredMethods().contains(
				new com.sap.oss.smarttestpicker.coverage.model.MethodIdentity(
						"example.instrumented.Calculator", "add", "(JJ)J")));
	}

	@Test
	void sequentialIsolatedJvmRunsDoNotLeakRegistryState() throws Exception {
		for (int index = 0; index < 2; index++) {
			Path output = temporaryDirectory.resolve("agent-output-" + index + ".json");
			ProcessResult result = runFixture(output.toString());
			assertEquals(0, result.exitCode, result.output);
			assertTrue(result.output.contains("fixture-ok"));
			assertTrue(Files.isRegularFile(output));
		}
	}

	@Test
	void agentAndNoArgumentJunitListenerResolveSameRuntimeAndShutdownCleansRegistry() throws Exception {
		Path output = temporaryDirectory.resolve("shared-runtime.json");
		AgentRuntime runtime = AgentRuntime.install(configuration(output), new AgentMetrics(1L));
		assertEquals(runtime.runtimeContext(), RuntimeContextRegistry.current().orElseThrow());

		StpRuntimeTestExecutionListener listener = new StpRuntimeTestExecutionListener();
		TestIdentifier test = leaf("shared-runtime");
		listener.executionStarted(test);
		assertTrue(runtime.runtimeContext().currentTest().isPresent());
		listener.executionFinished(test, TestExecutionResult.successful());
		runtime.writeOutput();

		assertTrue(Files.isRegularFile(output));
		assertTrue(Files.readString(output).contains("shared-runtime"));
		assertTrue(RuntimeContextRegistry.current().isEmpty());
	}

	@Test
	void failedAgentRuntimeInitializationRollsBackRegistry() {
		try (RuntimeHooks.Registration ignored = RuntimeHooks.install(value -> { })) {
			assertThrows(IllegalStateException.class,
					() -> AgentRuntime.install(configuration(temporaryDirectory.resolve("failed.json")),
							new AgentMetrics(1L)));
			assertTrue(RuntimeContextRegistry.current().isEmpty());
		}
	}

	@Test
	void premainFailureAfterRuntimeInstallationLeavesNoRegistryState() {
		Instrumentation failing = (Instrumentation) Proxy.newProxyInstance(getClass().getClassLoader(),
				new Class<?>[] { Instrumentation.class }, (proxy, method, arguments) -> {
					if (method.getName().equals("addTransformer")) {
						throw new IllegalStateException("synthetic transformer failure");
					}
					return defaultValue(method.getReturnType());
				});
		assertThrows(IllegalStateException.class, () -> StpAgent.premain(
				"output=" + temporaryDirectory.resolve("premain-failed.json") + ";debug=false", failing));
		assertTrue(RuntimeContextRegistry.current().isEmpty());
	}

	@Test
	void invalidOutputPathFailsClearlyBeforeApplicationMain() throws Exception {
		ProcessResult result = runFixture(temporaryDirectory.toString());
		assertTrue(result.exitCode != 0);
		assertTrue(result.output.contains("output must be a file"), result.output);
		assertFalse(result.output.contains("fixture-ok"));
	}

	private NoOpClassFileTransformer transformer(String arguments) {
		return new NoOpClassFileTransformer(AgentConfiguration.parse(arguments), new AgentMetrics(1L));
	}

	private static AgentConfiguration configuration(Path output) {
		return AgentConfiguration.parse("output=" + output + ";runId=registry-test;debug=false");
	}

	private static TestIdentifier leaf(String id) {
		TestDescriptor descriptor = new AbstractTestDescriptor(UniqueId.forEngine("synthetic").append("test", id), id) {
			@Override
			public Type getType() {
				return Type.TEST;
			}
		};
		return TestIdentifier.from(descriptor);
	}

	private static Object defaultValue(Class<?> type) {
		if (!type.isPrimitive()) return null;
		if (type == boolean.class) return false;
		if (type == char.class) return '\0';
		if (type == byte.class) return (byte) 0;
		if (type == short.class) return (short) 0;
		if (type == int.class) return 0;
		if (type == long.class) return 0L;
		if (type == float.class) return 0F;
		if (type == double.class) return 0D;
		return null;
	}

	private ProcessResult runFixture(String output) throws Exception {
		String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		String testClasses = Path.of(AgentFixtureMain.class.getProtectionDomain().getCodeSource().getLocation().toURI())
				.toString();
		String args = "output=" + output
				+ ";includes=example.fixture.;runId=isolated-run;debug=false;instrumentation=off";
		Process process = new ProcessBuilder(java, "-javaagent:" + agentJar() + "=" + args, "-cp", testClasses,
				AgentFixtureMain.class.getName()).redirectErrorStream(true).start();
		String processOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		return new ProcessResult(process.waitFor(), processOutput);
	}

	private ProcessResult runJunitStartupFixture(Path output) throws Exception {
		String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		String args = "output=" + output + ";includes=example.fixture.;runId=junit-startup;debug=false";
		Process process = new ProcessBuilder(java, "-javaagent:" + agentJar() + "=" + args, "-cp",
				junitStartupClasspath(), AgentJunitStartupFixtureMain.class.getName())
				.redirectErrorStream(true).start();
		String processOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		return new ProcessResult(process.waitFor(), processOutput);
	}

	private static String junitStartupClasspath() throws URISyntaxException {
		LinkedHashSet<String> entries = new LinkedHashSet<>();
		entries.add(codeSource(AgentJunitStartupFixtureMain.class));
		entries.add(agentJar().toString());
		entries.add(codeSource(org.junit.platform.launcher.TestExecutionListener.class));
		entries.add(codeSource(org.junit.platform.engine.TestExecutionResult.class));
		entries.add(codeSource(org.junit.platform.commons.JUnitException.class));
		entries.add(codeSource(org.opentest4j.TestAbortedException.class));
		return String.join(File.pathSeparator, entries);
	}

	private static String junitFixtureClasspath() throws URISyntaxException {
		LinkedHashSet<String> entries = new LinkedHashSet<>();
		entries.add(codeSource(AgentCoverageFragmentFixtureMain.class));
		entries.add(agentJar().toString());
		for (Class<?> type : List.of(org.junit.platform.launcher.TestExecutionListener.class,
				org.junit.platform.engine.TestExecutionResult.class, org.junit.platform.commons.JUnitException.class,
				org.junit.jupiter.api.Test.class, org.junit.jupiter.engine.JupiterTestEngine.class,
				org.opentest4j.TestAbortedException.class, org.apiguardian.api.API.class)) {
			entries.add(codeSource(type));
		}
		return String.join(File.pathSeparator, entries);
	}

	private Path isolatedProductionJar() throws Exception {
		Path jar = temporaryDirectory.resolve("production-fixture.jar");
		String resource = "/" + Calculator.class.getName().replace('.', '/') + ".class";
		try (var input = Calculator.class.getResourceAsStream(resource);
				JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
			output.putNextEntry(new JarEntry(resource.substring(1)));
			output.write(input.readAllBytes());
			output.closeEntry();
		}
		return jar;
	}

	private static String codeSource(Class<?> type) throws URISyntaxException {
		return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
	}

	private static Path agentJar() {
		return Path.of(System.getProperty("stp.agent.jar"));
	}

	private record ProcessResult(int exitCode, String output) {
	}
}
