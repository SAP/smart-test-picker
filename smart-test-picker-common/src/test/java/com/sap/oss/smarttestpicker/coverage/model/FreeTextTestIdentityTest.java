// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.model;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.sap.oss.smarttestpicker.coverage.serialization.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FreeTextTestIdentityTest {
    @ParameterizedTest
    @ValueSource(strings = {
        "Base PO class should contain @ConnectedVia with po.value when only po-value references it -> CASE-12345 TC-03.2",
        "perform uses httpGateway.dispatch with the given REST action",
        "Should update allowed kind [distributedBatchEnabled: #distributedBatchEnabled, maxWorkers: #maxWorkers, synchronous: #synchronous] -> TC-03 CASE-23456",
        "Should fail to create/update/delete non-dynamic item kind for Batch mode: (#batchOperation) and batch.allowed.violation: '#violationValue'-> TC-02 CASE-34567",
        "reads a.b; [value]/#path"
    })
    void freeTextSurvivesLogicalExecutableFragmentAndAssignmentCodecs(String name) {
        var logical = new TestIdentity("example.Spec", name);
        var executable = new ExecutableTestIdentity(ExecutionTarget.parse("ant:sample"), logical);
        assertEquals(logical, TestIdentity.parse(logical.toString()));
        assertEquals(executable, ExecutableTestIdentity.parse(executable.toString()));
        var revision = new CoverageMapRevision("revision");
        var shard = new ShardId("shard");
        var coverage = new TestCoverage(Set.of(), Set.of(), TestOutcome.PASS, CollectionStatus.COLLECTED_EMPTY);
        var logicalFragment = new CoverageFragment(2, revision, shard, Map.of(logical, coverage), List.of(), List.of(), true);
        var logicalCodec = new CoverageFragmentCodec();
        assertEquals(logicalFragment, logicalCodec.deserialize(logicalCodec.serialize(logicalFragment)));
        var fragment = new ExecutableCoverageFragment(3, revision, shard, Map.of(executable, coverage), List.of(), List.of(), true);
        var fragmentCodec = new ExecutableCoverageFragmentCodec();
        assertEquals(fragment, fragmentCodec.deserialize(fragmentCodec.serialize(fragment)));
        var assignment = new ExecutableShardAssignment(1, revision, shard, Set.of(executable));
        var assignmentCodec = new ExecutableShardAssignmentCodec();
        assertEquals(assignment, assignmentCodec.deserialize(assignmentCodec.serialize(assignment)));
    }

    @Test void isoControlsStillFail() {
        for (int value = 0; value <= 159; value++) {
            if (Character.isISOControl(value)) {
                String name = "before" + (char) value + "after";
                assertThrows(IllegalArgumentException.class, () -> new TestIdentity("example.Spec", name));
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"java.lang.String", "[I"})
    void validTypeSuffixHasTheDocumentedAmbiguity(String parameters) {
        var feature = new TestIdentity("example.Spec", "foo(" + parameters + ")");
        var method = new TestIdentity("example.Spec", "foo", parameters);
        assertEquals(feature.toString(), method.toString());
        assertEquals(method, TestIdentity.parse(feature.toString()));
    }
}
