// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.cli;

import java.util.*;
import com.sap.oss.smarttestpicker.coverage.model.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JoinCoveragePoolTest
{
    @Test void sharesDictionariesAcrossFragmentsWithoutChangingCoverage()
    {
        JoinCoveragePool pool = new JoinCoveragePool();
        var first = fragment("1"); var second = fragment("2");
        var sharedFirst = pool.share(first); var sharedSecond = pool.share(second);
        assertEquals(first, sharedFirst); assertEquals(second, sharedSecond);
        var a = sharedFirst.tests().values().iterator().next();
        var b = sharedSecond.tests().values().iterator().next();
        assertSame(a.coveredMethods().iterator().next(), b.coveredMethods().iterator().next());
        assertSame(a.coveredClasses().iterator().next(), b.coveredClasses().iterator().next());
        assertSame(a.coveredClasses().iterator().next(), b.coveredMethods().iterator().next().binaryClassName());
    }

    private static ExecutableCoverageFragment fragment(String shard)
    {
        String owner = new String("example.Value");
        var method = new MethodIdentity(owner, "value", "()I");
        var id = ExecutableTestIdentity.parse("maven:.::example.Tests#test" + shard);
        var coverage = new TestCoverage(Set.of(owner), Set.of(method), TestOutcome.PASS,
                CollectionStatus.COLLECTED_WITH_COVERAGE);
        return new ExecutableCoverageFragment(3, new CoverageMapRevision("revision"), new ShardId(shard),
                Map.of(id, coverage), List.of(), List.of(), true);
    }
}
