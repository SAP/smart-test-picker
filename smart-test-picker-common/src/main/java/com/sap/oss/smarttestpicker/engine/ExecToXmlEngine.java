// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.engine;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IBundleCoverage;
import org.jacoco.core.tools.ExecFileLoader;
import org.jacoco.report.DirectorySourceFileLocator;
import org.jacoco.report.IReportVisitor;
import org.jacoco.report.xml.XMLFormatter;


/**
 * Converts per-test JaCoCo {@code .exec} files into JaCoCo XML reports.
 *
 * <p>Each {@code .exec} file is analyzed against compiled classes, filtered
 * to only classes with at least one covered line, and written as a JaCoCo XML report.
 * Report generation is parallelized across multiple threads for performance.</p>
 *
 * <p>Supports both single-directory and multi-directory class analysis — the latter
 * is needed for platforms where compiled classes are spread across
 * multiple directories.</p>
 *
 * <p>Class bytecode is preloaded into memory once and reused across all exec files,
 * eliminating repeated disk I/O which is the primary bottleneck when processing
 * thousands of exec files.</p>
 */
public class ExecToXmlEngine
{

	private static final int DEFAULT_THREADS = Runtime.getRuntime().availableProcessors();

	private static final FilenameFilter SESSION_EXEC_FILTER =
			(dir, name) -> name.startsWith("session_") && name.endsWith(".exec");

	private static final FilenameFilter ALL_EXEC_FILTER =
			(dir, name) -> name.endsWith(".exec");

	/**
	 * Scans the exec directory for {@code session_*.exec} files and generates
	 * a per-test XML report for each, using the default thread count.
	 */
	public void generateReports(File execDir, File classesDir, File sourceDir,
			File reportDir, EngineLogger logger) throws IOException
	{
		generateReports(execDir, List.of(classesDir), sourceDir, reportDir,
				logger, DEFAULT_THREADS, SESSION_EXEC_FILTER);
	}

	/**
	 * Scans the exec directory for {@code session_*.exec} files and generates
	 * a per-test XML report for each, using the specified number of threads.
	 */
	public void generateReports(File execDir, File classesDir, File sourceDir,
			File reportDir, EngineLogger logger, int threads) throws IOException
	{
		generateReports(execDir, List.of(classesDir), sourceDir, reportDir,
				logger, threads, SESSION_EXEC_FILTER);
	}

	/**
	 * Scans the exec directory for matching {@code .exec} files and generates
	 * a per-test XML report for each.
	 *
	 * <p>Class bytecode is preloaded into memory before processing begins.
	 * This trades memory (one copy of all class bytes) for dramatically reduced
	 * disk I/O when processing many exec files.</p>
	 *
	 * @param execDir     directory containing {@code .exec} files
	 * @param classesDirs compiled production classes directories (one or more)
	 * @param sourceDir   source directory for source file references (nullable)
	 * @param reportDir   output directory for XML reports
	 * @param logger      engine logger
	 * @param threads     number of parallel threads for report generation
	 * @param execFilter  filename filter for selecting exec files
	 */
	public void generateReports(File execDir, List<File> classesDirs, File sourceDir,
			File reportDir, EngineLogger logger, int threads,
			FilenameFilter execFilter) throws IOException
	{
		File[] execFiles = execDir.listFiles(execFilter);

		if (execFiles == null || execFiles.length == 0)
		{
			logger.info("[SmartTestPicker] No matching .exec files found in {}", execDir.getAbsolutePath());
			return;
		}

		if (!reportDir.exists() && !reportDir.mkdirs())
		{
			logger.warn("[SmartTestPicker] Failed to create report directory: {}", reportDir.getAbsolutePath());
			return;
		}

		int threadCount = Math.max(1, Math.min(threads, execFiles.length));

		// Preload all class bytecode into memory once to avoid repeated disk reads.
		long preloadStart = System.currentTimeMillis();
		Map<String, byte[]> classCache = preloadClassFiles(classesDirs);
		long preloadElapsed = System.currentTimeMillis() - preloadStart;

		logger.info("[SmartTestPicker] Generating XML reports for {} exec files using {} threads across {} classes directories",
				execFiles.length, threadCount, classesDirs.size());
		logger.info("[SmartTestPicker] Preloaded {} class files ({}MB) in {}ms",
				classCache.size(), classCache.values().stream().mapToLong(b -> b.length).sum() / (1024 * 1024), preloadElapsed);

		AtomicInteger generated = new AtomicInteger();
		AtomicInteger skipped = new AtomicInteger();
		AtomicInteger failed = new AtomicInteger();
		long startTime = System.currentTimeMillis();

		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		for (File execFile : execFiles)
		{
			executor.submit(() -> {
				String name = execFile.getName().replace(".exec", "");
				File xmlOut = new File(reportDir, name + ".xml");
				try
				{
					boolean hasContent = generateReport(execFile, classCache, sourceDir, xmlOut);
					if (hasContent)
					{
						generated.incrementAndGet();
					}
					else
					{
						skipped.incrementAndGet();
					}
				}
				catch (Exception e)
				{
					failed.incrementAndGet();
					logger.warn("[SmartTestPicker] Failed to generate report for {}: {}",
							execFile.getName(), e.getMessage());
				}
			});
		}

		executor.shutdown();
		try
		{
			executor.awaitTermination(24, TimeUnit.HOURS);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			logger.warn("[SmartTestPicker] Report generation interrupted");
		}

		long elapsed = System.currentTimeMillis() - startTime;
		logger.info("[SmartTestPicker] Report generation complete: {} generated, {} skipped (no coverage), {} failed, {}ms elapsed",
				generated.get(), skipped.get(), failed.get(), elapsed);
	}

	/**
	 * Returns a filter that accepts all {@code *.exec} files.
	 */
	public static FilenameFilter allExecFilter()
	{
		return ALL_EXEC_FILTER;
	}

	/**
	 * Preloads all {@code .class} files from the given directories into memory.
	 *
	 * @param classesDirs directories to scan for .class files
	 * @return map of relative class path (e.g. "com/example/Foo.class") to bytecode
	 */
	private Map<String, byte[]> preloadClassFiles(List<File> classesDirs) throws IOException
	{
		Map<String, byte[]> cache = new HashMap<>();
		for (File classesDir : classesDirs)
		{
			if (!classesDir.isDirectory())
			{
				continue;
			}
			Files.walk(classesDir.toPath())
					.filter(p -> p.toString().endsWith(".class"))
					.forEach(classFilePath -> {
						try
						{
							String relPath = classesDir.toPath().relativize(classFilePath).toString();
							cache.put(relPath, Files.readAllBytes(classFilePath));
						}
						catch (IOException e)
						{
							// silently skip unreadable classes
						}
					});
		}
		return cache;
	}

	/**
	 * Generates a JaCoCo XML report from a single {@code .exec} file using
	 * preloaded class bytecode from memory.
	 * Only classes with at least one covered line are included in the output.
	 *
	 * @return true if the report was written (had covered classes), false if skipped
	 */
	private boolean generateReport(File execFile, Map<String, byte[]> classCache,
			File sourceDir, File xmlOut) throws IOException
	{
		ExecFileLoader loader = new ExecFileLoader();
		loader.load(execFile);

		// First pass: analyze all cached classes to find which have coverage
		CoverageBuilder coverageBuilder = new CoverageBuilder();
		Analyzer analyzer = new Analyzer(loader.getExecutionDataStore(), coverageBuilder);

		for (Map.Entry<String, byte[]> entry : classCache.entrySet())
		{
			analyzer.analyzeClass(new ByteArrayInputStream(entry.getValue()), entry.getKey());
		}

		var filteredClasses = coverageBuilder.getClasses().stream()
				.filter(cls -> cls.getLineCounter().getCoveredCount() > 0)
				.toList();

		if (filteredClasses.isEmpty())
		{
			return false;
		}

		// Second pass: re-analyze only covered classes for a clean XML report
		// without zero-coverage noise.
		CoverageBuilder filteredBuilder = new CoverageBuilder();
		for (var cls : filteredClasses)
		{
			String classPath = cls.getName() + ".class";
			byte[] classBytes = classCache.get(classPath);
			if (classBytes != null)
			{
				Analyzer reAnalyzer = new Analyzer(loader.getExecutionDataStore(), filteredBuilder);
				reAnalyzer.analyzeClass(new ByteArrayInputStream(classBytes), classPath);
			}
		}

		IBundleCoverage filteredBundle = filteredBuilder.getBundle("smart-report");
		XMLFormatter formatter = new XMLFormatter();
		try (FileOutputStream fos = new FileOutputStream(xmlOut))
		{
			IReportVisitor visitor = formatter.createVisitor(fos);
			visitor.visitInfo(loader.getSessionInfoStore().getInfos(),
					loader.getExecutionDataStore().getContents());
			visitor.visitBundle(filteredBundle,
					sourceDir != null ? new DirectorySourceFileLocator(sourceDir, "utf-8", 4) : null);
			visitor.visitEnd();
		}
		return true;
	}

}
