// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.plugin.MojoExecutionException;

/** Version boundary for the two entry points that use qualified provider preparation. */
final class MavenMappingVersion {
    private MavenMappingVersion() { }

    static void requireSupported(String goal, org.apache.maven.rtinfo.RuntimeInformation runtime) throws MojoExecutionException {
        String running = runtime.getMavenVersion();
        if (running == null) throw new MojoExecutionException("Cannot determine running Maven version for "
                + goal + "; schema-v3 inventory and preparation require Maven 3.9 or newer");
        var version = new DefaultArtifactVersion(running);
        if (version.getMajorVersion() < 3 || (version.getMajorVersion() == 3 && version.getMinorVersion() < 9)) {
            throw new MojoExecutionException("Running Maven " + running + " cannot execute " + goal
                    + ": schema-v3 inventory and preparation require Maven 3.9 or newer (qualified on Maven 3.9.15)");
        }
    }
}
