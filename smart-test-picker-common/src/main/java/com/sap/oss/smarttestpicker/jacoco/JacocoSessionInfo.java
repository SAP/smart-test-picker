// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.jacoco;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;


/**
 * JAXB model for the JaCoCo XML {@code <sessioninfo>} element.
 *
 * <p>Contains the session identifier (set by the plugin to {@code TestClass#testMethod})
 * and timestamps for when coverage collection started and when data was dumped.
 * The session ID is used to associate a coverage report with a specific test method.</p>
 */
@XmlAccessorType(XmlAccessType.FIELD)
public class JacocoSessionInfo
{

	/** Session identifier, set to {@code TestClass#testMethod} by the plugin. */
	@XmlAttribute(name = "id")
	private String id;

	/** Timestamp (epoch millis) when coverage collection started for this session. */
	@XmlAttribute(name = "start")
	private long start;

	/** Timestamp (epoch millis) when coverage data was dumped (flushed) for this session. */
	@XmlAttribute(name = "dump")
	private long dump;

	public String getId()
	{
		return id;
	}

	public void setId(final String id)
	{
		this.id = id;
	}

	public long getStart()
	{
		return start;
	}

	public void setStart(final long start)
	{
		this.start = start;
	}

	public long getDump()
	{
		return dump;
	}

	public void setDump(final long dump)
	{
		this.dump = dump;
	}
}
