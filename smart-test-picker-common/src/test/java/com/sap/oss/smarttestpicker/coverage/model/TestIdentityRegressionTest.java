// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.model;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestIdentityRegressionTest
{
	@Test void existingLogicalFormsRemainUnchangedAndRoundTrip()
	{
		for (String canonical : List.of(
				"com.example.Test#works",
				"com.example.Outer$Nested#works",
				"com.example.Test#works(java.lang.String,int[])",
				"com.example.Test#method with spaces & punctuation!?:,'\"@%^&*|~`{}<>"))
		{
			TestIdentity identity = TestIdentity.parse(canonical);
			assertEquals(canonical, identity.toString());
			assertEquals(identity, TestIdentity.parse(identity.toString()));
		}
	}
}
