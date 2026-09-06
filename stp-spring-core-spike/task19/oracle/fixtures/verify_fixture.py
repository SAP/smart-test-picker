#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0
import collections
import json
import sys
from pathlib import Path

events = [json.loads(line) for line in Path(sys.argv[1]).read_text().splitlines()]
observed = collections.Counter((e["classSignature"], e["methodName"], e["descriptor"]) for e in events)
service = "Ltask19/fixtures/FixtureService;"
expected_once = {
    (service, "<clinit>", "()V"), (service, "staticInitializer", "()V"),
    (service, "<init>", "()V"), (service, "publicCall", "()V"),
    (service, "privateHelper", "()V"), (service, "staticHelper", "()V"),
    (service, "selfInvocation", "()V"), (service, "overloaded", "(I)V"),
    (service, "overloaded", "(Ljava/lang/String;)V"), (service, "exceptionPath", "()V"),
    (service, "lambdaTarget", "()V"), (service, "reflectionTarget", "()V"),
    (service, "rawThreadTarget", "()V"), (service, "executorTarget", "()V"),
    (service, "completableFutureTarget", "()V"), (service, "virtualThreadTarget", "()V"),
    ("Ltask19/fixtures/StringContract;", "convert", "(Ljava/lang/String;)Ljava/lang/String;"),
    ("Ltask19/fixtures/StringContract;", "convert", "(Ljava/lang/Object;)Ljava/lang/Object;"),
}
missing = sorted(expected_once - observed.keys())
duplicates = sorted((entry, observed[entry]) for entry in expected_once if observed[entry] != 1)
thread_expectations = {
    "rawThreadTarget": "oracle-raw-thread", "executorTarget": "oracle-executor",
    "virtualThreadTarget": "oracle-virtual-thread",
}
wrong_threads = []
for method, thread in thread_expectations.items():
    matches = [e for e in events if e["methodName"] == method]
    if len(matches) != 1 or matches[0]["threadName"] != thread:
        wrong_threads.append({"method": method, "expected": thread, "observed": matches})
result = {"schemaVersion": "task19-oracle-fixture-acceptance-1", "eventCount": len(events),
          "expectedEntryCount": len(expected_once), "missing": missing,
          "unexpectedDuplicateExpectedEntries": duplicates, "wrongThreads": wrong_threads,
          "descriptorIdentityCorrect": not missing, "threadIdentityCorrect": not wrong_threads,
          "passed": not missing and not duplicates and not wrong_threads}
print(json.dumps(result, indent=2, sort_keys=True))
raise SystemExit(0 if result["passed"] else 1)
