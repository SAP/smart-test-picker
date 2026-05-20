// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker.report;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.sax.SAXSource;

import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.analysis.ILine;
import org.jacoco.core.analysis.ISourceFileCoverage;
import org.jacoco.core.tools.ExecFileLoader;
import org.xml.sax.InputSource;

import io.github.ljubisap.smarttestpicker.jacoco.JacocoLine;
import io.github.ljubisap.smarttestpicker.jacoco.JacocoPackage;
import io.github.ljubisap.smarttestpicker.jacoco.JacocoReport;
import io.github.ljubisap.smarttestpicker.jacoco.JacocoSourceFile;
import io.github.ljubisap.smarttestpicker.mapper.CoverageMapperJaxb;
import jakarta.xml.bind.Unmarshaller;


/**
 * Generates per-class HTML source pages with line-level coverage highlighting.
 *
 * <p>Parses per-test JaCoCo XML reports for selected tests, merges line coverage
 * data across all tests, reads the actual Java source files, and produces one
 * self-contained HTML page per covered class.</p>
 *
 * <p>Output: {@code build/reports/smart-test-picker/sources/{fqn}.html}</p>
 */
public class SourceCoverageGenerator
{

	/**
	 * Generates source coverage HTML pages for classes covered by selected tests.
	 *
	 * @param reportsDir    directory with per-test JaCoCo XML reports (build/jacoco-xml/)
	 * @param sourceDir     Java source directory (src/main/java/)
	 * @param outputDir     output directory for HTML files (build/reports/smart-test-picker/sources/)
	 * @param selectedTests set of selected test names (e.g. "OwnerControllerTests#testShowOwner")
	 * @param testMappings  coverage map: testName -> { "classes": [...], "methods": [...] }
	 * @return source generation result containing source links and per-class coverage percentages
	 */
	public SourceGenerationResult generateSourcePages(
			File reportsDir,
			File sourceDir,
			File outputDir,
			Set<String> selectedTests,
			Map<String, Map<String, List<String>>> testMappings)
	{
		return generateSourcePages(reportsDir,
				sourceDir != null ? List.of(sourceDir) : List.of(),
				outputDir, selectedTests, testMappings);
	}

	/**
	 * Generates source coverage HTML pages, searching multiple source directories
	 * for each class file. Used by platforms where sources are spread across modules.
	 */
	public SourceGenerationResult generateSourcePages(
			File reportsDir,
			List<File> sourceDirs,
			File outputDir,
			Set<String> selectedTests,
			Map<String, Map<String, List<String>>> testMappings)
	{
		return generateSourcePages(
				reportsDir != null ? List.of(reportsDir) : List.of(),
				sourceDirs, outputDir, selectedTests, testMappings);
	}

	/**
	 * Generates source coverage HTML pages using binary exec merge for accurate branch coverage.
	 * Merges per-test .exec files using JaCoCo's ExecFileLoader (probe-level OR),
	 * then analyzes merged data against compiled classes for correct branch counts.
	 *
	 * @param execDirs       directories containing per-test session_*.exec files
	 * @param classesDirs    compiled production classes directories
	 * @param sourceDirs     Java source directories
	 * @param outputDir      output directory for HTML files
	 * @param selectedTests  set of selected test names
	 * @param testMappings   coverage map: testName -> { "classes": [...], "methods": [...] }
	 */
	public SourceGenerationResult generateSourcePagesFromExec(
			List<File> execDirs,
			List<File> classesDirs,
			List<File> sourceDirs,
			File outputDir,
			Set<String> selectedTests,
			Map<String, Map<String, List<String>>> testMappings)
	{
		List<File> validSourceDirs = sourceDirs.stream()
				.filter(d -> d != null && d.exists()).toList();
		List<File> validClassesDirs = classesDirs.stream()
				.filter(d -> d != null && d.exists()).toList();
		List<File> validExecDirs = execDirs.stream()
				.filter(d -> d != null && d.exists()).toList();

		if (validExecDirs.isEmpty() || validClassesDirs.isEmpty() || validSourceDirs.isEmpty())
		{
			return new SourceGenerationResult(Map.of(), Map.of());
		}

		// 1. Identify classes covered by selected tests and which tests cover each class
		Map<String, Set<String>> classToTests = buildClassToTestsMap(selectedTests, testMappings);
		if (classToTests.isEmpty())
		{
			return new SourceGenerationResult(Map.of(), Map.of());
		}

		// 2. Merge exec files of selected tests using JaCoCo binary merge
		ExecFileLoader loader = new ExecFileLoader();
		for (String test : selectedTests)
		{
			for (File execDir : validExecDirs)
			{
				File execFile = new File(execDir, "session_" + test + ".exec");
				if (!execFile.exists())
				{
					execFile = new File(execDir, "session_" + sanitizeSessionFileName(test) + ".exec");
				}
				if (!execFile.exists())
				{
					execFile = new File(execDir, test + ".exec");
				}
				if (execFile.exists())
				{
					try
					{
						loader.load(execFile);
					}
					catch (IOException e)
					{
						// skip unreadable exec files
					}
					break;
				}
			}
		}

		// 3. Analyze only covered classes (not entire classpath)
		CoverageBuilder coverageBuilder = new CoverageBuilder();
		Analyzer analyzer = new Analyzer(loader.getExecutionDataStore(), coverageBuilder);
		for (String classFqn : classToTests.keySet())
		{
			String classPath = classFqn.replace('.', '/') + ".class";
			for (File classesDir : validClassesDirs)
			{
				File classFile = new File(classesDir, classPath);
				if (classFile.exists())
				{
					try (var inputStream = Files.newInputStream(classFile.toPath()))
					{
						analyzer.analyzeClass(inputStream, classPath);
					}
					catch (IOException e)
					{
						// skip
					}
					break;
				}
			}
		}

		// 4. Build line coverage map from JaCoCo analysis results
		Map<String, Map<Integer, LineCoverage>> mergedLineCoverage = new HashMap<>();
		for (ISourceFileCoverage sf : coverageBuilder.getSourceFiles())
		{
			String key = sf.getPackageName() + "/" + sf.getName();
			Map<Integer, LineCoverage> lineMap = new TreeMap<>();
			for (int lineNr = sf.getFirstLine(); lineNr <= sf.getLastLine(); lineNr++)
			{
				ILine line = sf.getLine(lineNr);
				int status = line.getStatus();
				if (status == ICounter.NOT_COVERED || status == ICounter.PARTLY_COVERED
						|| status == ICounter.FULLY_COVERED)
				{
					ICounter instrCounter = line.getInstructionCounter();
					ICounter branchCounter = line.getBranchCounter();
					lineMap.put(lineNr, new LineCoverage(
							instrCounter.getMissedCount(), instrCounter.getCoveredCount(),
							branchCounter.getMissedCount(), branchCounter.getCoveredCount()));
				}
			}
			if (!lineMap.isEmpty())
			{
				mergedLineCoverage.put(key, lineMap);
			}
		}

		// 5. Generate HTML for each class
		return generateHtmlPages(classToTests, mergedLineCoverage, validSourceDirs, outputDir);
	}

	/**
	 * Generates source coverage HTML pages, searching multiple XML report directories
	 * and multiple source directories. Used for multi-module Maven projects where
	 * per-test XML reports are spread across module target directories.
	 */
	public SourceGenerationResult generateSourcePages(
			List<File> reportsDirs,
			List<File> sourceDirs,
			File outputDir,
			Set<String> selectedTests,
			Map<String, Map<String, List<String>>> testMappings)
	{
		List<File> validReportDirs = reportsDirs != null
				? reportsDirs.stream().filter(d -> d != null && d.exists()).toList()
				: List.of();
		if (validReportDirs.isEmpty() || sourceDirs.isEmpty())
		{
			return new SourceGenerationResult(Map.of(), Map.of());
		}
		List<File> validDirs = sourceDirs.stream().filter(d -> d != null && d.exists()).toList();
		if (validDirs.isEmpty())
		{
			return new SourceGenerationResult(Map.of(), Map.of());
		}

		// 1. Identify classes covered by selected tests (these are the changed classes we show)
		Map<String, Set<String>> classToTests = new LinkedHashMap<>();
		for (String test : selectedTests)
		{
			Map<String, List<String>> coverage = testMappings != null ? testMappings.get(test) : null;
			if (coverage == null)
				continue;
			List<String> classes = coverage.get("classes");
			if (classes == null)
				continue;
			for (String cls : classes)
			{
				classToTests.computeIfAbsent(cls, k -> new LinkedHashSet<>()).add(test);
			}
		}

		if (classToTests.isEmpty())
		{
			return new SourceGenerationResult(Map.of(), Map.of());
		}

		// 2. Find ALL tests that cover these classes (not just selected ones)
		//    This gives full baseline coverage on source pages
		Set<String> allCoveringTests = new LinkedHashSet<>(selectedTests);
		if (testMappings != null)
		{
			for (Map.Entry<String, Map<String, List<String>>> entry : testMappings.entrySet())
			{
				List<String> classes = entry.getValue() != null ? entry.getValue().get("classes") : null;
				if (classes == null)
					continue;
				for (String cls : classes)
				{
					if (classToTests.containsKey(cls))
					{
						allCoveringTests.add(entry.getKey());
						classToTests.get(cls).add(entry.getKey());
						break;
					}
				}
			}
		}

		// 3. Parse XML reports for ALL covering tests and collect per-sourcefile line data
		Map<String, Map<Integer, LineCoverage>> mergedLineCoverage = new HashMap<>();

		for (String test : allCoveringTests)
		{
			File xmlFile = findXmlReport(validReportDirs, test);
			if (xmlFile == null)
				continue;

			JacocoReport report = parseXml(xmlFile);
			if (report == null || report.getPackages() == null)
				continue;

			for (JacocoPackage pkg : report.getPackages())
			{
				if (pkg.getSourcefiles() == null)
					continue;
				for (JacocoSourceFile sf : pkg.getSourcefiles())
				{
					if (sf.getLines() == null)
						continue;
					String key = pkg.getName() + "/" + sf.getName();
					Map<Integer, LineCoverage> lineMap = mergedLineCoverage
							.computeIfAbsent(key, k -> new TreeMap<>());

					for (JacocoLine line : sf.getLines())
					{
						lineMap.merge(line.getNr(),
								new LineCoverage(line.getMi(), line.getCi(), line.getMb(), line.getCb()),
								LineCoverage::merge);
					}
				}
			}
		}

		// 4. Generate HTML for each class and compute coverage percentages
		outputDir.mkdirs();
		Map<String, String> sourceLinks = new LinkedHashMap<>();
		Map<String, Double> coveragePercentages = new LinkedHashMap<>();

		for (Map.Entry<String, Set<String>> entry : classToTests.entrySet())
		{
			String classFqn = entry.getKey();
			Set<String> coveringTests = entry.getValue();

			// Convert FQN to source path: org.example.Foo -> org/example/Foo.java
			String relativePath = classFqn.replace('.', '/') + ".java";
			File sourceFile = findSourceFile(validDirs, relativePath);
			if (sourceFile == null)
				continue;

			// Find line coverage for this source file
			// The XML uses internal package format: org/example + Foo.java
			int lastDot = classFqn.lastIndexOf('.');
			String pkgInternal = lastDot >= 0 ? classFqn.substring(0, lastDot).replace('.', '/') : "";
			String simpleFileName = (lastDot >= 0 ? classFqn.substring(lastDot + 1) : classFqn) + ".java";
			String sfKey = pkgInternal.isEmpty() ? simpleFileName : pkgInternal + "/" + simpleFileName;

			Map<Integer, LineCoverage> lineCoverage = mergedLineCoverage.getOrDefault(sfKey, Map.of());

			// Compute coverage percentage including branch coverage
			long totalCi = 0;
			long totalMi = 0;
			long totalCb = 0;
			long totalMb = 0;
			for (LineCoverage lc : lineCoverage.values())
			{
				totalCi += lc.ci;
				totalMi += lc.mi;
				totalCb += lc.cb;
				totalMb += lc.mb;
			}
			long coveredItems = totalCi + totalCb;
			long totalItems = totalCi + totalMi + totalCb + totalMb;
			if (totalItems > 0)
			{
				coveragePercentages.put(classFqn, (double) coveredItems / totalItems * 100);
			}

			// Read source lines
			List<String> sourceLines;
			try
			{
				sourceLines = Files.readAllLines(sourceFile.toPath());
			}
			catch (IOException e)
			{
				continue;
			}

			// Generate HTML
			String html = generateSourceHtml(classFqn, sourceLines, lineCoverage, coveringTests);

			// Write to file
			String htmlFileName = classFqn + ".html";
			File htmlFile = new File(outputDir, htmlFileName);
			try (FileWriter writer = new FileWriter(htmlFile))
			{
				writer.write(html);
			}
			catch (IOException e)
			{
				continue;
			}

			sourceLinks.put(classFqn, "sources/" + htmlFileName);
		}

		return new SourceGenerationResult(sourceLinks, coveragePercentages);
	}

	private Map<String, Set<String>> buildClassToTestsMap(
			Set<String> selectedTests, Map<String, Map<String, List<String>>> testMappings)
	{
		Map<String, Set<String>> classToTests = new LinkedHashMap<>();
		for (String test : selectedTests)
		{
			Map<String, List<String>> coverage = testMappings != null ? testMappings.get(test) : null;
			if (coverage == null) continue;
			List<String> classes = coverage.get("classes");
			if (classes == null) continue;
			for (String cls : classes)
			{
				classToTests.computeIfAbsent(cls, k -> new LinkedHashSet<>()).add(test);
			}
		}
		return classToTests;
	}

	private SourceGenerationResult generateHtmlPages(
			Map<String, Set<String>> classToTests,
			Map<String, Map<Integer, LineCoverage>> mergedLineCoverage,
			List<File> sourceDirs, File outputDir)
	{
		outputDir.mkdirs();
		Map<String, String> sourceLinks = new LinkedHashMap<>();
		Map<String, Double> coveragePercentages = new LinkedHashMap<>();

		for (Map.Entry<String, Set<String>> entry : classToTests.entrySet())
		{
			String classFqn = entry.getKey();
			Set<String> coveringTests = entry.getValue();

			String relativePath = classFqn.replace('.', '/') + ".java";
			File sourceFile = findSourceFile(sourceDirs, relativePath);
			if (sourceFile == null) continue;

			int lastDot = classFqn.lastIndexOf('.');
			String pkgInternal = lastDot >= 0 ? classFqn.substring(0, lastDot).replace('.', '/') : "";
			String simpleFileName = (lastDot >= 0 ? classFqn.substring(lastDot + 1) : classFqn) + ".java";
			String sfKey = pkgInternal.isEmpty() ? simpleFileName : pkgInternal + "/" + simpleFileName;

			Map<Integer, LineCoverage> lineCoverage = mergedLineCoverage.getOrDefault(sfKey, Map.of());

			long totalCi = 0, totalMi = 0, totalCb = 0, totalMb = 0;
			for (LineCoverage lc : lineCoverage.values())
			{
				totalCi += lc.ci;
				totalMi += lc.mi;
				totalCb += lc.cb;
				totalMb += lc.mb;
			}
			long coveredItems = totalCi + totalCb;
			long totalItems = totalCi + totalMi + totalCb + totalMb;
			if (totalItems > 0)
			{
				coveragePercentages.put(classFqn, (double) coveredItems / totalItems * 100);
			}

			List<String> sourceLines;
			try
			{
				sourceLines = Files.readAllLines(sourceFile.toPath());
			}
			catch (IOException e)
			{
				continue;
			}

			String html = generateSourceHtml(classFqn, sourceLines, lineCoverage, coveringTests);
			String htmlFileName = classFqn + ".html";
			File htmlFile = new File(outputDir, htmlFileName);
			try (FileWriter writer = new FileWriter(htmlFile))
			{
				writer.write(html);
			}
			catch (IOException e)
			{
				continue;
			}

			sourceLinks.put(classFqn, "sources/" + htmlFileName);
		}

		return new SourceGenerationResult(sourceLinks, coveragePercentages);
	}

	/**
	 * Generates a self-contained HTML page showing source code with coverage highlighting.
	 */
	private String generateSourceHtml(String classFqn, List<String> sourceLines,
			Map<Integer, LineCoverage> lineCoverage, Set<String> coveringTests)
	{
		int lastDot = classFqn.lastIndexOf('.');
		String pkg = lastDot >= 0 ? classFqn.substring(0, lastDot) : "";
		String className = lastDot >= 0 ? classFqn.substring(lastDot + 1) : classFqn;

		// Compute summary stats (instruction + branch coverage)
		long totalCi = 0;
		long totalMi = 0;
		long totalCb = 0;
		long totalMb = 0;
		int coveredLines = 0;
		int missedLines = 0;
		for (LineCoverage lc : lineCoverage.values())
		{
			totalCi += lc.ci;
			totalMi += lc.mi;
			totalCb += lc.cb;
			totalMb += lc.mb;
			if (lc.ci > 0) coveredLines++;
			else if (lc.mi > 0) missedLines++;
		}
		int totalInstrumentedLines = coveredLines + missedLines;
		long coveredItems = totalCi + totalCb;
		long totalItems = totalCi + totalMi + totalCb + totalMb;
		double coveragePct = totalItems > 0 ? (double) coveredItems / totalItems * 100 : 0;

		StringBuilder html = new StringBuilder();
		html.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n");
		html.append("<meta charset=\"UTF-8\">\n");
		html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
		html.append("<title>").append(esc(className)).append(" \u2014 Coverage</title>\n");
		html.append("<style>\n").append(getSourceCSS()).append("</style>\n");
		html.append("</head>\n<body>\n");

		// Header
		html.append("<header>\n");
		html.append("  <a href=\"../index.html\" class=\"back-link\">\u2190 Back to Report</a>\n");
		html.append("  <h1>").append(esc(className)).append("</h1>\n");
		html.append("  <p class=\"pkg\">").append(esc(pkg)).append("</p>\n");
		html.append("  <div class=\"summary\">\n");
		html.append("    <span class=\"summary-stat\">Lines: <strong>").append(coveredLines)
				.append("/").append(totalInstrumentedLines).append("</strong> covered</span>\n");
		html.append("    <span class=\"summary-stat\">Coverage: <strong>")
				.append(String.format(java.util.Locale.US, "%.1f%%", coveragePct)).append("</strong></span>\n");
		html.append("  </div>\n");

		// Covering tests
		html.append("  <div class=\"covering-tests\">\n");
		html.append("    <strong>Covered by:</strong> ");
		List<String> sortedTests = new ArrayList<>(coveringTests);
		Collections.sort(sortedTests);
		for (int i = 0; i < sortedTests.size(); i++)
		{
			if (i > 0) html.append(", ");
			html.append("<span class=\"test-badge\">").append(esc(sortedTests.get(i))).append("</span>");
		}
		html.append("\n  </div>\n");
		html.append("</header>\n");

		// Source code table
		html.append("<table class=\"source\">\n");
		for (int i = 0; i < sourceLines.size(); i++)
		{
			int lineNr = i + 1;
			LineCoverage lc = lineCoverage.get(lineNr);
			String rowClass = "";
			String indicator = "";
			if (lc != null)
			{
				if (lc.ci > 0)
				{
					rowClass = " class=\"cov-hit\"";
					// Branch coverage indicator
					if (lc.mb > 0 && lc.cb > 0)
					{
						indicator = "<span class=\"branch-partial\" title=\"" + lc.cb + " of "
								+ (lc.mb + lc.cb) + " branches covered\">\u25cf</span>";
					}
					else if (lc.cb > 0)
					{
						indicator = "<span class=\"branch-full\" title=\"All branches covered\">\u25cf</span>";
					}
				}
				else if (lc.mi > 0)
				{
					rowClass = " class=\"cov-miss\"";
					if (lc.mb > 0)
					{
						indicator = "<span class=\"branch-none\" title=\"No branches covered\">\u25cf</span>";
					}
				}
			}

			html.append("<tr").append(rowClass).append(">");
			html.append("<td class=\"ln\" id=\"L").append(lineNr).append("\">").append(lineNr).append("</td>");
			html.append("<td class=\"br\">").append(indicator).append("</td>");
			html.append("<td class=\"code\">").append(esc(sourceLines.get(i))).append("</td>");
			html.append("</tr>\n");
		}
		html.append("</table>\n");

		html.append("</body>\n</html>\n");
		return html.toString();
	}

	private JacocoReport parseXml(File file)
	{
		try
		{
			Unmarshaller unmarshaller = CoverageMapperJaxb.JAXB_CTX.createUnmarshaller();
			SAXParserFactory spf = SAXParserFactory.newInstance();
			spf.setNamespaceAware(true);
			var xmlReader = spf.newSAXParser().getXMLReader();
			xmlReader.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
			InputSource inputSource = new InputSource(new FileInputStream(file));
			SAXSource source = new SAXSource(xmlReader, inputSource);
			return (JacocoReport) unmarshaller.unmarshal(source);
		}
		catch (Exception e)
		{
			return null;
		}
	}

	private String esc(String text)
	{
		if (text == null) return "";
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
				.replace("\"", "&quot;");
	}

	private File findXmlReport(List<File> reportsDirs, String testName)
	{
		for (File dir : reportsDirs)
		{
			File xmlFile = new File(dir, "session_" + testName + ".xml");
			if (xmlFile.exists()) return xmlFile;
			xmlFile = new File(dir, "session_" + sanitizeSessionFileName(testName) + ".xml");
			if (xmlFile.exists()) return xmlFile;
			xmlFile = new File(dir, testName + ".xml");
			if (xmlFile.exists()) return xmlFile;
		}
		return null;
	}

	private File findSourceFile(List<File> sourceDirs, String relativePath)
	{
		for (File dir : sourceDirs)
		{
			File candidate = new File(dir, relativePath);
			if (candidate.exists())
			{
				return candidate;
			}
		}
		return null;
	}

	private String getSourceCSS()
	{
		return "*, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }\n"
				+ "body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;\n"
				+ "  background: #f8f9fa; color: #2c3e50; line-height: 1.5; }\n"
				+ "\n"
				+ "header { background: #1a1a2e; color: #fff; padding: 20px 32px; }\n"
				+ "header h1 { font-size: 1.5rem; margin: 4px 0; }\n"
				+ "header .pkg { font-size: 0.9rem; opacity: 0.6; font-family: monospace; }\n"
				+ ".back-link { color: #74b9ff; text-decoration: none; font-size: 0.85rem; }\n"
				+ ".back-link:hover { text-decoration: underline; }\n"
				+ ".summary { margin-top: 10px; display: flex; gap: 20px; }\n"
				+ ".summary-stat { font-size: 0.9rem; opacity: 0.85; }\n"
				+ ".summary-stat strong { color: #2ecc71; }\n"
				+ ".covering-tests { margin-top: 10px; font-size: 0.85rem; opacity: 0.8; }\n"
				+ ".test-badge { background: rgba(255,255,255,0.15); padding: 2px 8px;\n"
				+ "  border-radius: 10px; font-size: 0.8rem; font-family: monospace; }\n"
				+ "\n"
				+ ".source { width: 100%; border-collapse: collapse; font-family: 'SF Mono', Monaco,\n"
				+ "  'Cascadia Code', 'Consolas', 'Liberation Mono', monospace;\n"
				+ "  font-size: 13px; line-height: 1.5; background: #fff;\n"
				+ "  margin: 0; border-top: 1px solid #e0e0e0; }\n"
				+ ".source tr { border: none; }\n"
				+ ".source td { padding: 0 8px; white-space: pre; vertical-align: top; }\n"
				+ ".source .ln { text-align: right; color: #aaa; user-select: none;\n"
				+ "  width: 50px; min-width: 50px; padding-right: 12px;\n"
				+ "  border-right: 1px solid #e8e8e8; background: #fafafa; }\n"
				+ ".source .br { width: 20px; min-width: 20px; text-align: center;\n"
				+ "  border-right: 1px solid #e8e8e8; }\n"
				+ ".source .code { padding-left: 12px; }\n"
				+ "\n"
				+ ".cov-hit { background: #e6ffe6; }\n"
				+ ".cov-hit .ln { background: #d4f5d4; }\n"
				+ ".cov-miss { background: #ffe6e6; }\n"
				+ ".cov-miss .ln { background: #f5d4d4; }\n"
				+ "\n"
				+ ".branch-full { color: #2ecc71; font-size: 10px; }\n"
				+ ".branch-partial { color: #f39c12; font-size: 10px; }\n"
				+ ".branch-none { color: #e74c3c; font-size: 10px; }\n"
				+ "\n"
				+ "@media print {\n"
				+ "  header { background: #1a1a2e; -webkit-print-color-adjust: exact; print-color-adjust: exact; }\n"
				+ "  .cov-hit, .cov-miss, .cov-hit .ln, .cov-miss .ln {\n"
				+ "    -webkit-print-color-adjust: exact; print-color-adjust: exact; }\n"
				+ "}\n";
	}

	/**
	 * Holds merged line-level coverage counters for a single source line.
	 */
	static class LineCoverage
	{
		int mi;
		int ci;
		int mb;
		int cb;

		LineCoverage(int mi, int ci, int mb, int cb)
		{
			this.mi = mi;
			this.ci = ci;
			this.mb = mb;
			this.cb = cb;
		}

		static LineCoverage merge(LineCoverage a, LineCoverage b)
		{
			int mergedCi = Math.max(a.ci, b.ci);
			int totalInstr = a.mi + a.ci; // total instructions is constant across tests
			int mergedMi = Math.max(0, totalInstr - mergedCi);

			int mergedCb = Math.max(a.cb, b.cb);
			int totalBranches = a.mb + a.cb; // total branches is constant across tests
			int mergedMb = Math.max(0, totalBranches - mergedCb);

			return new LineCoverage(mergedMi, mergedCi, mergedMb, mergedCb);
		}
	}

	/**
	 * Result of source page generation, containing both source links and coverage percentages.
	 */
	public static class SourceGenerationResult
	{
		private final Map<String, String> sourceLinks;
		private final Map<String, Double> coveragePercentages;

		public SourceGenerationResult(Map<String, String> sourceLinks, Map<String, Double> coveragePercentages)
		{
			this.sourceLinks = sourceLinks;
			this.coveragePercentages = coveragePercentages;
		}

		public Map<String, String> getSourceLinks()
		{
			return sourceLinks;
		}

		public Map<String, Double> getCoveragePercentages()
		{
			return coveragePercentages;
		}
	}

	private static String sanitizeSessionFileName(String sessionId)
	{
		int hashIdx = sessionId.indexOf('#');
		if (hashIdx < 0)
		{
			return sessionId;
		}
		String className = sessionId.substring(0, hashIdx);
		String methodName = sessionId.substring(hashIdx + 1);

		StringBuilder sb = new StringBuilder(methodName.length() + 16);
		for (int i = 0; i < methodName.length(); i++)
		{
			char c = methodName.charAt(i);
			if (Character.isUpperCase(c))
			{
				sb.append('~').append(c);
			}
			else
			{
				sb.append(c);
			}
		}
		return className + "#" + sb.toString();
	}
}
