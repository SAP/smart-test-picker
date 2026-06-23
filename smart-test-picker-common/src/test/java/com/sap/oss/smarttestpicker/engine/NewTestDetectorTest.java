// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.engine;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sap.oss.smarttestpicker.change.GitChangeDetector;
import com.sap.oss.smarttestpicker.mapper.CoverageMap;
import com.sap.oss.smarttestpicker.mapper.CoverageMapMetadata;

import static org.junit.jupiter.api.Assertions.*;


class NewTestDetectorTest
{

	@TempDir
	Path tempDir;

	private File projectDir;
	private File testClassesDir;
	private GitChangeDetector git;
	private EngineLogger logger;

	@BeforeEach
	void setUp() throws Exception
	{
		projectDir = tempDir.resolve("project").toFile();
		projectDir.mkdirs();

		run("git", "init");
		run("git", "config", "user.email", "test@test.com");
		run("git", "config", "user.name", "Test");
		Files.writeString(tempDir.resolve("project/dummy.txt"), "init");
		run("git", "add", ".");
		run("git", "commit", "-m", "initial");

		testClassesDir = tempDir.resolve("test-classes").toFile();
		testClassesDir.mkdirs();

		git = new GitChangeDetector(projectDir);
		logger = new NoOpLogger();
	}

	@Test
	void detectsNewTestNotInMap() throws IOException
	{
		Path classFile = testClassesDir.toPath().resolve("com/example/NewTest.class");
		Files.createDirectories(classFile.getParent());
		Files.write(classFile, new byte[]{0});

		CoverageMap map = buildMap("ExistingTest#test1");

		NewTestDetector detector = new NewTestDetector();
		Map<String, String> result = detector.detect(map, git, null, testClassesDir, logger);

		assertTrue(result.containsKey("com.example.NewTest"));
	}

	@Test
	void returnsEmptyWhenAllTestsInMap() throws IOException
	{
		Path classFile = testClassesDir.toPath().resolve("com/example/OwnerControllerTests.class");
		Files.createDirectories(classFile.getParent());
		Files.write(classFile, new byte[]{0});

		CoverageMap map = buildMap("OwnerControllerTests#testShowOwner");

		NewTestDetector detector = new NewTestDetector();
		Map<String, String> result = detector.detect(map, git, null, testClassesDir, logger);

		assertTrue(result.isEmpty());
	}

	@Test
	void returnsEmptyWhenDirNotExists()
	{
		File nonexistent = new File(tempDir.toFile(), "does-not-exist");
		CoverageMap map = buildMap("Test#method");

		NewTestDetector detector = new NewTestDetector();
		Map<String, String> result = detector.detect(map, git, null, nonexistent, logger);

		assertTrue(result.isEmpty());
	}

	@Test
	void skipsInnerClasses() throws IOException
	{
		Path outerClass = testClassesDir.toPath().resolve("com/example/MyTest.class");
		Path innerClass = testClassesDir.toPath().resolve("com/example/MyTest$Inner.class");
		Files.createDirectories(outerClass.getParent());
		Files.write(outerClass, new byte[]{0});
		Files.write(innerClass, new byte[]{0});

		CoverageMap map = buildMap("SomeOther#test");

		NewTestDetector detector = new NewTestDetector();
		Map<String, String> result = detector.detect(map, git, null, testClassesDir, logger);

		assertTrue(result.containsKey("com.example.MyTest"));
		assertFalse(result.containsKey("com.example.MyTest$Inner"));
	}

	@Test
	void classifiesAddedTest() throws Exception
	{
		String commitId = git.getHeadCommitId();

		Path testFile = tempDir.resolve("project/src/test/java/com/example/BrandNewTest.java");
		Files.createDirectories(testFile.getParent());
		Files.writeString(testFile, "package com.example; class BrandNewTest {}");
		run("git", "add", ".");
		run("git", "commit", "-m", "add new test");

		Path classFile = testClassesDir.toPath().resolve("com/example/BrandNewTest.class");
		Files.createDirectories(classFile.getParent());
		Files.write(classFile, new byte[]{0});

		CoverageMap map = buildMap("ExistingTest#test1");

		NewTestDetector detector = new NewTestDetector();
		Map<String, String> result = detector.detect(map, git, commitId, testClassesDir, logger);

		assertTrue(result.containsKey("com.example.BrandNewTest"));
		assertTrue(result.get("com.example.BrandNewTest").contains("New test"));
	}

	@Test
	void classifiesModifiedTest() throws Exception
	{
		Path testFile = tempDir.resolve("project/src/test/java/com/example/ModifiedTest.java");
		Files.createDirectories(testFile.getParent());
		Files.writeString(testFile, "package com.example; class ModifiedTest {}");
		run("git", "add", ".");
		run("git", "commit", "-m", "add test");

		String commitId = git.getHeadCommitId();

		Files.writeString(testFile, "package com.example; class ModifiedTest { int x; }");
		run("git", "add", ".");
		run("git", "commit", "-m", "modify test");

		Path classFile = testClassesDir.toPath().resolve("com/example/ModifiedTest.class");
		Files.createDirectories(classFile.getParent());
		Files.write(classFile, new byte[]{0});

		CoverageMap map = buildMap("ExistingTest#test1");

		NewTestDetector detector = new NewTestDetector();
		Map<String, String> result = detector.detect(map, git, commitId, testClassesDir, logger);

		assertTrue(result.containsKey("com.example.ModifiedTest"));
		assertTrue(result.get("com.example.ModifiedTest").contains("Modified"));
	}

	@Test
	void classifiesUnknownTest() throws IOException
	{
		Path classFile = testClassesDir.toPath().resolve("com/example/UnknownTest.class");
		Files.createDirectories(classFile.getParent());
		Files.write(classFile, new byte[]{0});

		CoverageMap map = buildMap("ExistingTest#test1");
		String commitId = git.getHeadCommitId();

		NewTestDetector detector = new NewTestDetector();
		Map<String, String> result = detector.detect(map, git, commitId, testClassesDir, logger);

		assertTrue(result.containsKey("com.example.UnknownTest"));
		assertEquals("Not in coverage map", result.get("com.example.UnknownTest"));
	}


	private CoverageMap buildMap(String testName)
	{
		Map<String, Map<String, List<String>>> mappings = new HashMap<>();
		mappings.put(testName, Map.of(
				"classes", List.of("com.example.Foo"),
				"methods", List.of("com.example.Foo#bar")
		));
		return new CoverageMap(new CoverageMapMetadata("main", "abc123", "2026-04-11T10:00:00Z"), mappings);
	}

	private void run(String... args) throws IOException, InterruptedException
	{
		ProcessBuilder pb = new ProcessBuilder(args);
		pb.directory(projectDir);
		pb.redirectErrorStream(true);
		Process p = pb.start();
		p.getInputStream().readAllBytes();
		int exit = p.waitFor();
		if (exit != 0)
			throw new RuntimeException("Command failed: " + String.join(" ", args));
	}

	private static class NoOpLogger implements EngineLogger
	{
		@Override
		public void info(String msg, Object... args) {}

		@Override
		public void warn(String msg, Object... args) {}
	}

	// --- Source-based classification tests ---

	@Test
	void classifyFromSource_detectsDisabledTest() throws IOException
	{
		File srcDir = writeTempSource("com/example/FooTest.java",
				"package com.example;\nimport org.junit.jupiter.api.Disabled;\n@Disabled\npublic class FooTest {\n  @Test void m() {}\n}");

		String result = NewTestDetector.classifyFromSource("com/example/FooTest.java", List.of(srcDir));

		assertEquals("All tests @Disabled/@Ignore", result);
	}

	@Test
	void classifyFromSource_detectsIgnoredTest() throws IOException
	{
		File srcDir = writeTempSource("com/example/FooTest.java",
				"package com.example;\nimport org.junit.Ignore;\n@Ignore\npublic class FooTest {\n  @Test void m() {}\n}");

		String result = NewTestDetector.classifyFromSource("com/example/FooTest.java", List.of(srcDir));

		assertEquals("All tests @Disabled/@Ignore", result);
	}

	@Test
	void classifyFromSource_detectsEnclosedRunner() throws IOException
	{
		File srcDir = writeTempSource("com/example/FooTest.java",
				"package com.example;\n@RunWith(Enclosed.class)\npublic class FooTest {\n}");

		String result = NewTestDetector.classifyFromSource("com/example/FooTest.java", List.of(srcDir));

		assertEquals("Enclosed runner \u2014 tests only in inner classes", result);
	}

	@Test
	void classifyFromSource_detectsPackagePrivate() throws IOException
	{
		File srcDir = writeTempSource("com/example/FooTest.java",
				"package com.example;\nclass FooTest {\n  @Test void m() {}\n}");

		String result = NewTestDetector.classifyFromSource("com/example/FooTest.java", List.of(srcDir));

		assertEquals("Package-private class \u2014 may not produce coverage sessions", result);
	}

	@Test
	void classifyFromSource_detectsConditionalSkip() throws IOException
	{
		File srcDir = writeTempSource("com/example/FooTest.java",
				"package com.example;\npublic class FooTest {\n  @Test void m() {\n    if (System.getenv(\"X\") == null) return;\n  }\n}");

		String result = NewTestDetector.classifyFromSource("com/example/FooTest.java", List.of(srcDir));

		assertEquals("Conditional skip \u2014 tests may not execute without env vars", result);
	}

	@Test
	void classifyFromSource_detectsArchUnit() throws IOException
	{
		File srcDir = writeTempSource("com/example/FooTest.java",
				"package com.example;\npublic class FooTest {\n  @ArchTest\n  static final ArchRule rule = null;\n}");

		String result = NewTestDetector.classifyFromSource("com/example/FooTest.java", List.of(srcDir));

		assertEquals("ArchUnit test \u2014 @ArchTest fields, no standard @Test", result);
	}

	@Test
	void classifyFromSource_detectsNoTestAnnotation() throws IOException
	{
		File srcDir = writeTempSource("com/example/FooTest.java",
				"package com.example;\npublic class FooTest {\n  void helper() {}\n}");

		String result = NewTestDetector.classifyFromSource("com/example/FooTest.java", List.of(srcDir));

		assertEquals("No @Test methods found", result);
	}

	@Test
	void classifyFromSource_returnsNullForNormalTest() throws IOException
	{
		File srcDir = writeTempSource("com/example/FooTest.java",
				"package com.example;\npublic class FooTest {\n  @Test void m() {}\n}");

		String result = NewTestDetector.classifyFromSource("com/example/FooTest.java", List.of(srcDir));

		assertNull(result);
	}

	@Test
	void classifyFromSource_skipsBlockComments() throws IOException
	{
		File srcDir = writeTempSource("com/example/FooTest.java",
				"package com.example;\n/* copyright\n * @Test\n */\npublic class FooTest {\n  void helper() {}\n}");

		String result = NewTestDetector.classifyFromSource("com/example/FooTest.java", List.of(srcDir));

		assertEquals("No @Test methods found", result);
	}

	@Test
	void classifyFromSource_returnsNullWhenFileNotFound()
	{
		File emptyDir = tempDir.resolve("empty-src").toFile();
		emptyDir.mkdirs();

		String result = NewTestDetector.classifyFromSource("com/example/Missing.java", List.of(emptyDir));

		assertNull(result);
	}

	@Test
	void isAbstractOrInterface_detectsAbstract() throws IOException
	{
		File srcDir = writeTempSource("com/example/FooTest.java",
				"package com.example;\npublic abstract class FooTest {\n}");

		assertTrue(NewTestDetector.isAbstractOrInterface("com/example/FooTest.java", List.of(srcDir)));
	}

	@Test
	void isAbstractOrInterface_detectsInterface() throws IOException
	{
		File srcDir = writeTempSource("com/example/FooTest.java",
				"package com.example;\npublic interface FooTest {\n}");

		assertTrue(NewTestDetector.isAbstractOrInterface("com/example/FooTest.java", List.of(srcDir)));
	}

	@Test
	void isAbstractOrInterface_detectsAnnotationType() throws IOException
	{
		File srcDir = writeTempSource("com/example/FooTest.java",
				"package com.example;\npublic @interface FooTest {\n}");

		assertTrue(NewTestDetector.isAbstractOrInterface("com/example/FooTest.java", List.of(srcDir)));
	}

	@Test
	void isAbstractOrInterface_returnsFalseForConcreteClass() throws IOException
	{
		File srcDir = writeTempSource("com/example/FooTest.java",
				"package com.example;\npublic class FooTest {\n}");

		assertFalse(NewTestDetector.isAbstractOrInterface("com/example/FooTest.java", List.of(srcDir)));
	}

	@Test
	void isAbstractOrInterface_returnsFalseForMissingSrcDir()
	{
		assertFalse(NewTestDetector.isAbstractOrInterface("com/example/Missing.java", List.of()));
	}

	@Test
	void isAbstractOrInterface_handlesBlockComments() throws IOException
	{
		File srcDir = writeTempSource("com/example/FooTest.java",
				"/* SAP copyright\n * header\n */\npackage com.example;\npublic class FooTest {\n}");

		assertFalse(NewTestDetector.isAbstractOrInterface("com/example/FooTest.java", List.of(srcDir)));
	}

	@Test
	void detect_withTestSourceDirs_filtersAbstractClasses() throws IOException
	{
		Path classFile = testClassesDir.toPath().resolve("com/example/AbstractBaseTest.class");
		Files.createDirectories(classFile.getParent());
		Files.write(classFile, new byte[]{0});

		File srcDir = writeTempSource("com/example/AbstractBaseTest.java",
				"package com.example;\npublic abstract class AbstractBaseTest {\n}");

		CoverageMap map = buildMap("ExistingTest#test1");

		NewTestDetector detector = new NewTestDetector();
		Map<String, String> result = detector.detect(map, git, null,
				List.of(testClassesDir), List.of(srcDir), logger);

		assertFalse(result.containsKey("com.example.AbstractBaseTest"),
				"Abstract classes should be filtered out when testSourceDirs are provided");
	}

	@Test
	void detect_multiDir_scansAllDirectories() throws IOException
	{
		File dir1 = tempDir.resolve("classes1").toFile();
		File dir2 = tempDir.resolve("classes2").toFile();

		Path classFile1 = dir1.toPath().resolve("com/a/ATest.class");
		Path classFile2 = dir2.toPath().resolve("com/b/BTest.class");
		Files.createDirectories(classFile1.getParent());
		Files.createDirectories(classFile2.getParent());
		Files.write(classFile1, new byte[]{0});
		Files.write(classFile2, new byte[]{0});

		CoverageMap map = buildMap("ExistingTest#test1");

		NewTestDetector detector = new NewTestDetector();
		Map<String, String> result = detector.detect(map, git, null,
				List.of(dir1, dir2), logger);

		assertTrue(result.containsKey("com.a.ATest"));
		assertTrue(result.containsKey("com.b.BTest"));
	}

	private File writeTempSource(String relativePath, String content) throws IOException
	{
		File srcDir = tempDir.resolve("test-src").toFile();
		Path javaFile = srcDir.toPath().resolve(relativePath);
		Files.createDirectories(javaFile.getParent());
		Files.writeString(javaFile, content);
		return srcDir;
	}
}
