#!/usr/bin/env python3
"""Reconcile an EI-13 Maven plan with Surefire XML at enforceable granularity."""
import argparse
import hashlib
import json
import pathlib
import re
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument("--archive", required=True)
parser.add_argument("--expected", required=True)
parser.add_argument("--output", required=True)
args = parser.parse_args()

root = pathlib.Path(args.archive)
plan = json.loads((root / "execution-plan.json").read_text())
plans = [json.loads(path.read_text()) for path in sorted(root.glob("execution-plan-[0-9].json"))]

def identity(item):
    return f"{item['target']}::{item['class']}#{item['method']}"

planned = {identity(item) for item in plan["tests"]}
assigned = [identity(item) for shard in plans for item in shard["tests"]]
actual = set()
raw_invocations = skipped = failed = 0
for report in root.glob("reports-*/*/target/surefire-reports/TEST-*.xml"):
    module = report.parts[report.parts.index(next(x for x in report.parts if x.startswith("reports-"))) + 1]
    target = f"maven:{module}@surefire@default-test@examples"
    for case in ET.parse(report).iter("testcase"):
        raw_invocations += 1
        method = re.split(r"[([]", case.attrib["name"], maxsplit=1)[0]
        actual.add(f"{target}::{case.attrib['classname']}#{method}")
        skipped += case.find("skipped") is not None
        failed += case.find("failure") is not None or case.find("error") is not None

expected = set(json.loads(pathlib.Path(args.expected).read_text())["selectedTests"])
canonical = "".join(value + "\n" for value in sorted(planned))
result = {
    "normalization": "Surefire report invocations collapse to target::class#baseMethod; parameter lists, parameterized invocation arguments, repetition indices, and nested display suffixes are not independently selectable by Maven Surefire includesFile.",
    "expectedCount": len(expected),
    "plannedCount": len(planned),
    "assignedCount": len(assigned),
    "uniqueAssignedCount": len(set(assigned)),
    "executedCount": len(actual),
    "rawSurefireInvocations": raw_invocations,
    "skippedInvocations": skipped,
    "failedInvocations": failed,
    "shardCounts": [len(shard["tests"]) for shard in plans],
    "canonicalSha256": hashlib.sha256(canonical.encode()).hexdigest(),
    "expectedMissingFromPlan": sorted(expected - planned),
    "unexpectedInPlan": sorted(planned - expected),
    "duplicateAssignments": sorted({value for value in assigned if assigned.count(value) > 1}),
    "plannedMissingFromExecution": sorted(planned - actual),
    "unexpectedExecutions": sorted(actual - planned),
    "result": "PASS" if expected == planned == set(assigned) == actual and not failed else "FAIL",
}
pathlib.Path(args.output).write_text(json.dumps(result, indent=2) + "\n")
print(json.dumps(result, indent=2))
