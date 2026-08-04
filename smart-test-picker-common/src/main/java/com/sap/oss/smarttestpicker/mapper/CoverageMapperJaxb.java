// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.mapper;

import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.sax.SAXSource;

import java.io.File;
import java.io.FileInputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

	/** Per-class aggregated coverage metrics, populated during mapping. */
	private Map<String, ClassCoverageMetrics> classMetrics = new HashMap<>();

	/**
	 * Creates a mapper for the given reports directory.
	 *
	 * @param reportsDir directory containing {@code session_*.xml} JaCoCo reports
	 */
	public CoverageMapperJaxb(File reportsDir)
	{
		this.reportsDir = reportsDir;
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
		Map<String, Map<String, List<String>>> testMap = new HashMap<>();

		File[] xmlFiles = reportsDir.listFiles((dir, name) -> name.endsWith(".xml"));
		if (xmlFiles == null)
			return testMap;

		for (File xml : xmlFiles)
		{
			if (xml.length() == 0)
				continue;

			String testName = extractTestName(xml.getName());

			JacocoReport report = parseXml(xml);
			if (report == null || report.getPackages() == null)
				continue;

			Set<String> coveredClasses = new HashSet<>();
			Set<String> coveredMethods = new HashSet<>();

			for (JacocoPackage pkg : report.getPackages())
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

			Map<String, List<String>> coverage = new HashMap<>();
			coverage.put("classes", sortedClasses);
			coverage.put("methods", sortedMethods);

			TestClassFilter.filterTestClasses(coverage);

			testMap.put(testName, coverage);
		}

		return testMap;
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
