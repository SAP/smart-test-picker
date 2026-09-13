// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;

final class MavenSurefireTestFilter {
	private MavenSurefireTestFilter() { }

	static Predicate<TestIdentity> from(MavenProject project) {
		String expression = project.getBuildPlugins().stream()
				.filter(plugin -> "org.apache.maven.plugins".equals(plugin.getGroupId()))
				.filter(plugin -> "maven-surefire-plugin".equals(plugin.getArtifactId()))
				.map(Plugin::getConfiguration).filter(Xpp3Dom.class::isInstance).map(Xpp3Dom.class::cast)
				.map(configuration -> configuration.getChild("test")).filter(java.util.Objects::nonNull)
				.map(Xpp3Dom::getValue).filter(java.util.Objects::nonNull).findFirst().orElse(null);
		return parse(expression);
	}

	static Predicate<TestIdentity> parse(String expression) {
		if (expression == null || expression.isBlank()) return ignored -> true;
		List<Predicate<TestIdentity>> includes = new ArrayList<>();
		List<Predicate<TestIdentity>> excludes = new ArrayList<>();
		for (String raw : expression.split(",", -1)) {
			String token = raw.trim();
			if (token.isEmpty()) throw new IllegalArgumentException("Malformed Maven Surefire test expression: " + expression);
			boolean excluded = token.startsWith("!");
			if (excluded) token = token.substring(1).trim();
			if (token.isEmpty()) throw new IllegalArgumentException("Malformed Maven Surefire test expression: " + expression);
			Pattern pattern = Pattern.compile(glob(token.contains("#") ? token : token + "#*"));
			Predicate<TestIdentity> predicate = test -> pattern.matcher(projection(test)).matches();
			(excluded ? excludes : includes).add(predicate);
		}
		return test -> (includes.isEmpty() || includes.stream().anyMatch(filter -> filter.test(test)))
				&& excludes.stream().noneMatch(filter -> filter.test(test));
	}

	private static String projection(TestIdentity test) {
		return test.className() + "#" + test.methodName();
	}

	private static String glob(String value) {
		StringBuilder result = new StringBuilder("^");
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			if (character == '*') result.append(".*");
			else if (character == '?') result.append('.');
			else result.append(Pattern.quote(String.valueOf(character)));
		}
		return result.append('$').toString();
	}
}
