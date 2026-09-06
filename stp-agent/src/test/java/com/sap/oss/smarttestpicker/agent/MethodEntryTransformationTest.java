// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.agent;

import com.sap.oss.smarttestpicker.runtime.RuntimeHooks;
import com.sap.oss.smarttestpicker.runtime.model.MethodIdentity;
import example.instrumented.Calculator;
import example.instrumented.ApplicationMethodKinds;
import example.instrumented.InstrumentedFixtureMain;
import example.fixture.ExecutorPropagationFixtureMain;
import example.fixture.ExecutorOverloadVerificationFixtureMain;
import example.fixture.ThreadBoundaryFixtureMain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MethodEntryTransformationTest {
	@Test
	void excludesMavenAndGradleTestOutputLocations() throws Exception {
		for (String location : List.of("file:/tmp/project/target/test-classes/",
				"file:/tmp/project/build/classes/java/test/", "file:/tmp/project/build/classes/kotlin/test/",
				"file:/tmp/project/build/classes/java/testFixtures/",
				"file:/tmp/project/build/classes/kotlin/testFixtures/")) {
			ProtectionDomain domain = new ProtectionDomain(new CodeSource(new URL(location), (java.security.cert.Certificate[]) null), null);
			assertTrue(MethodEntryClassFileTransformer.isTestClassLocation(domain), location);
		}
	}
	@TempDir
	Path temporaryDirectory;

	@Test
	void buildsCanonicalMethodKeyAndDistinguishesOverloads() {
		assertEquals("example.Calculator#add(II)I", MethodKeys.canonical("example.Calculator", "add", "(II)I"));
		assertNotEquals(MethodKeys.canonical("example.Calculator", "add", "(II)I"),
				MethodKeys.canonical("example.Calculator", "add", "(JJ)J"));
	}

	@Test
	void methodIdsAreDeterministicAndOverloadSensitive() {
		MethodIdHasher hasher = new Fnv1a64MethodIdHasher();
		String intAdd = "example.instrumented.Calculator#add(II)I";
		assertEquals(hasher.hash(intAdd), hasher.hash(intAdd));
		assertEquals("13461325983995178623", Long.toUnsignedString(hasher.hash(intAdd)));
		assertNotEquals(hasher.hash(intAdd), hasher.hash("example.instrumented.Calculator#add(JJ)J"));
	}

	@Test
	void catalogRetainsBothCanonicalKeysOnCollision() {
		AgentMetrics metrics = new AgentMetrics(1L);
		java.util.ArrayList<String> errors = new java.util.ArrayList<>();
		MethodCatalog catalog = new MethodCatalog(ignored -> 7L, metrics, errors::add);
		catalog.register(new MethodIdentity("example.A", "one", "()V"));
		catalog.register(new MethodIdentity("example.B", "two", "()V"));
		assertEquals(2, catalog.resolve(7L).size());
		assertEquals(1, metrics.snapshot().methodIdCollisions());
		assertEquals(List.of("method-id-collision:7"), errors);
	}

	@Test
	void transformsOrdinaryStaticPrivateAndOverloadedMethodsAndExecutesHooks() throws Exception {
		Transformation transformation = transformCalculator();
		assertTrue(transformation.bytes.length > 0);
		LongAdder hits = new LongAdder();
		try (RuntimeHooks.Registration ignored = RuntimeHooks.install(id -> hits.increment())) {
			Class<?> type = new DefiningLoader().define(Calculator.class.getName(), transformation.bytes);
			Object calculator = type.getConstructor().newInstance();
			assertEquals(5, type.getMethod("add", int.class, int.class).invoke(calculator, 2, 3));
			assertEquals(11L, type.getMethod("add", long.class, long.class).invoke(calculator, 4L, 7L));
			assertEquals(12, type.getMethod("scale", int.class, int.class).invoke(null, 3, 4));
		}
		// constructor + int add + private adjust + long add + static scale
		assertEquals(5, hits.sum());
		assertTrue(transformation.catalog.snapshot().values().stream().flatMap(List::stream)
				.anyMatch(method -> method.methodName().equals("adjust")));
	}

	@Test
	void transformsConstructorsStaticInitializersLambdaBodiesAndCompilerGeneratedRecordMethods() throws Exception {
		Transformation transformation = transform(ApplicationMethodKinds.class);
		String diagnostics = verificationDiagnostics(transformation.bytes);
		assertEquals("", diagnostics);
		List<String> keys = transformation.catalog.snapshot().values().stream().flatMap(List::stream)
				.map(MethodIdentity::canonicalKey).toList();
		assertTrue(keys.stream().anyMatch(key -> key.contains("#<init>(I)V")));
		assertTrue(keys.stream().anyMatch(key -> key.contains("#<clinit>()V")));
		assertTrue(keys.stream().anyMatch(key -> key.contains("#lambda$lambda$0(I)I")));
		Transformation recordTransformation = transform(ApplicationMethodKinds.SampleRecord.class);
		assertEquals("", verificationDiagnostics(recordTransformation.bytes));
		assertTrue(recordTransformation.catalog.snapshot().values().stream().flatMap(List::stream)
				.map(MethodIdentity::canonicalKey)
				.anyMatch(key -> key.contains("$SampleRecord#equals(Ljava/lang/Object;)Z")));
	}

	@Test
	void transformsDefaultAndPrivateInterfaceMethods() throws Exception {
		Transformation transformation = transform(ApplicationMethodKinds.Defaults.class);
		assertEquals("", verificationDiagnostics(transformation.bytes));
		List<String> keys = transformation.catalog.snapshot().values().stream().flatMap(List::stream)
				.map(MethodIdentity::canonicalKey).toList();
		assertTrue(keys.stream().anyMatch(key -> key.contains("#publicDefault(I)I")));
		assertTrue(keys.stream().anyMatch(key -> key.contains("#privateDefault(I)I")));
		assertEquals(2, transformation.metrics.snapshot().methodsInstrumented());
		LongAdder hits = new LongAdder();
		try (RuntimeHooks.Registration ignored = RuntimeHooks.install(id -> hits.increment())) {
			Class<?> type = new DefiningLoader().define(ApplicationMethodKinds.Defaults.class.getName(),
					transformation.bytes);
			Object proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type },
					(instance, method, arguments) -> InvocationHandler.invokeDefault(instance, method, arguments));
			assertEquals(7, type.getMethod("publicDefault", int.class).invoke(proxy, 3));
		}
		assertEquals(2, hits.sum());
	}

	@Test
	void exceptionSynchronizedAndTryCatchBehaviorRemainUnchanged() throws Exception {
		Transformation transformation = transformCalculator();
		Class<?> type = new DefiningLoader().define(Calculator.class.getName(), transformation.bytes);
		Object calculator = type.getConstructor().newInstance();
		InvocationTargetException failure = assertThrows(InvocationTargetException.class,
				() -> type.getMethod("divide", int.class, int.class).invoke(calculator, 1, 0));
		assertTrue(failure.getCause() instanceof ArithmeticException);
		assertEquals(7, type.getMethod("synchronizedAdd", int.class, int.class).invoke(calculator, 3, 4));
		assertEquals(0, type.getMethod("safeDivide", int.class, int.class).invoke(calculator, 3, 0));
		assertEquals(3, type.getMethod("safeDivide", int.class, int.class).invoke(calculator, 9, 3));
	}

	@Test
	void transformingMarkedBytesIsIdempotent() throws Exception {
		Transformation transformation = transformCalculator();
		byte[] second = transformation.transformer.transform(getClass().getClassLoader(),
				Calculator.class.getName().replace('.', '/'), null, null, transformation.bytes);
		assertNull(second);
		assertEquals(1, transformation.metrics.snapshot().alreadyInstrumentedClasses());
	}

	@Test
	void excludedClassRemainsUnchanged() throws Exception {
		Transformation transformation = transformation();
		byte[] original = classBytes(Calculator.class);
		byte[] result = transformation.transformer.transform(getClass().getClassLoader(), "java/lang/Calculator",
				null, getClass().getProtectionDomain(), original);
		assertNull(result);
		assertArrayEquals(original, original);
		assertEquals(0, transformation.metrics.snapshot().classesTransformed());
	}

	@Test
	void generatedFrameworkClassUnderIncludedPrefixRemainsUnchanged() throws Exception {
		assertTrue(MethodEntryClassFileTransformer.isGeneratedFrameworkClass("org/springframework/core/$Proxy54"));
		assertTrue(MethodEntryClassFileTransformer.isGeneratedFrameworkClass(
				"org/springframework/core/Type$auxiliary$generated"));
		Transformation transformation = transformation();
		byte[] result = transformation.transformer.transform(getClass().getClassLoader(),
				"example/instrumented/OwnerRepository$MockitoMock$generated", null,
				getClass().getProtectionDomain(), classBytes(Calculator.class));
		assertNull(result);
		assertEquals(0, transformation.metrics.snapshot().classesTransformed());
		assertEquals(1, transformation.metrics.snapshot().classesIgnored());
		assertNull(transformation.transformer.transform(getClass().getClassLoader(),
				"example/instrumented/Owner$HibernateProxy", null, getClass().getProtectionDomain(),
				classBytes(Calculator.class)));
		assertNull(transformation.transformer.transform(getClass().getClassLoader(),
				"example/instrumented/Owner$HibernateInstantiator", null, getClass().getProtectionDomain(),
				classBytes(Calculator.class)));
		assertEquals(3, transformation.metrics.snapshot().classesIgnored());
	}

	@Test
	void transformedOutputPassesAsmVerification() throws Exception {
		Transformation transformation = transformCalculator();
		assertEquals("", verificationDiagnostics(transformation.bytes));
	}

	@Test
	void isolatedJvmPassesFullVerificationAndProducesDeterministicCatalogAndUnattributedHits() throws Exception {
		Path first = temporaryDirectory.resolve("first.json");
		Path second = temporaryDirectory.resolve("second.json");
		ProcessResult firstRun = runFixture(first);
		ProcessResult secondRun = runFixture(second);
		assertEquals(0, firstRun.exitCode, firstRun.output);
		assertEquals(0, secondRun.exitCode, secondRun.output);
		assertTrue(firstRun.output.contains("instrumented-fixture-ok"));
		String firstJson = Files.readString(first);
		String secondJson = Files.readString(second);
		assertTrue(firstJson.contains("\"classesTransformed\": 3"));
		assertTrue(firstJson.contains("\"methodsInstrumented\": 15"));
		assertTrue(firstJson.contains("\"reason\":\"NO_ACTIVE_TEST\""));
		assertTrue(firstJson.contains("example.instrumented.Calculator#add(II)I"));
		assertTrue(firstJson.contains("example.instrumented.GreetingService#greet(Ljava/lang/String;)Ljava/lang/String;"));
		assertEquals(normalizeVolatileMetrics(firstJson), normalizeVolatileMetrics(secondJson));
	}

	@Test
	void executorCallSitesPropagateExactSubmittingTestWithoutPoolLeakage() throws Exception {
		Path output = temporaryDirectory.resolve("executor.json");
		ProcessResult run = runFixture(output, ExecutorPropagationFixtureMain.class);
		assertEquals(0, run.exitCode, run.output);
		assertTrue(run.output.contains("executor-propagation-fixture-ok"));
		String json = Files.readString(output);
		assertTestHasOnly(json, "single", "single");
		assertTestHasOnly(json, "execute-runnable", "executeRunnable");
		assertTestHasOnly(json, "submit-runnable", "submitRunnable");
		assertTestHasOnly(json, "reuse-a", "reusedA");
		assertTestHasOnly(json, "reuse-b", "reusedB");
		assertTestHasOnly(json, "fixed", "fixedOne", "fixedTwo");
		assertTestHasOnly(json, "callable", "callable");
		assertTestHasOnly(json, "same-runnable-a", "reusedRunnable");
		assertTestHasOnly(json, "same-runnable-b", "reusedRunnable");
		assertTestHasOnly(json, "same-callable-a", "reusedCallable");
		assertTestHasOnly(json, "same-callable-b", "reusedCallable");
		assertTestHasOnly(json, "nested", "nestedOuter", "nestedInner");
		assertTestHasOnly(json, "failure", "failing");
		assertTestHasOnly(json, "direct-thread-pool", "directThreadPool");
		assertTestHasOnly(json, "custom-interface", "customInterface");
		assertTestHasOnly(json, "custom-implementation", "customImplementation");
		assertTestHasOnly(json, "cf-explicit-run", "completableFuture");
		assertTestHasOnly(json, "cf-common-run", "completableFutureCommon");
		assertTestHasOnly(json, "cf-explicit-supply", "completableFutureSupply");
		assertTestHasOnly(json, "cf-common-supply", "completableFutureSupply");
		assertTestHasOnly(json, "cf-common-then-apply", "completableFutureApply");
		assertTestHasOnly(json, "cf-explicit-then-apply", "completableFutureApply");
		assertTestHasOnly(json, "cf-explicit-then-run", "completableFutureThenRun");
		assertTestHasOnly(json, "cf-common-then-run", "completableFutureThenRun");
		assertTestHasOnly(json, "forkjoin-execute", "forkJoinExecute");
		assertTestHasOnly(json, "forkjoin-submit", "forkJoinSubmit");
		String delayed = testSection(json, "delayed");
		assertTrue(delayed.contains("\"reason\":\"LATE_EVENT\""));
		assertTrue(delayed.contains("AsyncApplication#delayed()V"));
		String global = json.substring(json.lastIndexOf("\"unattributedEvents\""));
		assertTrue(global.contains("AsyncApplication#unrelated()V"));
		assertTrue(global.contains("AsyncApplication#afterFailure()V"));
	}

	@Test
	void runnableExecutorOverloadsOutsideTheSupportedDescriptorsRemainValid() throws Exception {
		Path output = temporaryDirectory.resolve("executor-overload.json");
		ProcessResult run = runFixture(output, ExecutorOverloadVerificationFixtureMain.class);
		assertEquals(0, run.exitCode, run.output);
		assertTrue(run.output.contains("executor-overload-fixture-ok"));
	}

	@Test
	void scheduledExecutorsAndRawThreadsPropagateThroughRealAsmInstrumentation() throws Exception {
		Path output = temporaryDirectory.resolve("thread-boundaries.json");
		ProcessResult run = runFixture(output, ThreadBoundaryFixtureMain.class);
		assertEquals(0, run.exitCode, run.output);
		assertTrue(run.output.contains("thread-boundary-fixture-ok"));
		String json = Files.readString(output);
		assertTestHasMethod(json, "scheduled-runnable", "scheduledRunnable");
		assertTestHasMethod(json, "scheduled-callable", "scheduledCallable");
		assertTestHasMethod(json, "fixed-rate", "scheduledFixedRate");
		assertTestHasMethod(json, "fixed-delay", "scheduledFixedDelay");
		assertTestHasMethod(json, "scheduled-nested", "scheduledNestedOuter");
		assertTestHasMethod(json, "scheduled-nested", "scheduledNestedInner");
		assertTestHasMethod(json, "scheduled-failure", "scheduledFailure");
		assertTrue(testSection(json, "late-periodic").contains("\"reason\":\"LATE_EVENT\""));
		assertTrue(testSection(json, "late-periodic").contains("scheduledLatePeriodic"));
		assertTestHasMethod(json, "raw-thread", "rawThread");
		assertTestHasMethod(json, "raw-reuse-a", "rawThreadReused");
		assertTestHasMethod(json, "raw-reuse-b", "rawThreadReused");
		assertTestHasMethod(json, "raw-failure", "rawThreadFailure");
		assertTestHasMethod(json, "raw-nested", "rawThreadNestedOuter");
		assertTestHasMethod(json, "raw-nested", "rawThreadNestedInner");
		assertTrue(json.substring(json.lastIndexOf("\"unattributedEvents\""))
				.contains("AsyncApplication#rawNoContext()V"));
		String global = json.substring(json.lastIndexOf("\"unattributedEvents\""));
		assertTrue(global.contains("AsyncApplication#threadSubclass()V"));
		assertTrue(global.contains("AsyncApplication#forkJoinPoolTaskSubmit()V"));
		assertTrue(global.contains("AsyncApplication#forkJoinPoolTaskInvoke()V"));
		assertTrue(global.contains("AsyncApplication#forkJoinDirectFork()V"));
		assertTrue(global.contains("AsyncApplication#scheduledNoContext()V"));
	}

	@Test
	void jdk21VirtualThreadApisPropagateThroughRealAsmInstrumentation() throws Exception {
		Path javaHome = jdk21Home();
		org.junit.jupiter.api.Assumptions.assumeTrue(javaHome != null, "JDK 21 not installed");
		Path classes = temporaryDirectory.resolve("jdk21-classes");
		Files.createDirectories(classes);
		Path source = Path.of(getClass().getResource(
				"/jdk21/example/instrumented/VirtualThreadFixtureMain.java").toURI());
		Process compile = new ProcessBuilder(javaHome.resolve("bin/javac").toString(), "--release", "21",
				"-cp", agentJar().toString(), "-d", classes.toString(), source.toString())
				.redirectErrorStream(true).start();
		String compileOutput = new String(compile.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertEquals(0, compile.waitFor(), compileOutput);
		Path output = temporaryDirectory.resolve("virtual.json");
		String args = "output=" + output + ";includes=example.instrumented.;runId=fixture-run;debug=false;instrumentation=on";
		Process process = new ProcessBuilder(javaHome.resolve("bin/java").toString(), "-Xverify:all",
				"-javaagent:" + agentJar() + "=" + args, "-cp", classes.toString(),
				"example.instrumented.VirtualThreadFixtureMain").redirectErrorStream(true).start();
		String processOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertEquals(0, process.waitFor(), processOutput);
		assertTrue(processOutput.contains("virtual-thread-fixture-ok"));
		String json = Files.readString(output);
		assertTestContains(json, "start-virtual", "VirtualThreadFixtureMain$Application#startVirtual(");
		assertTestContains(json, "start-virtual", "VirtualThreadFixtureMain$Application#nested(");
		assertTestContains(json, "builder-virtual", "VirtualThreadFixtureMain$Application#builderVirtual(");
		assertTestContains(json, "virtual-executor", "VirtualThreadFixtureMain$Application#virtualExecutor(");
		assertTestContains(json, "virtual-failure", "VirtualThreadFixtureMain$Application#failure(");
		assertTrue(json.substring(json.lastIndexOf("\"unattributedEvents\""))
				.contains("VirtualThreadFixtureMain$Application#noContext()V"));
	}

	private static Path jdk21Home() {
		String configured = System.getenv("JDK21_HOME");
		if (configured != null && Files.isExecutable(Path.of(configured, "bin", "java"))) return Path.of(configured);
		Path macHome = Path.of("/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home");
		return Files.isExecutable(macHome.resolve("bin/java")) ? macHome : null;
	}

	private static void assertTestHasMethod(String json, String testId, String methodName) {
		assertTestContains(json, testId, "AsyncApplication#" + methodName + "(");
	}
	private static void assertTestContains(String json, String testId, String method) {
		assertTrue(testSection(json, testId).contains(method), testId);
	}

	@Test
	void agentJarRelocatesAsmAndDoesNotExposeOriginalPackageOrAsmInPublicApi() throws Exception {
		try (JarFile jar = new JarFile(agentJar().toFile())) {
			assertTrue(jar.stream().anyMatch(entry -> entry.getName()
					.startsWith("com/sap/oss/smarttestpicker/internal/asm/ClassReader")));
			assertTrue(jar.stream().noneMatch(entry -> entry.getName().startsWith("org/objectweb/asm/")));
			for (Class<?> api : List.of(StpAgent.class, AgentConfiguration.class, AgentMetrics.class,
					MethodKeys.class, MethodIdHasher.class, Fnv1a64MethodIdHasher.class,
					MethodCatalog.class, MethodEntryClassFileTransformer.class)) {
				for (var method : api.getMethods()) {
					assertFalse(method.getReturnType().getName().startsWith("org.objectweb.asm"));
					for (Class<?> parameter : method.getParameterTypes()) {
						assertFalse(parameter.getName().startsWith("org.objectweb.asm"));
					}
				}
			}
		}
	}

	private Transformation transformCalculator() throws Exception {
		return transform(Calculator.class);
	}

	private Transformation transform(Class<?> type) throws Exception {
		Transformation transformation = transformation();
		byte[] transformed = transformation.transformer.transform(getClass().getClassLoader(),
				type.getName().replace('.', '/'), null, null, classBytes(type));
		return transformation.withBytes(transformed);
	}

	private String verificationDiagnostics(byte[] bytes) {
		StringWriter diagnostics = new StringWriter();
		CheckClassAdapter.verify(new ClassReader(bytes), getClass().getClassLoader(), false,
				new PrintWriter(diagnostics));
		return diagnostics.toString();
	}

	private Transformation transformation() {
		AgentConfiguration configuration = AgentConfiguration.parse(
				"includes=example.instrumented.;instrumentation=on");
		AgentMetrics metrics = new AgentMetrics(1L);
		MethodCatalog catalog = new MethodCatalog(new Fnv1a64MethodIdHasher(), metrics, ignored -> { });
		MethodEntryClassFileTransformer transformer = new MethodEntryClassFileTransformer(configuration, metrics,
				catalog, ignored -> { });
		return new Transformation(transformer, metrics, catalog, null);
	}

	private ProcessResult runFixture(Path output) throws Exception {
		return runFixture(output, InstrumentedFixtureMain.class);
	}

	private ProcessResult runFixture(Path output, Class<?> mainClass) throws Exception {
		String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		Path sourceClasses = Path.of(mainClass.getProtectionDomain().getCodeSource().getLocation().toURI());
		Path fixtureClasses = temporaryDirectory.resolve("fixture-classes");
		if (Files.notExists(fixtureClasses)) {
			try (var paths = Files.walk(sourceClasses)) {
				for (Path source : paths.toList()) {
					Path target = fixtureClasses.resolve(sourceClasses.relativize(source).toString());
					if (Files.isDirectory(source)) Files.createDirectories(target);
					else Files.copy(source, target);
				}
			}
		}
		String args = "output=" + output + ";includes=example.instrumented.;runId=fixture-run;debug=false;instrumentation=on";
		Process process = new ProcessBuilder(java, "-Xverify:all", "-javaagent:" + agentJar() + "=" + args,
				"-cp", fixtureClasses.toString(), mainClass.getName()).redirectErrorStream(true).start();
		String processOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		return new ProcessResult(process.waitFor(), processOutput);
	}

	private static void assertTestHasOnly(String json, String testId, String... methodNames) {
		String section = testSection(json, testId);
		for (String methodName : methodNames) assertTrue(section.contains("AsyncApplication#" + methodName + "("));
		for (String other : List.of("single", "executeRunnable", "submitRunnable", "reusedA", "reusedB",
				"reusedRunnable", "reusedCallable", "fixedOne", "fixedTwo", "callable", "nestedOuter",
				"nestedInner", "failing", "delayed", "unrelated", "afterFailure", "directThreadPool",
				"customInterface", "customImplementation", "completableFuture", "completableFutureCommon",
				"completableFutureSupply", "completableFutureApply", "completableFutureThenRun",
				"forkJoinExecute", "forkJoinSubmit")) {
			boolean expected = java.util.Arrays.asList(methodNames).contains(other);
			assertEquals(expected, section.contains("AsyncApplication#" + other + "("), testId + " -> " + other);
		}
	}

	private static String testSection(String json, String testId) {
		int start = json.indexOf("\"testId\": \"" + testId + "\"");
		assertTrue(start >= 0, "missing test " + testId);
		int next = json.indexOf("\"testId\": \"", start + 1);
		return json.substring(start, next < 0 ? json.indexOf("\"unattributedEvents\"", start) : next);
	}

	private static String normalizeVolatileMetrics(String json) {
		return json.replaceAll("\"jvmId\": \"pid-[0-9]+\"", "\"jvmId\": \"pid-X\"")
				.replaceAll("\"transformerTotalNanos\": [0-9]+", "\"transformerTotalNanos\": X")
				.replaceAll("\"agentStartNanos\": [0-9]+", "\"agentStartNanos\": X")
				.replaceAll("\"runtimeRecordingNanos\": [0-9]+", "\"runtimeRecordingNanos\": X")
				.replaceAll("\"output\": \"[^\"]+\"", "\"output\": \"OUTPUT\"");
	}

	private static byte[] classBytes(Class<?> type) throws IOException {
		String resource = "/" + type.getName().replace('.', '/') + ".class";
		try (var stream = type.getResourceAsStream(resource)) {
			return stream.readAllBytes();
		}
	}

	private static Path agentJar() {
		return Path.of(System.getProperty("stp.agent.jar"));
	}

	private record Transformation(MethodEntryClassFileTransformer transformer, AgentMetrics metrics,
			MethodCatalog catalog, byte[] bytes) {
		Transformation withBytes(byte[] value) {
			return new Transformation(transformer, metrics, catalog, value);
		}
	}

	private static final class DefiningLoader extends ClassLoader {
		private DefiningLoader() {
			super(MethodEntryTransformationTest.class.getClassLoader());
		}

		Class<?> define(String name, byte[] bytes) {
			return defineClass(name, bytes, 0, bytes.length);
		}
	}

	private record ProcessResult(int exitCode, String output) {
	}
}
