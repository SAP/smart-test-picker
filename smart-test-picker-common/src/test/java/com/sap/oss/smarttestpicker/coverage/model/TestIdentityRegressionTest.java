// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.model;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.platform.engine.support.descriptor.MethodSource;
import com.sap.oss.smarttestpicker.selector.JUnitMethodSourceIdentity;
import example.audit.ParameterProducerProbe;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestIdentityRegressionTest
{
	@Test void parenthesizedFeatureNamesRoundTrip()
	{
		for (String method : List.of("should work (happy path)", "returns empty list()",
				"is #expected for valueOf(code)", "handles #input (edge case)", "()",
				"works( )", "works(int,)", "works(,int)", "works(int,,long)", "works(void)",
				"works(int, String)", "works(Outer$Inner)"))
		{
			assertRoundTrip(new TestIdentity("com.example.Test", method));
		}
	}

	@Test void primitiveAndArrayParameterSpellingsArePreserved()
	{
		for (String parameters : List.of("boolean, byte, char, short, int, long, float, double",
				"boolean[],byte[][],char[],short[],int[],long[],float[],double[]",
				"[Z, [B, [C, [S, [I, [J, [F, [D", "[[I, [[[D", "[LFoo;",
				"com.example.Outer$Inner[][], long", " java.lang.String , int[] "))
		{
			assertRoundTrip(new TestIdentity("com.example.Test", "works", parameters));
		}
	}

	@Test void methodSourceArrayParametersRoundTrip() throws Exception
	{
		var source = MethodSource.from(ParameterProducerProbe.class.getDeclaredMethod("arrays",
				String.class, int[].class, String[][].class, ParameterProducerProbe.Nested[].class));
		assertEquals("java.lang.String, [I, [[Ljava.lang.String;, [Lexample.audit.ParameterProducerProbe$Nested;",
				source.getMethodParameterTypes());
		assertRoundTrip(JUnitMethodSourceIdentity.from(source));
	}

	@Test void defaultPackageParameterRetainsDocumentedAmbiguity() throws Exception
	{
		var fixture = Class.forName("DefaultPackageParameterFixture");
		var source = MethodSource.from(fixture.getDeclaredMethod("works", Class.forName("Foo")));
		assertEquals("Foo", source.getMethodParameterTypes());
		// Use a qualified test class; only its parameter belongs to the default package.
		var original = new TestIdentity("com.example.Test", "works", source.getMethodParameterTypes());
		var expected = new TestIdentity("com.example.Test", "works(Foo)");
		assertEquals(expected, TestIdentity.parse(original.toString()));
		assertEquals(new ExecutableTestIdentity(ExecutionTarget.parse("ant:cxmc"), expected),
				ExecutableTestIdentity.parse("ant:cxmc::" + original));
		var arraySource = MethodSource.from(fixture.getDeclaredMethod("arrays", Class.forName("[LFoo;")));
		assertEquals("[LFoo;", arraySource.getMethodParameterTypes());
		assertRoundTrip(new TestIdentity("com.example.Test", "arrays", arraySource.getMethodParameterTypes()));
	}

	@Test void primitiveSuffixRetainsDocumentedAmbiguity()
	{
		assertEquals(new TestIdentity("com.example.Test", "foo", "int"),
				TestIdentity.parse(new TestIdentity("com.example.Test", "foo(int)").toString()));
	}

	@Test void malformedDescriptorsAreNotAcceptedAsParameters()
	{
		for (String parameters : List.of("[V", "[II", "[", "[Ljava.lang.String", "[Ljava.lang.String;;",
				"[Ljava/lang/String;", "java..lang.String", "java.lang.String,", "java.lang.String[]x"))
		{
			assertEquals(new TestIdentity("com.example.Test", "works(" + parameters + ")"),
					TestIdentity.parse("com.example.Test#works(" + parameters + ")"), parameters);
		}
	}

	private static void assertRoundTrip(TestIdentity identity)
	{
		assertEquals(identity, TestIdentity.parse(identity.toString()));
		var executable = new ExecutableTestIdentity(ExecutionTarget.parse("ant:cxmc"), identity);
		assertEquals(executable, ExecutableTestIdentity.parse(executable.toString()));
	}

	@Test void spockPlaceholdersInTrailingParenthesesRemainPartOfMethodName()
	{
		for (String method : List.of(
				"is5xxError is #expected for valueOf(#code, #desc)",
				"feature(#value)",
				"feature with nested call(valueOf(#code, #desc))"))
		{
			TestIdentity identity = new TestIdentity(
					"de.hybris.platform.extensibilityservices.client.HttpResponseStatusUnitTest", method);
			assertEquals(identity, TestIdentity.parse(identity.toString()));
			ExecutableTestIdentity executable = new ExecutableTestIdentity(ExecutionTarget.parse("ant:cxmc"), identity);
			assertEquals(executable, ExecutableTestIdentity.parse(executable.toString()));
		}
	}

	@Test void declaredParametersRemainDistinctFromSpockPlaceholders()
	{
		for (String method : List.of("works", "method with spaces", "valueOf(#code, #desc)"))
		{
			for (String parameters : List.of("java.lang.String", "java.lang.String,int[]", "com.example.Outer$Inner[][], long"))
			{
				TestIdentity identity = new TestIdentity("com.example.Test", method, parameters);
				assertEquals(identity, TestIdentity.parse(identity.toString()));
			}
		}
	}

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
