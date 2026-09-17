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
		Xpp3Dom configuration = project.getBuildPlugins().stream()
				.filter(plugin -> "org.apache.maven.plugins".equals(plugin.getGroupId()))
				.filter(plugin -> "maven-surefire-plugin".equals(plugin.getArtifactId()))
				.map(Plugin::getConfiguration).filter(Xpp3Dom.class::isInstance).map(Xpp3Dom.class::cast)
				.findFirst().orElse(null);
		if (configuration == null) return ignored -> true;
		Xpp3Dom test = configuration.getChild("test");
		Predicate<TestIdentity> expression = parse(test == null ? null : test.getValue());
		List<Pattern> includes = patterns(configuration.getChild("includes"), "include");
		List<Pattern> excludes = patterns(configuration.getChild("excludes"), "exclude");
		return identity -> expression.test(identity)
				&& (includes.isEmpty() || includes.stream().anyMatch(pattern -> pattern.matcher(path(identity)).matches()))
				&& excludes.stream().noneMatch(pattern -> pattern.matcher(path(identity)).matches());
	}

	private static List<Pattern> patterns(Xpp3Dom parent, String childName) {
		if (parent == null) return List.of();
		List<Pattern> result = new ArrayList<>();
		for (Xpp3Dom child : parent.getChildren(childName)) {
			if (child.getValue() != null && !child.getValue().isBlank())
				result.add(Pattern.compile(glob(child.getValue().trim())));
		}
		return result;
	}

	private static String path(TestIdentity identity) {
		return identity.className().replace('.', '/') + ".java";
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
			if (character == '*' && index + 2 < value.length() && value.charAt(index + 1) == '*'
					&& value.charAt(index + 2) == '/') {
				result.append("(?:.*/)?");
				index += 2;
			}
			else if (character == '*' && index + 1 < value.length() && value.charAt(index + 1) == '*') {
				result.append(".*");
				index++;
			}
			else if (character == '*') result.append("[^/]*");
			else if (character == '?') result.append("[^/]");
			else result.append(Pattern.quote(String.valueOf(character)));
		}
		return result.append('$').toString();
	}
}
