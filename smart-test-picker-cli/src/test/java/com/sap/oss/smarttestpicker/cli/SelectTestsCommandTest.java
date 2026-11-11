// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.cli;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import com.google.gson.Gson;
import com.sap.oss.smarttestpicker.coverage.model.*;
import com.sap.oss.smarttestpicker.coverage.serialization.CoverageMapCodec;
import com.sap.oss.smarttestpicker.selector.SelectionOutput;
import picocli.CommandLine;

class SelectTestsCommandTest {
	@TempDir Path temporary;
	private Path project, map, inventory;
	private final TestIdentity selected = new TestIdentity("com.example.FooTest", "works", "java.lang.String");

	@BeforeEach void setUp() throws Exception {
		project = temporary.resolve("project"); Files.createDirectories(project);
		run("git", "init", "-q"); run("git", "config", "user.email", "test@example.com"); run("git", "config", "user.name", "Test");
		write("src/main/java/com/example/Foo.java", "package com.example; class Foo {}"); commit("base");
		String revision = output("git", "rev-parse", "HEAD");
		write("src/main/java/com/example/Foo.java", "package com.example; class Foo { int x; }"); commit("change");
		map = project.resolve("map.json"); Files.write(map, new CoverageMapCodec().serialize(validMap(revision)));
		inventory = project.resolve("inventory.json"); Files.writeString(inventory, new Gson().toJson(List.of(selected.toString())));
	}

	@Test void jsonSelectedContainsCompleteMandatorySet() throws Exception {
		Path target = temporary.resolve("selected.json"); assertEquals(0, execute(target, "json", map, inventory));
		SelectionOutput result = new Gson().fromJson(Files.readString(target), SelectionOutput.class);
		assertEquals("SELECTED", result.getStatus()); assertEquals(List.of(selected.toString()), result.getSelectedTests());
	}

	@Test void jsonNoneIsExactEmpty() throws Exception {
		String head = output("git", "rev-parse", "HEAD"); Path candidate = project.resolve("none.json");
		Files.write(candidate, new CoverageMapCodec().serialize(validMap(head)));
		Path target = temporary.resolve("none-output.json"); assertEquals(0, execute(target, "json", candidate, inventory));
		SelectionOutput result = new Gson().fromJson(Files.readString(target), SelectionOutput.class);
		assertEquals("NONE", result.getStatus()); assertEquals(List.of(), result.getSelectedTests());
	}

	@Test void jsonMissingInventoryAndUnsafeMapFailOpen() throws Exception {
		Path first = temporary.resolve("missing-inventory.json"); assertEquals(0, execute(first, "json", map, null));
		assertEquals("FULL_SUITE", new Gson().fromJson(Files.readString(first), SelectionOutput.class).getStatus());
		Path corrupt = project.resolve("corrupt.json"); Files.writeString(corrupt, "not-json");
		Path second = temporary.resolve("unsafe.json"); assertEquals(0, execute(second, "json", corrupt, inventory));
		assertEquals("FULL_SUITE", new Gson().fromJson(Files.readString(second), SelectionOutput.class).getStatus());
	}

	@Test void txtIsExplicitAndAntRejectsFullSuite() throws Exception {
		Path txt = temporary.resolve("all.txt"), ant = temporary.resolve("all.ant");
		assertEquals(0, execute(txt, "txt", map, null)); assertEquals("FULL_SUITE\n", Files.readString(txt));
		assertEquals(1, execute(ant, "ant", map, null)); assertFalse(Files.exists(ant));
	}

	@Test void txtAndAntSelectedUseSelectedTestsOnly() throws Exception {
		Path txt = temporary.resolve("selected.txt"), ant = temporary.resolve("selected.ant");
		assertEquals(0, execute(txt, "txt", map, inventory)); assertEquals(List.of(selected.toString()), Files.readAllLines(txt));
		assertEquals(0, execute(ant, "ant", map, inventory)); assertEquals(selected.toString(), Files.readString(ant));
	}

	private int execute(Path target, String format, Path candidate, Path headInventory) {
		ArrayList<String> args = new ArrayList<>(List.of("--map", candidate.toString(), "--project-dir", project.toString(), "--output", target.toString(), "--format", format));
		if (headInventory != null) args.addAll(List.of("--head-inventory", headInventory.toString()));
		return new CommandLine(new SelectTestsCommand()).execute(args.toArray(String[]::new));
	}

	private CoverageMap validMap(String revision) {
		ShardId shard = new ShardId("one"); Set<TestIdentity> tests = Set.of(selected);
		Completeness complete = new Completeness(tests, tests, Set.of(shard), Set.of(shard), Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
		return new CoverageMap(2, new CoverageMapRevision(revision), Instant.EPOCH, new GeneratorProvenance("test", "test", "test", "17"),
				Map.of(selected, new TestCoverage(Set.of("com.example.Foo"), Set.of(), TestOutcome.PASS, CollectionStatus.COLLECTED_WITH_COVERAGE)),
				List.of(), List.of(), complete, new MapStatistics(1, 1, 0, 0, 1, 0), CoverageMapLifecycleState.PUBLISHED, null);
	}
	private void write(String relative, String value) throws Exception { Path path = project.resolve(relative); Files.createDirectories(path.getParent()); Files.writeString(path, value); }
	private void commit(String message) throws Exception { run("git", "add", "."); run("git", "commit", "-q", "-m", message); }
	private String output(String... command) throws Exception { return process(command); }
	private void run(String... command) throws Exception { process(command); }
	private String process(String... command) throws Exception { Process process = new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true).start(); String text = new String(process.getInputStream().readAllBytes()).trim(); if (process.waitFor() != 0) throw new AssertionError(String.join(" ", command) + ": " + text); return text; }
}
