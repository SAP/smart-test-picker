#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0
import json
import sys
from pathlib import Path

method_events = [json.loads(line) for line in Path(sys.argv[1]).read_text().splitlines()]
lifecycle = [json.loads(line) for line in Path(sys.argv[2]).read_text().splitlines()]
phases = [event["phase"] for event in lifecycle]
required = ["BEFORE_ALL_START", "BEFORE_ALL_END", "LEAF_OWNERSHIP_START", "BEFORE_EACH_START",
            "BEFORE_EACH_END", "TEST_EXECUTION_START", "TEST_EXECUTION_END", "AFTER_EACH_START",
            "AFTER_EACH_END", "LEAF_OWNERSHIP_END", "AFTER_ALL_START", "AFTER_ALL_END"]
missing_phases = [phase for phase in required if phase not in phases]
ordered = not missing_phases and all(phases.index(left) < phases.index(right) for left, right in zip(required, required[1:]))
start = next((event["wallClockMillis"] for event in lifecycle if event["phase"] == "LEAF_OWNERSHIP_START"), 0)
end = next((event["wallClockMillis"] for event in lifecycle if event["phase"] == "LEAF_OWNERSHIP_END"), 0)
after_all_start = next((event for event in lifecycle if event["phase"] == "AFTER_ALL_START"), None)
after_all_end = next((event for event in lifecycle if event["phase"] == "AFTER_ALL_END"), None)
observed = {}
for event in method_events:
    if event["classSignature"] != "Ltask19/fixtures/LifecycleTargets;": continue
    timestamp = event["wallClockNanos"] // 1_000_000
    in_after_all = (after_all_start and after_all_end and event["threadName"] == after_all_start["threadName"]
                    and after_all_start["wallClockMillis"] <= timestamp <= after_all_end["wallClockMillis"])
    observed[event["methodName"]] = ("TEST_OWNED" if start <= timestamp <= end else
                                      "OUTSIDE_TEST" if timestamp < start or in_after_all else "LATE")
expected = {"beforeAllTarget": "OUTSIDE_TEST", "beforeEachTarget": "TEST_OWNED", "testTarget": "TEST_OWNED",
            "afterEachTarget": "TEST_OWNED", "lateTarget": "LATE", "afterAllTarget": "OUTSIDE_TEST"}
result = {"schemaVersion": "task19-lifecycle-fixture-acceptance-1", "missingPhases": missing_phases,
          "phaseOrderCorrect": ordered, "expectedOwnership": expected, "observedOwnership": observed,
          "lateMethodCorrectlyAfterTestEnd": observed.get("lateTarget") == "LATE",
          "passed": ordered and observed == expected}
print(json.dumps(result, indent=2, sort_keys=True))
raise SystemExit(0 if result["passed"] else 1)
