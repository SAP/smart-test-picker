package com.sap.oss.smarttestpicker.maven;

import java.nio.file.*;
import java.util.*;
import com.sap.oss.smarttestpicker.coverage.model.*;
import com.sap.oss.smarttestpicker.coverage.serialization.*;
import com.sap.oss.smarttestpicker.selector.ExecutableHeadTestInventoryCodec;

/** Runtime assertions against published Jenkins artifacts, using the production codecs. */
class VerifyEvidence {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        String revision = Files.readString(root.resolve("revision.txt")).trim();
        var inventory = new ExecutableHeadTestInventoryCodec().read(root.resolve(".stp/head-test-inventory.json").toFile());
        var assignment = new ExecutableShardAssignmentCodec().deserialize(Files.readAllBytes(root.resolve("build/stp/shards/assignment-0.json")));
        var fragment = new ExecutableCoverageFragmentCodec().deserialize(Files.readAllBytes(root.resolve("build/stp/fragment-0.json")));
        var evidence = MavenMappingEvidence.read(root.resolve("build/stp/execution-evidence-0.json"));
        var map = new ExecutableCoverageMapCodec().deserialize(Files.readAllBytes(root.resolve("build/stp/coverage-map-distributed.json")));
        check(inventory.revision().equals(revision), "inventory revision");
        check(assignment.revision().value().equals(revision), "assignment revision");
        check(fragment.revision().value().equals(revision) && map.revision().value().equals(revision), "coverage revision");
        check(evidence.revision().equals(revision), "evidence revision");
        check(new HashSet<>(inventory.runnableTests()).equals(assignment.tests()), "inventory / assignment identities");
        check(fragment.tests().keySet().equals(assignment.tests()), "fragment / assignment identities");
        check(evidence.executed().equals(assignment.tests()) && evidence.nonExecuted().isEmpty(), "evidence partition");
        check(map.tests().equals(fragment.tests()), "published exact coverage");
        check(fragment.unmapped().isEmpty() && fragment.collectionCompleted(), "complete fragment");
        check(map.lifecycleState() == CoverageMapLifecycleState.PUBLISHED, "published lifecycle");
        check(map.completeness().isComplete() && map.completeness().expectedTests().equals(assignment.tests()), "published completeness");
        check(map.tests().size() == 2, "exact fixture assignment size");
        for (var entry : map.tests().entrySet()) {
            String identity = entry.getKey().toString();
            check(identity.startsWith("maven:module-b"), "target ownership: " + identity);
            var coverage = entry.getValue();
            if (identity.endsWith("::b.BTests#b2")) {
                check(coverage.coveredClasses().equals(Set.of("a.AValue", "b.BValue")), "cross-module classes");
                check(coverage.coveredMethods().stream().map(Object::toString).collect(java.util.stream.Collectors.toSet()).equals(Set.of("a.AValue#<init>()V", "a.AValue#value()I", "b.BValue#<init>()V", "b.BValue#value()I")), "cross-module methods");
            } else {
                check(identity.endsWith("::shared.SharedTest#same"), "unexpected identity: " + identity);
                check(coverage.coveredClasses().isEmpty() && coverage.coveredMethods().isEmpty(), "empty-test isolation");
            }
            System.out.println(identity + " classes=" + coverage.coveredClasses() + " methods=" + coverage.coveredMethods());
        }
        if (args.length > 1) {
            var other = new ExecutableCoverageMapCodec().deserialize(Files.readAllBytes(
                Path.of(args[1]).resolve("build/stp/coverage-map-distributed.json")));
            check(map.revision().equals(other.revision()), "ASM / JaCoCo same fixture revision");
            check(map.tests().equals(other.tests()), "ASM / JaCoCo exact identities, edges and outcomes");
            check(map.unmapped().equals(other.unmapped()), "ASM / JaCoCo fixture unmapped equality");
            System.out.println("ASM09 FIXTURE-ONLY MAP EQUALITY PASS; no collector equivalence claim");
        }
        System.out.println("ASM09 PRODUCTION CODECS PASS");
    }
    private static void check(boolean condition, String diagnostic) {
        if (!condition) throw new AssertionError(diagnostic);
    }
}
