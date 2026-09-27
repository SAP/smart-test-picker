// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.execution;

/** Optional additive metadata associated with an existing legacy coverage-map key. */
public class ExecutionIdentityMetadata
{
	private String module;
	private String testClassFqn;
	private String logicalMethodName;
	private String legacySessionId;
	private String engine;
	private ExecutionShape executionShape;

	public ExecutionIdentityMetadata() {}
	public ExecutionIdentityMetadata(String module, String testClassFqn, String logicalMethodName,
			String legacySessionId, String engine, ExecutionShape executionShape)
	{
		this.module = module; this.testClassFqn = testClassFqn; this.logicalMethodName = logicalMethodName;
		this.legacySessionId = legacySessionId; this.engine = engine; this.executionShape = executionShape;
	}
	public String getModule() { return module; }
	public void setModule(String value) { module = value; }
	public String getTestClassFqn() { return testClassFqn; }
	public String getLogicalMethodName() { return logicalMethodName; }
	public String getLegacySessionId() { return legacySessionId; }
	public String getEngine() { return engine; }
	public ExecutionShape getExecutionShape() { return executionShape; }
}
