// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.mapper;

import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.sax.SAXSource;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.io.FileInputStream;
import java.util.Properties;

import com.sap.oss.smarttestpicker.execution.ExecutionIdentityMetadata;
import com.sap.oss.smarttestpicker.execution.ExecutionShape;
import com.sap.oss.smarttestpicker.engine.ExecToXmlEngine;

import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

import com.sap.oss.smarttestpicker.jacoco.JacocoClass;
import com.sap.oss.smarttestpicker.jacoco.JacocoCounter;
import com.sap.oss.smarttestpicker.jacoco.JacocoLine;
import com.sap.oss.smarttestpicker.jacoco.JacocoMethod;
import com.sap.oss.smarttestpicker.jacoco.JacocoPackage;
import com.sap.oss.smarttestpicker.jacoco.JacocoReport;
import com.sap.oss.smarttestpicker.jacoco.JacocoSessionInfo;
import com.sap.oss.smarttestpicker.jacoco.JacocoSourceFile;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Unmarshaller;


/**
 * Parses per-test JaCoCo XML reports and builds a unified coverage mapping.
 *
 * <p>Each XML report ({@code session_TestClass#testMethod.xml}) is parsed via JAXB
 * into a {@link JacocoReport} object tree. The mapper then walks the tree to extract
 * which classes and methods were covered (i.e. have at least one executed instruction).</p>
 *
 * <p>The output is a map: {@code testName -> { "classes": [...], "methods": [...] }}
 * which becomes the core data structure for selective test execution.</p>
 *
 * @see JacocoReport
 * @see CoverageMap
 */
public class CoverageMapperJaxb
{

	/** Directory containing per-test JaCoCo XML reports. */
	private final File reportsDir;
	private final Set<String> productionOutputClasses;
	private final Map<String, ExecutionIdentityMetadata> executionIdentities = new TreeMap<>();

	/** Per-class aggregated coverage metrics, populated during mapping. */
	private Map<String, ClassCoverageMetrics> classMetrics = new TreeMap<>();

	/**
	 * Creates a mapper for the given reports directory.
	 *
	 * @param reportsDir directory containing {@code session_*.xml} JaCoCo reports
	 */
	public CoverageMapperJaxb(File reportsDir)
	{
		this.reportsDir = reportsDir;
		this.productionOutputClasses = loadProductionOutputClasses(reportsDir);
	}

	private static Set<String> loadProductionOutputClasses(File reportsDir)
	{
		File manifest = new File(reportsDir, ExecToXmlEngine.PRODUCTION_CLASSES_MANIFEST);
		if (!manifest.isFile())
			return Set.of();
		try
		{
			return Set.copyOf(Files.readAllLines(manifest.toPath(), StandardCharsets.UTF_8));
		}
		catch (IOException e)
		{
			throw new IllegalStateException("Cannot read production class ownership manifest: " + manifest, e);
		}
	}

	/** Shared JAXB context for all JaCoCo XML model classes — initialized once. */
	public static final JAXBContext JAXB_CTX = initContext();

	/**
	 * Initializes the JAXB context with all JaCoCo model classes.
	 * Called once at class loading time.
	 */
	private static JAXBContext initContext() {
		try {
			return JAXBContext.newInstance(
					JacocoReport.class,
					JacocoPackage.class,
					JacocoClass.class,
					JacocoMethod.class,
					JacocoCounter.class,
					JacocoSessionInfo.class,
					JacocoSourceFile.class,
					JacocoLine.class
			);
		} catch (Exception e) {
			throw new RuntimeException("Failed to init JAXBContext", e);
		}
	}
	/**
	 * Generates the test-to-coverage mapping by parsing all per-test XML reports.
	 *
	 * <p>For each {@code session_*.xml} file in the reports directory:</p>
	 * <ol>
	 *   <li>Extracts the test name from the filename</li>
	 *   <li>Parses the XML into a {@link JacocoReport}</li>
	 *   <li>Walks packages/classes/methods to find covered items</li>
	 *   <li>Builds sorted lists of covered class FQNs and method FQNs</li>
	 * </ol>
	 *
	 * @return map of test name to coverage data ({@code "classes"} and {@code "methods"} lists)
	 */
	public Map<String, Map<String, List<String>>> generateTestCoverageMapping()
	{
		Map<String, Map<String, List<String>>> testMap = new TreeMap<>();

		File[] xmlFiles = reportsDir.listFiles((dir, name) -> name.endsWith(".xml"));
		if (xmlFiles == null)
			return testMap;

		for (File xml : xmlFiles)
		{
			if (xml.length() == 0)
				continue;

			String testName = extractTestName(xml.getName());
			loadExecutionIdentity(testName, xml.getName());

			JacocoReport report = parseXml(xml);
			if (report == null)
				continue;
			if (report.getPackages() == null && !xml.getName().startsWith("session_"))
				continue;

			Set<String> coveredClasses = new HashSet<>();
			Set<String> coveredMethods = new HashSet<>();

			List<JacocoPackage> packages = report.getPackages() != null
					? report.getPackages() : List.of();
			for (JacocoPackage pkg : packages)
			{
				if (pkg.getClasses() == null)
					continue;

				for (JacocoClass cls : pkg.getClasses())
				{
					if (cls == null || cls.getName() == null || cls.getMethods() == null)
						continue;

					String classFqn = cls.getName().replace('/', '.');
					boolean classCovered = false;

					for (JacocoMethod method : cls.getMethods())
					{
						if (method == null || method.getName() == null)
							continue;

						if (method.getCoveredCount() > 0)
						{
							coveredMethods.add(classFqn + "#" + method.getName());
							classCovered = true;
						}
					}

					if (classCovered)
						coveredClasses.add(classFqn);

					collectClassMetrics(classFqn, cls);
				}
			}

			List<String> sortedClasses = new ArrayList<>(coveredClasses);
			Collections.sort(sortedClasses);
			List<String> sortedMethods = new ArrayList<>(coveredMethods);
			Collections.sort(sortedMethods);

			Map<String, List<String>> coverage = new TreeMap<>();
			coverage.put("classes", sortedClasses);
			coverage.put("methods", sortedMethods);

			TestClassFilter.retainProductionClasses(coverage, productionOutputClasses);

			testMap.put(testName, coverage);
		}

		if (!productionOutputClasses.isEmpty())
			classMetrics.keySet().retainAll(productionOutputClasses);
		return testMap;
	}

	public Map<String, ExecutionIdentityMetadata> getExecutionIdentities()
	{
		return new TreeMap<>(executionIdentities);
	}

	private void loadExecutionIdentity(String testName, String xmlName)
	{
		File file = new File(reportsDir, xmlName.substring(0, xmlName.length() - 4) + ".identity.properties");
		if (!file.isFile()) return;
		Properties values = new Properties();
		try (FileInputStream input = new FileInputStream(file))
		{
			values.load(input);
			ExecutionShape shape;
			try { shape = ExecutionShape.valueOf(values.getProperty("executionShape", "UNKNOWN")); }
			catch (IllegalArgumentException ignored) { shape = ExecutionShape.UNKNOWN; }
			executionIdentities.put(testName, new ExecutionIdentityMetadata(null,
					values.getProperty("testClassFqn"), values.getProperty("logicalMethodName"),
					values.getProperty("legacySessionId", testName), values.getProperty("engine", "unknown"), shape));
		}
		catch (IOException ignored) { /* Missing/malformed optional metadata means conservative fallback. */ }
	}

	/**
	 * Returns per-class aggregated coverage metrics collected during
	 * {@link #generateTestCoverageMapping()}.
	 */
	public Map<String, ClassCoverageMetrics> getClassMetrics()
	{
		return classMetrics;
	}

	private void collectClassMetrics(String classFqn, JacocoClass cls)
	{
		if (cls.getCounters() == null)
			return;

		int lineMissed = 0, lineCovered = 0, branchMissed = 0, branchCovered = 0;
		for (JacocoCounter counter : cls.getCounters())
		{
			if ("LINE".equals(counter.getType()))
			{
				lineMissed = counter.getMissed();
				lineCovered = counter.getCovered();
			}
			else if ("BRANCH".equals(counter.getType()))
			{
				branchMissed = counter.getMissed();
				branchCovered = counter.getCovered();
			}
		}

		if (lineMissed == 0 && lineCovered == 0)
			return;

		ClassCoverageMetrics newMetrics = new ClassCoverageMetrics(lineMissed, lineCovered, branchMissed, branchCovered);
		classMetrics.merge(classFqn, newMetrics, ClassCoverageMetrics::merge);
	}

	/**
	 * Extracts the test name from a session XML filename.
	 * E.g. {@code "session_MyTest#testFoo.xml"} becomes {@code "MyTest#testFoo"}.
	 * Reverses the tilde-escaping applied by JacocoPerTestListener for case-insensitive filesystems.
	 */
	private String extractTestName(String filename)
	{
		String name = filename.replace(".xml", "");
		if (name.startsWith("session_"))
		{
			name = name.substring("session_".length());
		}
		return unsanitizeSessionFileName(name);
	}

	/**
	 * Reverses filename sanitization: {@code ~X} becomes uppercase {@code X}.
	 */
	static String unsanitizeSessionFileName(String name)
	{
		int hashIdx = name.indexOf('#');
		if (hashIdx < 0)
		{
			return name;
		}
		String className = name.substring(0, hashIdx);
		String methodPart = name.substring(hashIdx + 1);

		StringBuilder sb = new StringBuilder(methodPart.length());
		for (int i = 0; i < methodPart.length(); i++)
		{
			char c = methodPart.charAt(i);
			if (c == '~' && i + 1 < methodPart.length())
			{
				sb.append(methodPart.charAt(i + 1));
				i++;
			}
			else
			{
				sb.append(c);
			}
		}
		return className + "#" + sb.toString();
	}

	/**
	 * Parses a JaCoCo XML report file into a {@link JacocoReport} object.
	 * Uses a custom entity resolver to prevent loading external DTDs.
	 *
	 * @param file the JaCoCo XML file to parse
	 * @return the parsed report, or {@code null} if parsing fails
	 */
	private JacocoReport parseXml(File file)
	{
		try
		{
			Unmarshaller unmarshaller = JAXB_CTX.createUnmarshaller();


			SAXParserFactory spf = SAXParserFactory.newInstance();
			spf.setNamespaceAware(true);

			XMLReader xmlReader = spf.newSAXParser().getXMLReader();

			// JaCoCo XML files reference report.dtd via DOCTYPE. Return an empty InputSource
			// to prevent the SAX parser from fetching the external DTD over the network.
			xmlReader.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));

			InputSource inputSource = new InputSource(new FileInputStream(file));
			SAXSource source = new SAXSource(xmlReader, inputSource);

			JacocoReport report = (JacocoReport) unmarshaller.unmarshal(source);
			return report;
		}
		catch (Exception e)
		{
			System.err.println("Failed to parse XML file " + file.getName() + ": " + e.getMessage());
			e.printStackTrace();
			return null;
		}
	}
}
