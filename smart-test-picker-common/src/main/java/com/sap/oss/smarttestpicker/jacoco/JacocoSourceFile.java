// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.jacoco;

import java.util.List;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;


/**
 * JAXB model for the JaCoCo XML {@code <sourcefile>} element.
 *
 * <p>A sourcefile element appears inside a {@code <package>} and contains
 * per-line coverage data for a single Java source file. The {@code name}
 * attribute is the simple filename (e.g. {@code "OwnerController.java"}).</p>
 */
@XmlAccessorType(XmlAccessType.FIELD)
public class JacocoSourceFile
{

	/** Source file name (e.g. {@code "OwnerController.java"}). */
	@XmlAttribute(name = "name")
	private String name;

	/** Per-line coverage data. */
	@XmlElement(name = "line")
	private List<JacocoLine> lines;

	public String getName()
	{
		return name;
	}

	public void setName(String name)
	{
		this.name = name;
	}

	public List<JacocoLine> getLines()
	{
		return lines;
	}

	public void setLines(List<JacocoLine> lines)
	{
		this.lines = lines;
	}
}
