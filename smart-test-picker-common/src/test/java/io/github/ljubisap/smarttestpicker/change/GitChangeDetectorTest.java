// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker.change;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;


/**
 * Tests for {@link GitChangeDetector} — verifies change detection at class and method level
 * using a real temporary Git repository (init, commit, modify, diff).
 *
 * <p>Each test creates commits in a temp repo and validates that the detector correctly
 * identifies changed classes, methods, branches, commit SHAs, and commit distances.</p>
 */
class GitChangeDetectorTest
{

	@TempDir
	Path tempDir;

	private GitChangeDetector detector;

	@BeforeEach
	void setUp() throws Exception
	{
		// Initialize a real git repo in tempDir
		run("git", "init");
		run("git", "config", "user.email", "test@test.com");
		run("git", "config", "user.name", "Test");

		// Create initial commit with a Java file
		Path srcDir = tempDir.resolve("src/main/java/org/example");
		Files.createDirectories(srcDir);
		Files.writeString(srcDir.resolve("Foo.java"), "package org.example; class Foo {}");
		Files.writeString(srcDir.resolve("Bar.java"), "package org.example; class Bar {}");

		run("git", "add", ".");
		run("git", "commit", "-m", "initial");

		detector = new GitChangeDetector(tempDir.toFile());
	}

	@Test
	void getHeadCommitIdReturnsValidSha()
	{
		String sha = detector.getHeadCommitId();
		assertNotNull(sha);
		assertEquals(40, sha.length());
	}

	@Test
	void getCurrentBranchReturnsBranchName()
	{
		String branch = detector.getCurrentBranch();
		assertTrue(branch.equals("main") || branch.equals("master"));
	}

	@Test
	void isValidCommitReturnsTrueForHead()
	{
		String sha = detector.getHeadCommitId();
		assertTrue(detector.isValidCommit(sha));
	}

	@Test
	void isValidCommitReturnsFalseForInvalidSha()
	{
		assertFalse(detector.isValidCommit("0000000000000000000000000000000000000000"));
	}

	@Test
	void getChangedClassesDetectsModifiedFile() throws Exception
	{
		String baseCommit = detector.getHeadCommitId();

		// Modify a file and commit
		Path fooFile = tempDir.resolve("src/main/java/org/example/Foo.java");
		Files.writeString(fooFile, "package org.example; class Foo { int x; }");
		run("git", "add", ".");
		run("git", "commit", "-m", "modify Foo");

		var changed = detector.getChangedClasses(baseCommit);
		assertTrue(changed.contains("org.example.Foo"));
		assertFalse(changed.contains("org.example.Bar"));
	}

	@Test
	void getChangedClassesReturnsEmptyForNoChanges() throws Exception
	{
		String baseCommit = detector.getHeadCommitId();
		var changed = detector.getChangedClasses(baseCommit);
		assertTrue(changed.isEmpty());
	}

	@Test
	void getChangedClassesIgnoresTestFiles() throws Exception
	{
		String baseCommit = detector.getHeadCommitId();

		// Add a test file
		Path testDir = tempDir.resolve("src/test/java/org/example");
		Files.createDirectories(testDir);
		Files.writeString(testDir.resolve("FooTest.java"), "package org.example; class FooTest {}");
		run("git", "add", ".");
		run("git", "commit", "-m", "add test");

		var changed = detector.getChangedClasses(baseCommit);
		assertTrue(changed.isEmpty());
	}

	@Test
	void getChangedClassesIgnoresNonJavaFiles() throws Exception
	{
		String baseCommit = detector.getHeadCommitId();

		Files.writeString(tempDir.resolve("README.md"), "hello");
		run("git", "add", ".");
		run("git", "commit", "-m", "add readme");

		var changed = detector.getChangedClasses(baseCommit);
		assertTrue(changed.isEmpty());
	}

	@Test
	void getCommitDistanceReturnsCorrectCount() throws Exception
	{
		String baseCommit = detector.getHeadCommitId();

		Path fooFile = tempDir.resolve("src/main/java/org/example/Foo.java");
		Files.writeString(fooFile, "package org.example; class Foo { int x; }");
		run("git", "add", ".");
		run("git", "commit", "-m", "commit 1");

		Files.writeString(fooFile, "package org.example; class Foo { int x; int y; }");
		run("git", "add", ".");
		run("git", "commit", "-m", "commit 2");

		assertEquals(2, detector.getCommitDistance(baseCommit));
	}

	@Test
	void getChangedClassesDetectsUncommittedChanges() throws Exception
	{
		String baseCommit = detector.getHeadCommitId();

		// Modify a file but do NOT commit
		Path fooFile = tempDir.resolve("src/main/java/org/example/Foo.java");
		Files.writeString(fooFile, "package org.example; class Foo { int x; }");

		var changed = detector.getChangedClasses(baseCommit);
		assertTrue(changed.contains("org.example.Foo"));
	}

	@Test
	void getChangedClassesDetectsStagedChanges() throws Exception
	{
		String baseCommit = detector.getHeadCommitId();

		// Modify and stage but do NOT commit
		Path barFile = tempDir.resolve("src/main/java/org/example/Bar.java");
		Files.writeString(barFile, "package org.example; class Bar { String s; }");
		run("git", "add", ".");

		var changed = detector.getChangedClasses(baseCommit);
		assertTrue(changed.contains("org.example.Bar"));
	}

	@Test
	void pathToFqnConvertsCorrectly()
	{
		assertEquals("org.example.Foo", detector.pathToFqn("src/main/java/org/example/Foo.java"));
		assertEquals("com.acme.service.UserService",
				detector.pathToFqn("src/main/java/com/acme/service/UserService.java"));
		assertNull(detector.pathToFqn("src/test/java/org/example/FooTest.java"));
		assertNull(detector.pathToFqn("README.md"));
	}

	@Test
	void pathToFqnSupportsNonStandardSourceRoots()
	{
		assertEquals("com.sap.cx.commerce.Foo",
				detector.pathToFqn("bin/platform/ext/core/src/com/sap/cx/commerce/Foo.java"));
		assertEquals("com.example.MyService",
				detector.pathToFqn("modules/myext/src/com/example/MyService.java"));
		assertNull(detector.pathToFqn("ext/core/testsrc/com/example/FooTest.java"));
		assertNull(detector.pathToFqn("src/main/resources/application.properties"));
	}

	@Test
	void getChangedClassesDetectsNonStandardSourceRoot() throws Exception
	{
		String baseCommit = detector.getHeadCommitId();

		Path nonStdDir = tempDir.resolve("ext/core/src/com/sap/example");
		Files.createDirectories(nonStdDir);
		Files.writeString(nonStdDir.resolve("MyService.java"),
				"package com.sap.example; class MyService {}");
		run("git", "add", ".");
		run("git", "commit", "-m", "add non-standard source");

		var changed = detector.getChangedClasses(baseCommit);
		assertTrue(changed.contains("com.sap.example.MyService"));
	}

	@Test
	void extractMethodNameFromSignature()
	{
		assertEquals("getName", detector.extractMethodName("public String getName() {"));
		assertEquals("update", detector.extractMethodName("public void update(String value) {"));
		assertEquals("process", detector.extractMethodName("private int process(List<String> items) {"));
		assertEquals("init", detector.extractMethodName("protected void init() {"));
		assertNull(detector.extractMethodName("public class Foo {"));
		assertNull(detector.extractMethodName(""));
		assertNull(detector.extractMethodName(null));
	}

	@Test
	void getChangedMethodsDetectsModifiedMethods() throws Exception
	{
		// Rewrite Foo.java with proper methods
		Path fooFile = tempDir.resolve("src/main/java/org/example/Foo.java");
		Files.writeString(fooFile, """
				package org.example;

				public class Foo {

				    public void hello() {
				        System.out.println("hello");
				    }

				    public String getName() {
				        return "name";
				    }
				}
				""");
		run("git", "add", ".");
		run("git", "commit", "-m", "add methods");

		// Enable Java diff driver
		Files.writeString(tempDir.resolve(".gitattributes"), "*.java diff=java\n");
		run("git", "add", ".");
		run("git", "commit", "-m", "add gitattributes");

		String baseCommit = detector.getHeadCommitId();

		// Modify only getName
		Files.writeString(fooFile, """
				package org.example;

				public class Foo {

				    public void hello() {
				        System.out.println("hello");
				    }

				    public String getName() {
				        return "updated";
				    }
				}
				""");
		run("git", "add", ".");
		run("git", "commit", "-m", "modify getName");

		var methods = detector.getChangedMethods(baseCommit);
		assertTrue(methods.contains("org.example.Foo#getName"));
		assertFalse(methods.contains("org.example.Foo#hello"));
	}

	@Test
	void getChangedMethodsDetectsUncommittedMethodChanges() throws Exception
	{
		// Rewrite Foo.java with proper methods
		Path fooFile = tempDir.resolve("src/main/java/org/example/Foo.java");
		Files.writeString(fooFile, """
				package org.example;

				public class Foo {

				    public void hello() {
				        System.out.println("hello");
				    }

				    public String getName() {
				        return "name";
				    }
				}
				""");
		// Enable Java diff driver
		Files.writeString(tempDir.resolve(".gitattributes"), "*.java diff=java\n");
		run("git", "add", ".");
		run("git", "commit", "-m", "add methods + gitattributes");

		String baseCommit = detector.getHeadCommitId();

		// Modify getName but do NOT commit
		Files.writeString(fooFile, """
				package org.example;

				public class Foo {

				    public void hello() {
				        System.out.println("hello");
				    }

				    public String getName() {
				        return "changed";
				    }
				}
				""");

		var methods = detector.getChangedMethods(baseCommit);
		assertTrue(methods.contains("org.example.Foo#getName"));
		assertFalse(methods.contains("org.example.Foo#hello"));
	}

	@Test
	void getChangedMethodsReturnsEmptyForNoChanges() throws Exception
	{
		String baseCommit = detector.getHeadCommitId();
		var methods = detector.getChangedMethods(baseCommit);
		assertTrue(methods.isEmpty());
	}

	@Test
	void getChangedFilesReturnsAllFileTypes() throws Exception
	{
		String baseCommit = detector.getHeadCommitId();

		// Add various file types
		Path resourceDir = tempDir.resolve("src/main/resources");
		Files.createDirectories(resourceDir);
		Files.writeString(resourceDir.resolve("items.xml"), "<items/>");
		Files.writeString(resourceDir.resolve("data.impex"), "INSERT_UPDATE");
		Files.writeString(tempDir.resolve("README.md"), "readme");

		run("git", "add", ".");
		run("git", "commit", "-m", "add various files");

		var changedFiles = detector.getChangedFiles(baseCommit);
		assertTrue(changedFiles.contains("src/main/resources/items.xml"));
		assertTrue(changedFiles.contains("src/main/resources/data.impex"));
		assertTrue(changedFiles.contains("README.md"));
	}

	@Test
	void getChangedFilesDetectsUncommittedFiles() throws Exception
	{
		String baseCommit = detector.getHeadCommitId();

		// Modify existing file without committing
		Path fooFile = tempDir.resolve("src/main/java/org/example/Foo.java");
		Files.writeString(fooFile, "package org.example; class Foo { int x; }");

		// Add new file without committing
		Path resourceDir = tempDir.resolve("src/main/resources");
		Files.createDirectories(resourceDir);
		Files.writeString(resourceDir.resolve("spring-config.xml"), "<beans/>");
		run("git", "add", ".");

		var changedFiles = detector.getChangedFiles(baseCommit);
		assertTrue(changedFiles.contains("src/main/java/org/example/Foo.java"));
		assertTrue(changedFiles.contains("src/main/resources/spring-config.xml"));
	}

	@Test
	void getChangedFilesReturnsEmptyForNoChanges() throws Exception
	{
		String baseCommit = detector.getHeadCommitId();
		var changedFiles = detector.getChangedFiles(baseCommit);
		assertTrue(changedFiles.isEmpty());
	}

	private void run(String... args) throws IOException, InterruptedException
	{
		ProcessBuilder pb = new ProcessBuilder(args);
		pb.directory(tempDir.toFile());
		pb.redirectErrorStream(true);
		Process p = pb.start();
		p.getInputStream().readAllBytes();
		int exit = p.waitFor();
		if (exit != 0)
			throw new RuntimeException("Command failed: " + String.join(" ", args));
	}
}
