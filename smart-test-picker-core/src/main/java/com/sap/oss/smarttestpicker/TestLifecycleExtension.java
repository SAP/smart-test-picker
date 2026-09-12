// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;


/**
 * JUnit 5 extension that enables per-test coverage tracking with JaCoCo
 * and optional test metrics collection.
 *
 * <p>Before each test method, this extension sets the JaCoCo session ID to
 * {@code TestClass#testMethod}, isolating coverage data per test. After the test
 * completes, it flushes the JaCoCo agent and copies the resulting {@code .exec} file
 * to a per-session file ({@code session_TestClass#testMethod.exec}).</p>
 *
 * <p>When metrics collection is enabled via system property
 * {@code smarttestpicker.metrics.enabled=true}, each test's duration, status,
 * and failure info are recorded and written to
 * {@code target/smart-test-metrics.json} on JVM shutdown.</p>
 *
 * <p>The JaCoCo agent is accessed via reflection to avoid a compile-time dependency
 * on the agent runtime, which is only available when the JVM is started with the
 * JaCoCo agent attached ({@code -javaagent:jacocoagent.jar}).</p>
 *
 * <p>The output directory is resolved via the system property
 * {@code stp.exec.dir}. If not set, auto-detection is used:
 * {@code target/jacoco/} if a {@code target/} directory exists (Maven),
 * otherwise {@code build/jacoco/} (Gradle).</p>
 *
 * @see org.junit.jupiter.api.extension.BeforeTestExecutionCallback
 * @see org.junit.jupiter.api.extension.AfterTestExecutionCallback
 */
public class TestLifecycleExtension implements BeforeTestExecutionCallback, AfterTestExecutionCallback
{

	private static final String METRICS_ENABLED_PROPERTY = "smarttestpicker.metrics.enabled";
	private static final String METRICS_STORE_KEY = "smarttestpicker.startTime";

	private static final List<TestMetricEntry> metrics = new CopyOnWriteArrayList<>();
	private static volatile boolean shutdownHookRegistered = false;

	/** Session ID for the currently executing test ({@code ClassName#methodName}). */
	private String sessionId;
	private JacocoExecutionDataSource executionData;

	/**
	 * Resolves the directory where JaCoCo execution data files are written.
	 *
	 * <p>Resolution order:</p>
	 * <ol>
	 *   <li>System property {@code stp.exec.dir} — explicit override</li>
	 *   <li>Auto-detect: {@code target/jacoco/} if {@code target/} exists (Maven)</li>
	 *   <li>Fallback: {@code build/jacoco/} (Gradle)</li>
	 * </ol>
	 */
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
			Runtime.getRuntime().addShutdownHook(new Thread(TestLifecycleExtension::writeMetrics,
					"smart-test-picker-metrics-writer"));
		}
	}

	/**
	 * Sets the JaCoCo session ID to {@code TestClass#testMethod} before each test,
	 * so that coverage data is tracked per test method.
	 *
	 * @param context the JUnit extension context for the current test
	 */
	@Override
	public void beforeTestExecution(ExtensionContext context)
	{
		// If TestExecutionListener (JacocoPerTestListener) is already active,
		// skip to avoid duplicate per-test coverage processing.
		// This happens on Gradle where both mechanisms are loaded.
		if (JacocoPerTestListener.active)
		{
			return;
		}

		String simpleClass = context.getTestClass().map(Class::getSimpleName).orElse("UnknownClass");
		String fullClass = context.getTestClass().map(Class::getName).orElse("UnknownClass");
		String testMethod = context.getTestMethod().map(method -> method.getName()).orElse("UnknownMethod");

		sessionId = JacocoPerTestListener.buildSessionId(simpleClass, testMethod, fullClass);
		executionData = JacocoExecutionDataSource.active();
		executionData.startSession(sessionId);

		if (isMetricsEnabled())
		{
			registerShutdownHook();
			ExtensionContext.Store store = context.getStore(ExtensionContext.Namespace.create(getClass()));
			store.put(METRICS_STORE_KEY, System.nanoTime());
		}
	}

	/**
	 * Flushes JaCoCo execution data and saves it to a per-test {@code .exec} file
	 * after the test completes. If metrics collection is enabled, records test
	 * duration, status, and failure information.
	 *
	 * @param context the JUnit extension context for the current test
	 */
	@Override
	public void afterTestExecution(ExtensionContext context)
	{
		if (JacocoPerTestListener.active)
		{
			return;
		}

		saveJaCoCoSessionData(sessionId, executionData.snapshotAndReset());

		if (isMetricsEnabled())
		{
			collectMetrics(context);
		}
	}

	private void collectMetrics(ExtensionContext context)
	{
		ExtensionContext.Store store = context.getStore(ExtensionContext.Namespace.create(getClass()));
		Long startTime = store.remove(METRICS_STORE_KEY, Long.class);
		long durationMs = startTime != null ? (System.nanoTime() - startTime) / 1_000_000 : -1;

		String testName = sessionId;
		String status;
		String failureType = null;
		String failureMessage = null;

		if (context.getExecutionException().isPresent())
		{
			Throwable ex = context.getExecutionException().get();
			status = "FAILED";
			failureType = classifyFailure(ex);
			failureMessage = truncate(ex.getMessage(), 200);
		}
		else
		{
			status = "PASSED";
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

	/**
	 * Writes an in-memory JaCoCo snapshot to the STP-owned per-session file.
	 *
	 * @param sessionId the session ID used to name the output file
	 */
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
			else
			{
				System.err.println("JaCoCo returned no execution data for session: " + sessionId);
			}
		}
		catch (Exception e)
		{
			System.err.println("Failed to save JaCoCo execution data: " + e.getMessage());
		}
	}

	/**
	 * Internal record for holding per-test metric data in memory until JVM shutdown.
	 */
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
