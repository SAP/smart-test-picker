// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.jacoco;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;


/**
 * JAXB model for the JaCoCo XML {@code <line>} element within a {@code <sourcefile>}.
 *
 * <p>Each line element records per-line coverage counters:</p>
 * <ul>
 *   <li>{@code ci > 0} — line has covered instructions (hit)</li>
 *   <li>{@code mi > 0 && ci == 0} — line has only missed instructions (miss)</li>
 *   <li>Line not present — not instrumented</li>
 * </ul>
 */
@XmlAccessorType(XmlAccessType.FIELD)
public class JacocoLine
{

	/** Source line number. */
	@XmlAttribute(name = "nr")
	private int nr;

	/** Missed instruction count. */
	@XmlAttribute(name = "mi")
	private int mi;

	/** Covered instruction count. */
	@XmlAttribute(name = "ci")
	private int ci;

	/** Missed branch count. */
	@XmlAttribute(name = "mb")
	private int mb;

	/** Covered branch count. */
	@XmlAttribute(name = "cb")
	private int cb;

	public int getNr()
	{
		return nr;
	}

	public void setNr(int nr)
	{
		this.nr = nr;
	}

	public int getMi()
	{
		return mi;
	}

	public void setMi(int mi)
	{
		this.mi = mi;
	}

	public int getCi()
	{
		return ci;
	}

	public void setCi(int ci)
	{
		this.ci = ci;
	}

	public int getMb()
	{
		return mb;
	}

	public void setMb(int mb)
	{
		this.mb = mb;
	}

	public int getCb()
	{
		return cb;
	}

	public void setCb(int cb)
	{
		this.cb = cb;
	}
}
