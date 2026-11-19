// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import com.sap.oss.smarttestpicker.coverage.model.ExecutionTarget;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableShardAssignmentCodec;

/** Checks all targets before any mapped Test task starts. */
@DisableCachingByDefault(because = "Selection must be observed on every mapping invocation")
public abstract class ValidateGradleExecutableSelectionTask extends DefaultTask {
    private final List<GradleTestSelection> selections = new ArrayList<>();
    @Nested public List<GradleTestSelection> getSelections() { return selections; }
    @InputFile public abstract RegularFileProperty getAssignmentFile();
    @org.gradle.api.tasks.Internal public abstract RegularFileProperty getAggregateOutput();

    @TaskAction public void validateSelection() throws Exception {
        AsmCoverageCollectorBackend.deleteStale(getAggregateOutput().get().getAsFile(), "aggregate before selection validation");
        var assignment = new ExecutableShardAssignmentCodec().deserialize(
                Files.readAllBytes(getAssignmentFile().get().getAsFile().toPath()));
        for (var selection : selections) {
            var target = ExecutionTarget.parse("gradle:" + selection.getTaskPath().get());
            var assigned = new TreeSet<com.sap.oss.smarttestpicker.coverage.model.TestIdentity>();
            assignment.tests().stream().filter(id -> id.target().equals(target)).forEach(id -> assigned.add(id.test()));
            if (assigned.isEmpty()) continue;
            assigned.removeAll(selection.discover());
            if (!assigned.isEmpty()) throw new GradleException("Gradle mapping task " + selection.getTaskPath().get()
                    + " has assigned identities outside its effective test selection: " + assigned);
        }
    }
}
