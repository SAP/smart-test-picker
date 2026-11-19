// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import org.gradle.api.GradleException;
import org.gradle.api.Task;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.testing.Test;

/** Execution policy for live mapping observations, never for ordinary test invocations. */
final class GradleMappingExecution {
	private static final String PREPARATION = "stpMappingPreparation";

	static void install(Test test) {
		if (test.getExtensions().findByName(PREPARATION) == null) {
			Preparation preparation = new Preparation();
			test.getExtensions().add(PREPARATION, preparation);
			// Register before build-script doFirst actions, so those run before our final checks.
			test.doFirst(preparation);
		}
	}

	static Preparation preparation(Test test) {
		return (Preparation) test.getExtensions().getByName(PREPARATION);
	}

	static void fresh(Task task) {
		task.getOutputs().upToDateWhen(ignored -> false);
		task.getOutputs().doNotCacheIf("Mapping requires fresh runtime observations", ignored -> true);
	}

	static void clearOutputDirectory(java.io.File directory) {
		try {
			if (directory.isDirectory()) {
				try (var files = java.nio.file.Files.walk(directory.toPath())) {
					for (var file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
						java.nio.file.Files.delete(file);
					}
				}
			}
			java.nio.file.Files.createDirectories(directory.toPath());
		} catch (java.io.IOException failure) {
			throw new GradleException("Cannot invalidate stale mapping output: " + directory, failure);
		}
	}

	static void configure(Test test, String collector, Provider<String> frozenCollector) {
		fresh(test);
		test.getInputs().property("stp.mappingCollector", frozenCollector.orElse(""));
		Preparation preparation = preparation(test);
		preparation.collector = collector;
		preparation.frozenCollector = frozenCollector;
		test.doLast(current -> {
			Test executed = (Test) current;
			if (executed.getForkEvery() != 0 || executed.getMaxParallelForks() != 1) {
				throw new GradleException("Mapping task " + executed.getPath()
						+ " changed the validated single-JVM settings: forkEvery=" + executed.getForkEvery()
						+ ", maxParallelForks=" + executed.getMaxParallelForks()
						+ "; required forkEvery=0, maxParallelForks=1");
			}
		});
	}

	static final class Preparation implements org.gradle.api.Action<Task> {
		private String collector;
		private Provider<String> frozenCollector;
		AsmAgentArgumentProvider scope;

		@Override public void execute(Task current) {
			if (collector == null) return;
			Test test = (Test) current;
			String effective = String.valueOf(test.getSystemProperties().get(StpCollectorSelection.PROPERTY));
			if (!collector.equals(effective)) {
				throw new GradleException("Effective collector=" + effective + " conflicts with attached collector="
						+ collector + " for " + test.getPath());
			}
			String frozen = frozenCollector.getOrElse("").trim();
			if (!frozen.isEmpty() && !frozen.equalsIgnoreCase(collector)) {
				throw new GradleException("STP_MAPPING_COLLECTOR=" + frozen + " conflicts with effective collector="
						+ collector + " for " + current.getPath());
			}
			if (test.getForkEvery() > 0) {
				throw new GradleException(collector + " mapping task " + test.getPath()
						+ " requires forkEvery=0; forkEvery=" + test.getForkEvery());
			}
			if (test.getMaxParallelForks() != 1) {
				test.getLogger().lifecycle("{} mapping task {} overrides maxParallelForks={} with 1",
						collector, test.getPath(), test.getMaxParallelForks());
				test.setMaxParallelForks(1);
			}
			if (collector.equals("ASM")) {
				scope.prepareScope();
			}
		}
	}
}
