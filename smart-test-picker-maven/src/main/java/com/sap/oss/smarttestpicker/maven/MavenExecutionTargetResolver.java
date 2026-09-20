// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import org.apache.maven.project.MavenProject;

import com.sap.oss.smarttestpicker.coverage.model.BuildTool;
import com.sap.oss.smarttestpicker.coverage.model.ExecutionTarget;

/** Canonical Maven execution-target derivation shared by inventory and mapping. */
final class MavenExecutionTargetResolver {
	String reactorModuleId(ExecutionTarget target) {
		if (target == null || target.buildTool() != BuildTool.MAVEN)
			throw new IllegalArgumentException("Not a Maven execution target: " + target);
		String id = target.targetId();
		int qualifier = id.indexOf('@');
		return qualifier < 0 ? id : id.substring(0, qualifier);
	}

	ExecutionTarget resolve(File reactorRoot, MavenProject module) {
		return resolve(reactorRoot, module, null, null, null);
	}

	ExecutionTarget resolve(File reactorRoot, MavenProject module, String executionType, String executionId,
			String profile) {
		if (reactorRoot == null) throw new IllegalArgumentException("Maven reactor root is required");
		if (module == null || module.getBasedir() == null)
			throw new IllegalArgumentException("Maven module base directory is required");
		try {
			Path root = reactorRoot.getCanonicalFile().toPath().normalize();
			Path base = module.getBasedir().getCanonicalFile().toPath().normalize();
			if (!base.startsWith(root))
				throw new IllegalArgumentException("Maven module is outside reactor root: " + base);
			Path relative = root.relativize(base);
			String id = relative.toString().isEmpty() ? "." : relative.toString().replace(File.separatorChar, '/');
			if (executionType != null && !executionType.isBlank()) {
				validateQualifier("execution type", executionType);
				validateQualifier("execution ID", executionId);
				id += "@" + executionType + "@" + executionId;
				if (profile != null && !profile.isBlank()) {
					validateQualifier("profile", profile);
					id += "@" + profile;
				}
			}
			ExecutionTarget target = new ExecutionTarget(BuildTool.MAVEN, id);
			if (!target.equals(ExecutionTarget.parse(target.toString())))
				throw new IllegalArgumentException("Maven execution target does not round-trip: " + target);
			return target;
		} catch (IOException failure) {
			throw new IllegalArgumentException("Cannot resolve canonical Maven module base directory", failure);
		}
	}

	private static void validateQualifier(String label, String value) {
		if (value == null || !value.matches("[A-Za-z0-9_.-]+"))
			throw new IllegalArgumentException("Malformed Maven " + label + ": " + value);
	}
}
