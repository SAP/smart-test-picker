// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.gradle.testfixtures.ProjectBuilder;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.data.SessionInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.sap.oss.smarttestpicker.coverage.model.*;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableCoverageFragmentCodec;

class EncodedSessionDiscoveryTest {
    @Test void gradleConverterAndExecToXmlFindEncodedDirectChild(@TempDir Path directory) throws Exception {
        Path exec = Files.createDirectories(directory.resolve("exec"));
        Path reports = directory.resolve("reports");
        Path classes = Files.createDirectories(directory.resolve("classes"));
        String base = "session_" + SessionFileNames.sanitize("Spec#read/write");
        assertEquals(exec, exec.resolve(base + ".exec").getParent());
        try (var stream = Files.newOutputStream(exec.resolve(base + ".exec"))) {
            new ExecutionDataWriter(stream).visitSessionInfo(new SessionInfo("fixture", 1, 2));
        }
        Files.writeString(exec.resolve(base + ".identity"),
                "format=1\nclassName=example.Spec\nmethodName=read/write\nmethodParameterTypes=\noutcome=PASS\n");
        var project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build();
        var task = project.getTasks().create("encodedFragment", GenerateGradleJacocoExecutableFragmentTask.class);
        task.setExecDir(exec.toFile()); task.setReportsDir(reports.toFile());
        task.setClassesDirs(List.of(classes.toFile())); task.setRevision("revision"); task.setShardId("shard");
        var target = new ExecutionTarget(BuildTool.GRADLE, ":test");
        var identity = new TestIdentity("example.Spec", "read/write");
        task.setTarget(target); task.setAssigned(Set.of(identity));
        Path output = directory.resolve("fragment.json"); task.getOutputFile().set(output.toFile());
        task.generate();
        assertEquals("EMPTY", Files.readString(reports.resolve(base + ".status")).trim(),
                "ExecToXmlEngine must discover the encoded session without recursive scanning");
        var fragment = new ExecutableCoverageFragmentCodec().deserialize(Files.readAllBytes(output));
        assertTrue(fragment.collectionCompleted());
        assertEquals(Set.of(new ExecutableTestIdentity(target, identity)), fragment.tests().keySet());
    }
}
