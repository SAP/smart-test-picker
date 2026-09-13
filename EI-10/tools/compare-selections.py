#!/usr/bin/env python3
import glob
import json
import pathlib
import sys
import xml.etree.ElementTree as ET

evidence = pathlib.Path(__file__).resolve().parents[1]
expected = json.loads((evidence / "selection/independent-expected-selection.json").read_text())["expectedTests"]
plan = json.loads((evidence / "jenkins/job-b-build-5-artifacts/archive/build/stp/execution-plan.json").read_text())
selected = sorted(entry["class"] + "#" + entry["method"] for entry in plan["tests"])

def executed(pattern):
    result = set()
    for name in glob.glob(str(pattern)):
        for case in ET.parse(name).getroot().iter("testcase"):
            method = case.attrib["name"]
            if method.endswith("()"):
                method = method[:-2]
            result.add(case.attrib["classname"] + "#" + method)
    return sorted(result)

actual = executed(evidence / "jenkins/job-b-build-5-artifacts/archive/spring-core/build/test-results/test/*.xml")
result = {
    "planMode": plan["mode"],
    "expectedCount": len(expected),
    "selectedCount": len(selected),
    "executedCount": len(actual),
    "expectedEqualsSelected": expected == selected,
    "selectedEqualsExecuted": selected == actual,
    "missingFromExecution": sorted(set(selected) - set(actual)),
    "outsideSelection": sorted(set(actual) - set(selected)),
    "selectedTests": selected,
    "executedTests": actual,
}
if len(sys.argv) == 2:
    full = executed(pathlib.Path(sys.argv[1]))
    result.update({
        "fullSuiteCount": len(full),
        "selectedIncludedInFullSuite": set(selected) <= set(full),
        "selectionSmallerThanFullSuite": len(selected) < len(full),
        "fullSuiteTests": full,
    })
json.dump(result, sys.stdout, indent=2)
sys.stdout.write("\n")
