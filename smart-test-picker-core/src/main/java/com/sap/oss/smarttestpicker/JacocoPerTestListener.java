// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Properties;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;


/**
 * JUnit Platform listener that enables per-test coverage tracking with JaCoCo.
 * Works with ALL test engines on JUnit Platform — Jupiter (JUnit 5), Vintage (JUnit 4),
 * and any other engine.
 *
 * <p>Registered via SPI: {@code META-INF/services/org.junit.platform.launcher.TestExecutionListener}</p>
 */
public class JacocoPerTestListener implements TestExecutionListener
{

	private static final String METRICS_ENABLED_PROPERTY = "smarttestpicker.metrics.enabled";

	/**
	 * Flag indicating that this listener is active and handling per-test coverage.
	 * Used by {@link TestLifecycleExtension} to avoid duplicate processing when
	 * both mechanisms are registered (e.g. on Gradle where TestExecutionListener
	 * ServiceLoader works correctly).
	 */
	static volatile boolean active = false;

	private static final List<TestMetricEntry> metrics = new CopyOnWriteArrayList<>();
	private static volatile boolean shutdownHookRegistered = false;

	private final ThreadLocal<String> currentSessionId = new ThreadLocal<>();
	private final ThreadLocal<Long> startTime = new ThreadLocal<>();
	private final ThreadLocal<ObservedIdentity> currentIdentity = new ThreadLocal<>();

	@Override
	public void executionStarted(TestIdentifier id)
	{
		if (!id.isTest())
		{
			return;
		}

		active = true;
		String sessionId = extractSessionId(id);
		currentSessionId.set(sessionId);
		currentIdentity.set(observeIdentity(id, sessionId));
		setJaCoCoSession(sessionId);

		if (isMetricsEnabled())
		{
			registerShutdownHook();
			startTime.set(System.nanoTime());
		}
	}

	@Override
	public void executionFinished(TestIdentifier id, TestExecutionResult result)
	{
		if (!id.isTest())
		{
			return;
		}

		String sessionId = currentSessionId.get();
		if (sessionId == null)
		{
			sessionId = extractSessionId(id);
		}

		dumpJaCoCoData();
		saveJaCoCoSessionData(sessionId);
		saveExecutionIdentity(currentIdentity.get());

		if (isMetricsEnabled())
		{
			collectMetrics(sessionId, result);
		}

		currentSessionId.remove();
		startTime.remove();
		currentIdentity.remove();
	}

	private String extractSessionId(TestIdentifier id)
	{
		TestSource source = id.getSource().orElse(null);
		if (source instanceof MethodSource)
		{
			MethodSource ms = (MethodSource) source;
			return buildSessionId(ms.getJavaClass().getSimpleName(), ms.getMethodName(), ms.getClassName());
		}
		return id.getDisplayName();
	}

	/**
	 * Builds a unique session ID from test class and method names.
	 * Format: {@code SimpleClass#methodName_<hash>} where hash is derived from the FQN
	 * to disambiguate tests with the same simple class name in different packages.
	 *
	 * @param simpleClassName simple class name (e.g. "FooTest")
	 * @param methodName      test method name (e.g. "testSomething")
	 * @param fullClassName   fully qualified class name (e.g. "com.example.FooTest")
	 * @return unique session ID (e.g. "FooTest#testSomething_a7f3b2c")
	 */
	static String buildSessionId(String simpleClassName, String methodName, String fullClassName)
	{
		String fqn = fullClassName + "#" + methodName;
		String hash = Integer.toHexString(fqn.hashCode() & 0x7fffffff);
		while (hash.length() < 7)
		{
			hash = "0" + hash;
		}
		return simpleClassName + "#" + methodName + "_" + hash.substring(0, 7);
	}

	private void setJaCoCoSession(String sessionId)
	{
		try
		{
			Class<?> rtClass = loadJacocoRtClass();
			Object agent = rtClass.getMethod("getAgent").invoke(null);
			agent.getClass().getMethod("setSessionId", String.class).invoke(agent, sessionId);
		}
		catch (Exception e)
		{
			System.err.println("Failed to set JaCoCo session: " + e.getMessage());
		}
	}

	private void dumpJaCoCoData()
	{
		try
		{
			Class<?> rtClass = loadJacocoRtClass();
			Object agent = rtClass.getMethod("getAgent").invoke(null);
			agent.getClass().getMethod("dump", boolean.class).invoke(agent, true);
		}
		catch (Exception e)
		{
			System.err.println("Failed to dump JaCoCo execution data: " + e.getMessage());
		}
	}

	/**
	 * Resolves the JaCoCo runtime across launcher/provider class-loader boundaries.
	 * Maven Surefire and other JUnit Platform launchers may load this listener in
	 * an isolated provider loader while the {@code -javaagent} runtime remains
	 * visible only from the system or a parent context loader.
	 */
	Class<?> loadJacocoRtClass() throws ClassNotFoundException
	{
		try
		{
			return Class.forName("org.jacoco.agent.rt.RT");
		}
		catch (ClassNotFoundException ignored)
		{
		}

		try
		{
			return Class.forName("org.jacoco.agent.rt.RT", true, ClassLoader.getSystemClassLoader());
		}
		catch (ClassNotFoundException ignored)
		{
		}

		ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
		while (classLoader != null)
		{
			try
			{
				return Class.forName("org.jacoco.agent.rt.RT", true, classLoader);
			}
			catch (ClassNotFoundException ignored)
			{
				classLoader = classLoader.getParent();
			}
		}

		try
		{
			return Class.forName("org.jacoco.agent.rt.RT", true,
					JacocoPerTestListener.class.getClassLoader());
		}
		catch (ClassNotFoundException ignored)
		{
		}

		throw new ClassNotFoundException("org.jacoco.agent.rt.RT not found in any classloader");
	}

	private void saveJaCoCoSessionData(String sessionId)
	{
		try
		{
			String execDir = resolveExecDir();
			Path dir = Path.of(execDir);
			Files.createDirectories(dir);

			Path execFile = dir.resolve("test.exec");
			Path sessionFile = dir.resolve("session_" + SessionFileNames.sanitize(sessionId) + ".exec");

			if (Files.exists(execFile))
			{
				// Append to existing session file to merge coverage from multiple
				// invocations of the same test (e.g. parameterized test invocations).
				// JaCoCo exec format supports concatenation — ExecFileLoader merges
				// all blocks via OR on probes when reading.
				try (var in = Files.newInputStream(execFile);
					 var out = Files.newOutputStream(sessionFile,
							 java.nio.file.StandardOpenOption.CREATE,
							 java.nio.file.StandardOpenOption.APPEND))
				{
					in.transferTo(out);
				}
				Files.deleteIfExists(execFile);
			}
		}
		catch (Exception e)
		{
			System.err.println("Failed to save JaCoCo execution data: " + e.getMessage());
		}
	}

	private static String resolveExecDir()
	{
		String explicit = System.getProperty("stp.exec.dir");
		if (explicit != null && !explicit.isEmpty())
		{
			return explicit.endsWith("/") ? explicit : explicit + "/";
		}

		if (Files.isDirectory(Path.of("target")))
		{
			return "target/jacoco/";
		}

		return "build/jacoco/";
	}

	private ObservedIdentity observeIdentity(TestIdentifier id, String sessionId)
	{
		TestSource source = id.getSource().orElse(null);
		if (!(source instanceof MethodSource ms))
			return new ObservedIdentity(sessionId, null, null, engine(id), "UNKNOWN");
		return new ObservedIdentity(sessionId, ms.getClassName(), ms.getMethodName(), engine(id),
				executionShape(ms.getJavaClass(), ms.getMethodName(), engine(id)));
	}

	private static String engine(TestIdentifier id)
	{
		String uniqueId = id.getUniqueId();
		int start = uniqueId.indexOf("[engine:");
		if (start < 0) return "unknown";
		int end = uniqueId.indexOf(']', start);
		return end > start ? uniqueId.substring(start + 8, end) : "unknown";
	}

	static String executionShape(Class<?> testClass, String methodName, String engine)
	{
		try
		{
			if ("junit-jupiter".equals(engine))
			{
				for (Method method : allMethods(testClass))
					if (method.getName().equals(methodName) && hasAnnotation(method.getAnnotations(),
							"org.junit.jupiter.params.ParameterizedTest")) return "JUPITER_PARAMETERIZED";
				return "ORDINARY";
			}
			if ("junit-vintage".equals(engine))
			{
				for (Annotation annotation : testClass.getAnnotations())
				{
					if (!annotation.annotationType().getName().equals("org.junit.runner.RunWith")) continue;
					Class<?> runner = (Class<?>) annotation.annotationType().getMethod("value").invoke(annotation);
					if (!runner.getName().equals("org.junit.runners.Parameterized")) return "UNSUPPORTED_RUNNER";
					for (Method factory : testClass.getMethods())
						for (Annotation candidate : factory.getAnnotations())
							if (candidate.annotationType().getName().equals("org.junit.runners.Parameterized$Parameters"))
							{
								String name = String.valueOf(candidate.annotationType().getMethod("name").invoke(candidate));
								return name.matches(".*\\{[0-9]+}.*")
										? "JUNIT4_PARAMETERIZED_NAMED" : "JUNIT4_PARAMETERIZED_INDEXED";
							}
					return "UNKNOWN";
				}
				return "ORDINARY";
			}
		}
		catch (ReflectiveOperationException | LinkageError ignored) { return "UNKNOWN"; }
		return "UNKNOWN";
	}

	private static boolean hasAnnotation(Annotation[] annotations, String name)
	{
		for (Annotation annotation : annotations)
			if (annotation.annotationType().getName().equals(name)) return true;
		return false;
	}

	private static List<Method> allMethods(Class<?> type)
	{
		List<Method> methods = new java.util.ArrayList<>(List.of(type.getMethods()));
		for (Method method : type.getDeclaredMethods()) if (!methods.contains(method)) methods.add(method);
		return methods;
	}

	private static synchronized void saveExecutionIdentity(ObservedIdentity identity)
	{
		if (identity == null || identity.classFqn == null || identity.methodName == null) return;
		try
		{
			Path dir = Path.of(resolveExecDir()); Files.createDirectories(dir);
			Path file = dir.resolve("session_" + SessionFileNames.sanitize(identity.sessionId) + ".identity.properties");
			Properties properties = new Properties();
			properties.setProperty("legacySessionId", identity.sessionId);
			properties.setProperty("testClassFqn", identity.classFqn);
			properties.setProperty("logicalMethodName", identity.methodName);
			properties.setProperty("engine", identity.engine);
			properties.setProperty("executionShape", identity.executionShape);
			try (OutputStream out = Files.newOutputStream(file)) { properties.store(out, "JZC-02A execution identity"); }
		}
		catch (Exception e) { System.err.println("Failed to save execution identity: " + e.getMessage()); }
	}

	private record ObservedIdentity(String sessionId, String classFqn, String methodName,
			String engine, String executionShape) {}

	private static boolean isMetricsEnabled()
	{
		return "true".equalsIgnoreCase(System.getProperty(METRICS_ENABLED_PROPERTY));
	}

	private static synchronized void registerShutdownHook()
	{
		if (!shutdownHookRegistered)
		{
			shutdownHookRegistered = true;
			Runtime.getRuntime().addShutdownHook(new Thread(JacocoPerTestListener::writeMetrics,
					"smart-test-picker-metrics-writer"));
		}
	}

	private void collectMetrics(String testName, TestExecutionResult result)
	{
		Long start = startTime.get();
		long durationMs = start != null ? (System.nanoTime() - start) / 1_000_000 : -1;

		String status;
		String failureType = null;
		String failureMessage = null;

		if (result.getThrowable().isPresent())
		{
			Throwable ex = result.getThrowable().get();
			status = "FAILED";
			failureType = classifyFailure(ex);
			failureMessage = truncate(ex.getMessage(), 200);
		}
		else
		{
			status = result.getStatus() == TestExecutionResult.Status.SUCCESSFUL ? "PASSED" : "FAILED";
		}

		metrics.add(new TestMetricEntry(testName, status, durationMs, failureType, failureMessage));
	}

	private static String classifyFailure(Throwable ex)
	{
		String className = ex.getClass().getName();
		if (className.contains("AssertionError") || className.contains("AssertionFailedError")
				|| className.contains("ComparisonFailure"))
		{
			return "ASSERTION";
		}
		if (className.contains("TimeoutException") || className.contains("TestTimedOutException"))
		{
			return "TIMEOUT";
		}
		return "EXCEPTION";
	}

	private static String truncate(String s, int maxLen)
	{
		if (s == null)
		{
			return null;
		}
		return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
	}

	private static void writeMetrics()
	{
		if (metrics.isEmpty())
		{
			return;
		}

		Path outputDir = Files.isDirectory(Path.of("target")) ? Path.of("target") : Path.of("build");
		Path outputFile = outputDir.resolve("smart-test-metrics.json");

		try
		{
			Files.createDirectories(outputDir);
			StringBuilder sb = new StringBuilder();
			sb.append("{\n");
			sb.append("  \"tests\": [\n");

			for (int i = 0; i < metrics.size(); i++)
			{
				TestMetricEntry entry = metrics.get(i);
				sb.append("    {");
				sb.append("\"name\": ").append(jsonString(entry.name));
				sb.append(", \"status\": ").append(jsonString(entry.status));
				sb.append(", \"durationMs\": ").append(entry.durationMs);
				if (entry.failureType != null)
				{
					sb.append(", \"failureType\": ").append(jsonString(entry.failureType));
				}
				if (entry.failureMessage != null)
				{
					sb.append(", \"failureMessage\": ").append(jsonString(entry.failureMessage));
				}
				sb.append("}");
				if (i < metrics.size() - 1)
				{
					sb.append(",");
				}
				sb.append("\n");
			}

			sb.append("  ]\n");
			sb.append("}\n");

			Files.writeString(outputFile, sb.toString());
		}
		catch (IOException e)
		{
			System.err.println("[SmartTestPicker] Failed to write test metrics: " + e.getMessage());
		}
	}

	private static String jsonString(String value)
	{
		if (value == null)
		{
			return "null";
		}
		return "\"" + value.replace("\\", "\\\\")
				.replace("\"", "\\\"")
				.replace("\n", "\\n")
				.replace("\r", "\\r")
				.replace("\t", "\\t") + "\"";
	}

	private static class TestMetricEntry
	{
		final String name;
		final String status;
		final long durationMs;
		final String failureType;
		final String failureMessage;

		TestMetricEntry(String name, String status, long durationMs, String failureType, String failureMessage)
		{
			this.name = name;
			this.status = status;
			this.durationMs = durationMs;
			this.failureType = failureType;
			this.failureMessage = failureMessage;
		}
	}
}
