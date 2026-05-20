// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

	private static final List<TestMetricEntry> metrics = new CopyOnWriteArrayList<>();
	private static volatile boolean shutdownHookRegistered = false;

	private final ThreadLocal<String> currentSessionId = new ThreadLocal<>();
	private final ThreadLocal<Long> startTime = new ThreadLocal<>();

	@Override
	public void executionStarted(TestIdentifier id)
	{
		if (!id.isTest())
		{
			return;
		}

		String sessionId = extractSessionId(id);
		currentSessionId.set(sessionId);
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

		if (isMetricsEnabled())
		{
			collectMetrics(sessionId, result);
		}

		currentSessionId.remove();
		startTime.remove();
	}

	private String extractSessionId(TestIdentifier id)
	{
		TestSource source = id.getSource().orElse(null);
		if (source instanceof MethodSource)
		{
			MethodSource ms = (MethodSource) source;
			String className = ms.getJavaClass().getSimpleName();
			return className + "#" + ms.getMethodName();
		}
		return id.getDisplayName();
	}

	private void setJaCoCoSession(String sessionId)
	{
		try
		{
			Class<?> rtClass = Class.forName("org.jacoco.agent.rt.RT");
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
			Class<?> rtClass = Class.forName("org.jacoco.agent.rt.RT");
			Object agent = rtClass.getMethod("getAgent").invoke(null);
			agent.getClass().getMethod("dump", boolean.class).invoke(agent, true);
		}
		catch (Exception e)
		{
			System.err.println("Failed to dump JaCoCo execution data: " + e.getMessage());
		}
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
				Files.copy(execFile, sessionFile, StandardCopyOption.REPLACE_EXISTING);
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
