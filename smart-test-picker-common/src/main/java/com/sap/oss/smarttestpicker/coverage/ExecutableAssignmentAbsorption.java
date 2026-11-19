// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableCoverageFragment;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableUnmappedTest;
import com.sap.oss.smarttestpicker.coverage.model.UnmappedReason;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableCoverageFragmentCodec;

/** Shared ASM finalization policy: absent assigned owners are mandatory, not observed executions. */
public final class ExecutableAssignmentAbsorption {

	private ExecutableAssignmentAbsorption() {}

	/**
	 * Computes normalization without writing. Callers must first validate diagnostic, target and
	 * revision/shard binding, then validate the returned fragment's accounting and allowed outcomes
	 * before committing it. An incomplete fragment is never repaired.
	 */
	public static Result apply(ExecutableCoverageFragment fragment, Set<ExecutableTestIdentity> assigned,
			JsonObject input) {
		if (!fragment.collectionCompleted()) throw new IllegalArgumentException("Fragment collection is not completed");
		Set<ExecutableTestIdentity> reported = new TreeSet<>(fragment.tests().keySet());
		fragment.unmapped().forEach(entry -> reported.add(entry.test()));
		Set<ExecutableTestIdentity> unexpected = new TreeSet<>(reported); unexpected.removeAll(assigned);
		if (!unexpected.isEmpty()) throw new IllegalArgumentException("Fragment reports identities outside assignment: " + unexpected);
		Set<ExecutableTestIdentity> absorbed = new TreeSet<>(assigned); absorbed.removeAll(reported);
		JsonObject audit = audit(input, fragment, absorbed);
		if (!absorbed.isEmpty()) {
			var unmapped = new ArrayList<>(fragment.unmapped());
			absorbed.forEach(id -> unmapped.add(new ExecutableUnmappedTest(id, UnmappedReason.COLLECTION_FAILED)));
			fragment = new ExecutableCoverageFragment(fragment.schemaVersion(), fragment.revision(), fragment.shardId(),
					fragment.tests(), unmapped, fragment.setupScopes(), true);
		}
		return new Result(fragment, Collections.unmodifiableSet(new TreeSet<>(absorbed)), audit);
	}

	private static JsonObject audit(JsonObject input, ExecutableCoverageFragment fragment,
			Set<ExecutableTestIdentity> newlyAbsorbed) {
		Set<ExecutableTestIdentity> all = new TreeSet<>();
		if (input.has("assignmentAbsorption")) {
			var previous = input.getAsJsonObject("assignmentAbsorption");
			if (!"ASSIGNED_TEST_NOT_REPORTED".equals(previous.get("reason").getAsString()))
				throw new IllegalArgumentException("Invalid ASM assignment absorption reason");
			for (var value : previous.getAsJsonArray("tests")) {
				var id = ExecutableTestIdentity.parse(value.getAsString());
				if (!all.add(id) || fragment.unmapped().stream().noneMatch(entry -> entry.test().equals(id)
						&& entry.reason() == UnmappedReason.COLLECTION_FAILED))
					throw new IllegalArgumentException("Invalid ASM assignment absorption identity: " + id);
			}
			if (previous.get("count").getAsInt() != all.size())
				throw new IllegalArgumentException("Invalid ASM assignment absorption count");
		}
		all.addAll(newlyAbsorbed);
		if (all.isEmpty()) return null;
		JsonObject result = new JsonObject(); result.addProperty("reason", "ASSIGNED_TEST_NOT_REPORTED");
		result.addProperty("executionObserved", false);
		result.addProperty("count", all.size());
		JsonArray tests = new JsonArray(); all.forEach(id -> tests.add(id.toString())); result.add("tests", tests);
		return result;
	}

	/** A proposed normalization; writing remains a separate step after all acceptance checks pass. */
	public record Result(ExecutableCoverageFragment fragment, Set<ExecutableTestIdentity> absorbed, JsonObject audit) {
		/** Adds the same audit to either a fragment or a build-tool evidence document. */
		public String withAudit(String json) {
			if (audit == null) return json;
			var value = JsonParser.parseString(json).getAsJsonObject();
			value.add("assignmentAbsorption", audit);
			return new GsonBuilder().setPrettyPrinting().create().toJson(value) + "\n";
		}

		/** Leaves original bytes untouched when there are no newly absorbed identities. */
		public void writeIfChanged(Path output) throws IOException {
			if (absorbed.isEmpty()) return;
			String normalized = withAudit(new String(new ExecutableCoverageFragmentCodec().serialize(fragment), StandardCharsets.UTF_8));
			Path temporary = Files.createTempFile(output.toAbsolutePath().getParent(), ".asm-normalized-", ".tmp");
			try {
				Files.writeString(temporary, normalized);
				try { Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
				catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING); }
			} finally { Files.deleteIfExists(temporary); }
		}
	}
}
