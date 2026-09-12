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
	ExecutionTarget resolve(File reactorRoot, MavenProject module) {
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
			ExecutionTarget target = new ExecutionTarget(BuildTool.MAVEN, id);
			if (!target.equals(ExecutionTarget.parse(target.toString())))
				throw new IllegalArgumentException("Maven execution target does not round-trip: " + target);
			return target;
		} catch (IOException failure) {
			throw new IllegalArgumentException("Cannot resolve canonical Maven module base directory", failure);
		}
	}
}
