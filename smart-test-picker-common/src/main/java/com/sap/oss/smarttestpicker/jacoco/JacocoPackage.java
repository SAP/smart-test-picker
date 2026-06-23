// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.jacoco;

import java.util.List;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;


/**
 * JAXB model for the JaCoCo XML {@code <package>} element.
 *
 * <p>Represents a Java package in the coverage report, containing the classes
 * that were loaded during test execution. The package name uses internal format
 * with slashes (e.g. {@code org/example/service}).</p>
 */
@XmlAccessorType(XmlAccessType.FIELD)
public class JacocoPackage
{

	/** Package name in internal format (e.g. {@code org/example/service}). */
	@XmlAttribute(name = "name")
	private String name;

	/** Classes within this package that have coverage data. */
	@XmlElement(name = "class")
	private List<JacocoClass> classes;

	/** Source files within this package with per-line coverage data. */
	@XmlElement(name = "sourcefile")
	private List<JacocoSourceFile> sourcefiles;

	public String getName()
	{
		return name;
	}

	public List<JacocoClass> getClasses()
	{
		return classes;
	}

	public void setName(String name)
	{
		this.name = name;
	}

	public void setClasses(List<JacocoClass> classes)
	{
		this.classes = classes;
	}

	public List<JacocoSourceFile> getSourcefiles()
	{
		return sourcefiles;
	}

	public void setSourcefiles(List<JacocoSourceFile> sourcefiles)
	{
		this.sourcefiles = sourcefiles;
	}
}

