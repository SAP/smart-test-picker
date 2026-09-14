// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import com.sap.oss.smarttestpicker.coverage.model.ExecutableCoverageFragment;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableUnmappedTest;
import com.sap.oss.smarttestpicker.coverage.model.SetupScope;
import com.sap.oss.smarttestpicker.coverage.model.TestCoverage;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableCoverageFragmentCodec;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableShardAssignmentCodec;
import com.sap.oss.smarttestpicker.coverage.validation.ExecutableFragmentAssignmentValidator;

/** Adapter aggregation: one shard artifact from collision-free per-Test-task fragments. */
@DisableCachingByDefault(because = "Input fragment topology is configured from concrete Test tasks")
public abstract class AggregateGradleExecutableCoverageTask extends DefaultTask {
	private final List<File> fragments = new ArrayList<>();
	private File assignmentFile;
	@Internal public List<File> getFragments() { return fragments; }
	@Internal public File getAssignmentFile() { return assignmentFile; }
	public void setAssignmentFile(File assignmentFile) { this.assignmentFile = assignmentFile; }
	@OutputFile public abstract RegularFileProperty getOutputFile();

	@TaskAction public void aggregate() {
		try {
			var codec = new ExecutableCoverageFragmentCodec();
			var assignment = new ExecutableShardAssignmentCodec().deserialize(Files.readAllBytes(assignmentFile.toPath()));
			Map<ExecutableTestIdentity, TestCoverage> mapped = new TreeMap<>();
			Map<ExecutableTestIdentity, ExecutableUnmappedTest> unmapped = new TreeMap<>();
			Map<String, SetupScope> scopes = new TreeMap<>();
			for (File file : fragments) {
				ExecutableCoverageFragment fragment = codec.deserialize(Files.readAllBytes(file.toPath()));
				if (!fragment.revision().equals(assignment.revision()) || !fragment.shardId().equals(assignment.shardId()))
					throw new GradleException("Gradle Test-task fragment binding mismatch: " + file);
				if (!fragment.collectionCompleted()) throw new GradleException("Incomplete Gradle Test-task fragment: " + file);
				fragment.tests().forEach((identity, coverage) -> {
					if (mapped.putIfAbsent(identity, coverage) != null || unmapped.containsKey(identity))
						throw new GradleException("Duplicate executable identity across Gradle Test tasks: " + identity);
				});
				fragment.unmapped().forEach(value -> {
					if (mapped.containsKey(value.test()) || unmapped.putIfAbsent(value.test(), value) != null)
						throw new GradleException("Duplicate executable identity across Gradle Test tasks: " + value.test());
				});
				fragment.setupScopes().forEach(scope -> {
					String key = scope.owner() + "::" + scope.id();
					if (scope.owner() == null) throw new GradleException("Executable setup scope is missing owner: " + scope.id());
					if (scopes.putIfAbsent(key, scope) != null)
						throw new GradleException("Duplicate setup scope across Gradle Test tasks: " + key);
				});
			}
			ExecutableCoverageFragment result = new ExecutableCoverageFragment(3, assignment.revision(),
					assignment.shardId(), mapped, List.copyOf(unmapped.values()), List.copyOf(scopes.values()), true);
			ExecutableFragmentAssignmentValidator.validate(result, assignment);
			File output = getOutputFile().get().getAsFile(); output.getParentFile().mkdirs();
			Files.write(output.toPath(), codec.serialize(result));
		} catch (Exception failure) {
			throw failure instanceof GradleException gradle ? gradle
					: new GradleException("Cannot aggregate Gradle executable coverage fragment", failure);
		}
	}
}
