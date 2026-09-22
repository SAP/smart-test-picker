// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;


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
	private final ThreadLocal<DeclaredTestIdentity> currentTestIdentity = new ThreadLocal<>();
	private final ThreadLocal<Long> startTime = new ThreadLocal<>();
	private final ThreadLocal<String> setupContainer = new ThreadLocal<>();
	private volatile TestPlan testPlan;
	private JacocoExecutionDataSource executionData;

	@Override
	public void testPlanExecutionStarted(TestPlan plan) { testPlan = plan; }

	@Override
	public void executionStarted(TestIdentifier id)
	{
		if (!id.isTest())
		{
			id.getSource().filter(ClassSource.class::isInstance).map(ClassSource.class::cast).ifPresent(source -> {
				executionData = JacocoExecutionDataSource.active();
				setupContainer.set(source.getClassName());
				executionData.startSession("setup:" + source.getClassName());
			});
			return;
		}

		if (executionData != null && setupContainer.get() != null)
		{
			writeSetupCoverage(setupContainer.get(), executionData.snapshotAndReset());
			setupContainer.remove();
		}

		DeclaredTestIdentity identity = extractTestIdentity(id);
		String sessionId = identity != null
				? buildSessionId(identity.simpleClassName(), identity.methodName(), identity.className(), identity.methodParameterTypes())
				: id.getDisplayName();
		currentSessionId.set(sessionId);
		currentTestIdentity.set(identity);
		executionData = JacocoExecutionDataSource.active();
		executionData.startSession(sessionId);
		active = true;

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
			if (result.getStatus() == TestExecutionResult.Status.ABORTED)
			{
				markNonExecuted(id);
			}
			return;
		}

		String sessionId = currentSessionId.get();
		if (sessionId == null)
		{
			DeclaredTestIdentity fallbackIdentity = extractTestIdentity(id);
			sessionId = fallbackIdentity != null
					? buildSessionId(fallbackIdentity.simpleClassName(), fallbackIdentity.methodName(),
							fallbackIdentity.className(), fallbackIdentity.methodParameterTypes())
					: id.getDisplayName();
			currentTestIdentity.set(fallbackIdentity);
		}

		if (executionData != null)
		{
			byte[] snapshot = executionData.snapshotAndReset();
			if (result.getStatus() != TestExecutionResult.Status.ABORTED)
			{
				saveJaCoCoSessionData(sessionId, snapshot);
			}
		}
		DeclaredTestIdentity identity = currentTestIdentity.get();
		if (identity != null)
		{
			if (result.getStatus() == TestExecutionResult.Status.ABORTED)
			{
				markNonExecuted(id);
			}
			else
			{
				writeTestIdentity(sessionId, identity,
						result.getStatus() == TestExecutionResult.Status.SUCCESSFUL ? "PASS" : "FAIL");
			}
		}

		if (isMetricsEnabled())
		{
			collectMetrics(sessionId, result);
		}

		currentSessionId.remove();
		currentTestIdentity.remove();
		startTime.remove();
	}

	@Override
	public void executionSkipped(TestIdentifier id, String reason)
	{
		markNonExecuted(id);
	}

	private void markNonExecuted(TestIdentifier id)
	{
		List<TestIdentifier> skipped;
		if (id.isTest() || extractTestIdentity(id) != null)
			skipped = List.of(id);
		else
			skipped = testPlan == null ? List.of()
					: testPlan.getDescendants(id).stream().filter(TestIdentifier::isTest).toList();
		for (TestIdentifier skippedId : skipped)
		try
		{
			DeclaredTestIdentity identity = extractTestIdentity(skippedId);
			if (identity == null) continue;
			String sessionId = buildSessionId(identity.simpleClassName(), identity.methodName(),
					identity.className(), identity.methodParameterTypes());
			Path file = Path.of(resolveExecDir()).resolve("session_" + SessionFileNames.sanitize(sessionId) + ".non-executed");
			Files.createDirectories(file.getParent());
			Files.writeString(file, "format=1\nclassName=" + identity.className() + "\nmethodName="
					+ identity.methodName() + "\nmethodParameterTypes=" + identity.methodParameterTypes() + "\n");
		}
		catch (Exception e) { System.err.println("Failed to save authoritative non-execution identity: " + e.getMessage()); }
	}

	private DeclaredTestIdentity extractTestIdentity(TestIdentifier id)
	{
		TestSource source = id.getSource().orElse(null);
		if (source instanceof MethodSource)
		{
			MethodSource ms = (MethodSource) source;
			return new DeclaredTestIdentity(ms.getJavaClass().getSimpleName(), ms.getClassName(),
					ms.getMethodName(), ms.getMethodParameterTypes());
		}

		// JUnit Vintage custom runners, including ArchUnitRunner, may expose an
		// executable test with a ClassSource instead of a MethodSource. Inventory
		// discovery uses the same Vintage contract: the declaring class comes from
		// ClassSource and the declared logical test name from legacyReportingName.
		// Do not apply this fallback to other engines, where a display name is not
		// an authoritative declared test identity.
		if (!id.getUniqueId().contains("[engine:junit-vintage]")) return null;
		ClassSource testClass = classSource(id).orElse(null);
		if (testClass == null) return null;
		String methodName = vintageMethodName(id.getLegacyReportingName()).orElse(null);
		if (methodName == null || isClassName(methodName, testClass.getClassName())) return null;
		Class<?> javaClass = testClass.getJavaClass();
		return new DeclaredTestIdentity(javaClass.getSimpleName(), testClass.getClassName(), methodName, "");
	}

	private Optional<ClassSource> classSource(TestIdentifier id)
	{
		TestIdentifier current = id;
		while (current != null)
		{
			Optional<ClassSource> source = current.getSource().filter(ClassSource.class::isInstance)
					.map(ClassSource.class::cast);
			if (source.isPresent()) return source;
			current = testPlan == null ? null : testPlan.getParent(current).orElse(null);
		}
		return Optional.empty();
	}

	private static Optional<String> vintageMethodName(String legacyReportingName)
	{
		if (legacyReportingName == null) return Optional.empty();
		String methodName = legacyReportingName;
		int classSuffix = methodName.indexOf('(');
		if (classSuffix >= 0) methodName = methodName.substring(0, classSuffix);
		methodName = methodName.trim();
		return methodName.isEmpty() || "initializationError".equals(methodName)
				? Optional.empty() : Optional.of(methodName);
	}

	private static boolean isClassName(String candidate, String className)
	{
		int separator = Math.max(className.lastIndexOf('.'), className.lastIndexOf('$'));
		String simpleName = className.substring(separator + 1);
		return candidate.equals(className) || candidate.equals(simpleName);
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
		return buildSessionId(simpleClassName, methodName, fullClassName, "");
	}

	static String buildSessionId(String simpleClassName, String methodName, String fullClassName,
			String methodParameterTypes)
	{
		String fqn = fullClassName + "#" + methodName + "(" + methodParameterTypes + ")";
		String hash = Integer.toHexString(fqn.hashCode() & 0x7fffffff);
		while (hash.length() < 7)
		{
			hash = "0" + hash;
		}
		return simpleClassName + "#" + methodName + "_" + hash.substring(0, 7);
	}

	private synchronized void writeTestIdentity(String sessionId, DeclaredTestIdentity identity, String outcome)
	{
		try
		{
			Path file = Path.of(resolveExecDir()).resolve("session_" + SessionFileNames.sanitize(sessionId) + ".identity");
			Files.createDirectories(file.getParent());
			String previous = Files.exists(file) ? Files.readString(file) : "";
			String mergedOutcome = previous.contains("outcome=FAIL\n") ? "FAIL" : outcome;
			String content = "format=1\n"
					+ "className=" + identity.className() + "\n"
					+ "methodName=" + identity.methodName() + "\n"
					+ "methodParameterTypes=" + identity.methodParameterTypes() + "\n"
					+ "outcome=" + mergedOutcome + "\n";
			Files.writeString(file, content);
		}
		catch (Exception e)
		{
			System.err.println("Failed to save authoritative test identity: " + e.getMessage());
		}
	}

	private synchronized void writeSetupCoverage(String container, byte[] snapshot)
	{
		try
		{
			String base = "session_setup_" + Integer.toHexString(container.hashCode() & 0x7fffffff);
			Path directory = Path.of(resolveExecDir());
			Files.createDirectories(directory);
			Files.writeString(directory.resolve(base + ".setup"), "format=1\ncontainer=" + container + "\n");
			if (snapshot.length > 0) Files.write(directory.resolve(base + ".exec"), snapshot,
					java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
		}
		catch (Exception e)
		{
			System.err.println("Failed to save bounded setup coverage: " + e.getMessage());
		}
	}

	private record DeclaredTestIdentity(String simpleClassName, String className, String methodName,
			String methodParameterTypes) {}

	private void saveJaCoCoSessionData(String sessionId, byte[] snapshot)
	{
		try
		{
			String execDir = resolveExecDir();
			Path dir = Path.of(execDir);
			Files.createDirectories(dir);

			Path sessionFile = dir.resolve("session_" + SessionFileNames.sanitize(sessionId) + ".exec");

			if (snapshot.length > 0)
			{
				// Append to existing session file to merge coverage from multiple
				// invocations of the same test (e.g. parameterized test invocations).
				// JaCoCo exec format supports concatenation — ExecFileLoader merges
				// all blocks via OR on probes when reading.
				try (var out = Files.newOutputStream(sessionFile,
							 java.nio.file.StandardOpenOption.CREATE,
							 java.nio.file.StandardOpenOption.APPEND))
				{
					out.write(snapshot);
				}
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
