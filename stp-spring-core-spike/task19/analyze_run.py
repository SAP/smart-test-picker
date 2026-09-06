#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0
import argparse
import collections
import json
from pathlib import Path


def name_only(method): return method.split("(", 1)[0]


def oracle_identity(event):
    signature = event["classSignature"]
    if not (signature.startswith("L") and signature.endswith(";")): return None
    return signature[1:-1].replace("/", ".") + "#" + event["methodName"] + event["descriptor"]


def production_classes(classes_root):
    result = set()
    for base in (classes_root / "java/main", classes_root / "kotlin/main", classes_root / "java21"):
        if base.exists():
            result.update(str(path.relative_to(base))[:-6].replace("/", ".") for path in base.rglob("*.class"))
    return result


def classify(owned, non_owned, asm, jacoco, ambiguous=False):
    if ambiguous: return "AMBIGUOUS_OWNERSHIP"
    if owned:
        if asm and jacoco: return "BOTH_CORRECT"
        if asm: return "ASM_TRUE_POSITIVE_JACOCO_FALSE_NEGATIVE"
        if jacoco: return "JACOCO_TRUE_POSITIVE_ASM_FALSE_NEGATIVE"
        return "BOTH_FALSE_NEGATIVE"
    if non_owned:
        if asm and not jacoco: return "ASM_FALSE_POSITIVE_JACOCO_CORRECT_EXCLUSION"
        if jacoco and not asm: return "JACOCO_FALSE_POSITIVE_ASM_CORRECT_EXCLUSION"
        if asm and jacoco: return "BOTH_FALSE_POSITIVE"
        return "BOTH_CORRECT_EXCLUSION"
    if asm or jacoco: return "COLLECTOR_REPORTED_ORACLE_NEVER_OBSERVED"
    return "NOT_EXECUTED_NOT_REPORTED"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--selection", type=Path, required=True)
    parser.add_argument("--classes-root", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--global-mode", action="store_true")
    args = parser.parse_args()
    run = args.run_dir
    asm_doc = json.loads((run / "asm-map.json").read_text())
    asm_exact = {entry["method"] for test in asm_doc["runtimeEvents"]["tests"] for entry in test["methods"]}
    jacoco_exact = set((run / "jacoco-methods.txt").read_text().splitlines())
    lifecycle = [json.loads(line) for line in (run / "oracle-lifecycle.jsonl").read_text().splitlines()]
    starts = [event for event in lifecycle if event["phase"] == "LEAF_OWNERSHIP_START"]
    ends = [event for event in lifecycle if event["phase"] == "LEAF_OWNERSHIP_END"]
    if len(starts) != 1 or len(ends) != 1: raise SystemExit("expected exactly one leaf ownership interval")
    # JVMTI clock_gettime(CLOCK_MONOTONIC) and HotSpot System.nanoTime() do not
    # share an epoch on macOS. The independently recorded wall clocks do.
    start, end = starts[0]["wallClockMillis"], ends[0]["wallClockMillis"]
    leaf_thread = starts[0]["threadName"]
    after_all_starts = [event for event in lifecycle if event["phase"] == "AFTER_ALL_START"]
    after_all_ends = [event for event in lifecycle if event["phase"] == "AFTER_ALL_END"]
    classes = production_classes(args.classes_root)
    exact_ownership = collections.defaultdict(set)
    for event in map(json.loads, (run / "oracle-method-events.jsonl").read_text().splitlines()):
        identity = oracle_identity(event)
        if not identity or identity.split("#", 1)[0] not in classes or event["methodName"] == "$jacocoInit": continue
        timestamp = event["wallClockNanos"] // 1_000_000
        in_after_all = any(begin["threadName"] == event["threadName"]
                           and begin["wallClockMillis"] <= timestamp <= finish["wallClockMillis"]
                           for begin, finish in zip(after_all_starts, after_all_ends))
        exact_ownership[identity].add("AMBIGUOUS" if start <= timestamp <= end and event["threadName"] != leaf_thread else
                                      "TEST_OWNED" if start <= timestamp <= end else
                                      "OUTSIDE_TEST" if timestamp < start or in_after_all else "LATE")
    name_ownership = collections.defaultdict(set)
    for identity, ownership in exact_ownership.items(): name_ownership[name_only(identity)].update(ownership)
    owned = {method for method, phases in name_ownership.items() if "TEST_OWNED" in phases}
    ambiguous = {method for method, phases in name_ownership.items() if "TEST_OWNED" not in phases and "AMBIGUOUS" in phases}
    late = {method for method, phases in name_ownership.items() if "TEST_OWNED" not in phases and "AMBIGUOUS" not in phases and "LATE" in phases}
    outside = set(name_ownership) - owned - ambiguous - late
    asm_names, jacoco_names = set(map(name_only, asm_exact)), set(map(name_only, jacoco_exact))
    selection = json.loads(args.selection.read_text())
    target = next(test for test in selection["tests"] if test["gradleSelector"] == args.run_dir.joinpath("selector.txt").read_text().strip())
    universe = (set(name_ownership) | asm_names | jacoco_names) if args.global_mode else set(target["targetUnionMethods"])
    per_method = []
    for method in sorted(universe):
        ownership = "TEST_OWNED" if method in owned else "AMBIGUOUS" if method in ambiguous else "LATE" if method in late else "OUTSIDE_TEST" if method in outside else "NOT_EXECUTED"
        ae, je = method in asm_names, method in jacoco_names
        descriptors = sorted(identity for identity in set(exact_ownership) | asm_exact | jacoco_exact if name_only(identity) == method)
        per_method.append({"methodIdentity": method, "oracleExecuted": ownership != "NOT_EXECUTED",
                           "oracleOwnership": ownership, "asmPresent": ae, "jacocoPresent": je,
                           "classification": classify(ownership == "TEST_OWNED", ownership in ("LATE", "OUTSIDE_TEST"), ae, je, ownership == "AMBIGUOUS"),
                           "level2Descriptors": descriptors,
                           "descriptorComparison": "OVERLOAD_AMBIGUITY" if len(descriptors) > 1 else "EXACT"})
    def metrics(collector):
        present = asm_names if collector == "asm" else jacoco_names
        scored = universe - ambiguous
        tp, fp, fn = len(owned & present & scored), len((scored - owned) & present), len(owned & scored - present)
        precision = tp / (tp + fp) if tp + fp else None
        recall = tp / (tp + fn) if tp + fn else None
        f1 = 2 * precision * recall / (precision + recall) if precision is not None and recall is not None and precision + recall else None
        return {"TP": tp, "FP": fp, "FN": fn, "precision": precision, "recall": recall, "F1": f1}
    joined = {"schemaVersion": "task19-joined-test-observation-1", "testIdentity": target["testIdentity"],
              "mode": "GLOBAL_SPRING_MODE" if args.global_mode else "TARGET_UNION_MODE",
              "oracle": {"executedMethods": sorted(name_ownership), "testOwnedMethods": sorted(owned),
                         "lateMethods": sorted(late), "outsideMethods": sorted(outside), "ambiguousMethods": sorted(ambiguous)},
              "asm": {"methods": sorted(asm_names)}, "jacoco": {"methods": sorted(jacoco_names)},
              "comparison": {"perMethod": per_method, "asmMetrics": metrics("asm"), "jacocoMetrics": metrics("jacoco")}}
    args.output_dir.mkdir(parents=True, exist_ok=True)
    (args.output_dir / "joined-test-observation.json").write_text(json.dumps(joined, indent=2, sort_keys=True) + "\n")
    comparison = {"testIdentity": target["testIdentity"],
                  "oracleTestOwnedMethods": sorted(owned), "oracleLateMethods": sorted(late),
                  "oracleOutsideMethods": sorted(outside), "oracleAmbiguousMethods": sorted(ambiguous), "asmMethods": sorted(asm_names),
                  "jacocoMethods": sorted(jacoco_names), "metrics": joined["comparison"]}
    (args.output_dir / "task19-test-comparison.json").write_text(json.dumps(comparison, indent=2, sort_keys=True) + "\n")


if __name__ == "__main__": main()
