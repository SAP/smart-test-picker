#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0
import argparse
import collections
import json
from pathlib import Path


def canonical(test):
    cls, method = test.get("testClass"), test.get("testMethod")
    if not cls or not method: return None
    value = f"{cls}#{method}"
    hashed = 0
    for char in value: hashed = (31 * hashed + ord(char)) & 0xffffffff
    hashed &= 0x7fffffff
    suffix = f"{hashed:07x}"[:7]
    return f"{cls.rsplit('.', 1)[-1].rsplit('$', 1)[-1]}#{method}_{suffix}"


def name_only(method): return method.split("(", 1)[0]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--inventory", type=Path, required=True)
    parser.add_argument("--asm-map", type=Path, required=True)
    parser.add_argument("--jacoco-map", type=Path, required=True)
    parser.add_argument("--size", type=int, default=40)
    parser.add_argument("--late-analysis", type=Path)
    parser.add_argument("--output", type=Path, default=Path(__file__).parent / "evidence/selected-spring-tests.json")
    args = parser.parse_args()
    inventory = json.loads(args.inventory.read_text())
    asm = json.loads(args.asm_map.read_text())
    jacoco = json.loads(args.jacoco_map.read_text())["testMappings"]
    by_test = collections.defaultdict(list)
    for category, edges in inventory["edgesByCategory"].items():
        for edge in edges: by_test[edge["testIdentity"]].append((category, edge))
    sources, asm_methods, leaf_occurrences = {}, collections.defaultdict(set), collections.Counter()
    for test in asm["runtimeEvents"]["tests"]:
        key = canonical(test)
        if key:
            leaf_occurrences[key] += 1
            sources[key] = f"{test['testClass']}.{test['testMethod']}"
            asm_methods[key].update(name_only(hit["method"]) for hit in test["methods"])

    selected = []
    remaining = {test for test in by_test if leaf_occurrences[test] == 1}
    quotas = {"ASYNC": 5, "CONCURRENT_REFERENCE_HASH_MAP": 6, "CONSTRUCTOR": 5,
              "GENERATED_PROXY": 3, "ORDINARY_SYNCHRONOUS": 8,
              "REFLECTION_BRIDGE_METHOD_RESOLVER": 5, "SETUP_LIFECYCLE": 3,
              "STATIC_INITIALIZATION": 3}
    # Directional controls prevent the much larger JaCoCo-only population from
    # crowding all historical ASM-only tests out of the validation sample.
    for direction in ("ASM_ONLY", "JACOCO_ONLY"):
        for category in quotas:
            candidates = [test for test in remaining if any(cat == category and edge["differenceType"] == direction
                                                             for cat, edge in by_test[test])]
            if candidates:
                test = max(candidates, key=lambda item: (sum(cat == category and edge["differenceType"] == direction
                                                             for cat, edge in by_test[item]), len(by_test[item]), item))
                selected.append(test); remaining.remove(test)
    for category, quota in quotas.items():
        candidates = sorted(remaining, key=lambda test: (-sum(cat == category for cat, _ in by_test[test]),
                                                          -len(by_test[test]), test))
        for test in candidates:
            if sum(any(cat == category for cat, _ in by_test[item]) for item in selected) >= quota: break
            if any(cat == category for cat, _ in by_test[test]):
                selected.append(test); remaining.remove(test)
    while len(selected) < args.size and remaining:
        test = max(remaining, key=lambda item: (len(by_test[item]), item))
        selected.append(test); remaining.remove(test)
    selected = selected[:args.size]
    if args.late_analysis:
        late = json.loads(args.late_analysis.read_text())
        late_tests = {entry["testIdentity"] for entry in late["lateEventsInvolvingUnstableIdentities"]}
        extras = sorted((test for test in remaining & late_tests), key=lambda test: (-len(by_test[test]), test))[:3]
        selected.extend(extras)
    records = []
    for test in selected:
        historical_asm = sorted(asm_methods.get(test, set()))
        historical_jacoco = sorted(jacoco.get(test, {}).get("methods", []))
        records.append({"testIdentity": test, "gradleSelector": sources.get(test),
                        "historicalAsmMethods": historical_asm,
                        "historicalJacocoMethods": historical_jacoco,
                        "targetUnionMethods": sorted(set(historical_asm) | set(historical_jacoco)),
                        "divergentEdgeCount": len(by_test[test]),
                        "differenceTypes": dict(collections.Counter(edge["differenceType"] for _, edge in by_test[test])),
                        "categories": sorted({category for category, _ in by_test[test]})})
    covered = sum(record["divergentEdgeCount"] for record in records)
    document = {"schemaVersion": "task19-selected-spring-tests-1", "selectionUnit": "TEST",
                "selectedTestCount": len(records), "historicalDivergentEdgesCovered": covered,
                "historicalDivergentPopulation": inventory["edgeCount"],
                "eligibilityRule": "exactly one historical JUnit leaf occurrence for the class+method identity",
                "populationCoveragePercent": round(100 * covered / inventory["edgeCount"], 6),
                "lateRelatedSupplementCount": sum(test in ({entry['testIdentity'] for entry in json.loads(args.late_analysis.read_text())['lateEventsInvolvingUnstableIdentities']} if args.late_analysis else set()) for test in selected),
                "tests": records}
    if any(record["gradleSelector"] is None for record in records):
        raise SystemExit("selected test lacks source identity")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(document, indent=2, sort_keys=True) + "\n")
    args.output.with_name("selected-tests.txt").write_text("\n".join(r["gradleSelector"] for r in records) + "\n")


if __name__ == "__main__": main()
