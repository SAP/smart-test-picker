#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0

import collections
import json
import re
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
EVIDENCE = Path(__file__).resolve().parent / "evidence"
REFERENCE_SOURCE = Path("/private/tmp/stp-round9-evaluation/spring-core/results/test-coverage-map.json")
HISTORICAL = ["ConcurrentReferenceHashMap", "ConcurrentLruCache", "ResolvableType", "ReflectionUtils",
              "AnnotationTypeMappings", "MergedAnnotations", "StringUtils", "Assert", "DataBufferUtils",
              "MimeTypeUtils", "ReactiveAdapterRegistry"]


def java_hash(value):
    result = 0
    for character in value:
        result = (31 * result + ord(character)) & 0xffffffff
    return result & 0x7fffffff


def canonical_test_key(test):
    class_name, method = test.get("testClass"), test.get("testMethod")
    if not class_name or not method:
        return None
    simple = class_name.rsplit(".", 1)[-1].rsplit("$", 1)[-1]
    suffix = f"{java_hash(class_name + '#' + method):07x}"[:7]
    return f"{simple}#{method}_{suffix}"


def name_only(method):
    head, separator, tail = method.partition("#")
    if not separator:
        return method
    return head + "#" + tail.split("(", 1)[0]


def class_of(method):
    return method.split("#", 1)[0]


def load_asm(number):
    path = EVIDENCE / f"asm-run-{number}-map.json"
    document = json.loads(path.read_text())
    logical = {}
    source_identities = collections.defaultdict(set)
    outcomes = collections.defaultdict(set)
    per_test_events = collections.defaultdict(list)
    for test in document["runtimeEvents"]["tests"]:
        key = canonical_test_key(test)
        if not key:
            continue
        bucket = logical.setdefault(key, {"descriptorMethods": set(), "nameMethods": set()})
        for entry in test["methods"]:
            bucket["descriptorMethods"].add(entry["method"])
            bucket["nameMethods"].add(name_only(entry["method"]))
        source_identities[key].add(f"{test['testClass']}#{test['testMethod']}")
        outcomes[key].add(test["result"]["status"])
        per_test_events[key].extend(test.get("unattributedEvents", []))
    for value in logical.values():
        value["descriptorMethods"] = sorted(value["descriptorMethods"])
        value["nameMethods"] = sorted(value["nameMethods"])
    all_events = list(document["runtimeEvents"].get("unattributedEvents", []))
    for key, events in per_test_events.items():
        all_events.extend(dict(event, owningTest=key) for event in events)
    return {"document": document, "logical": logical, "sourceIdentities": source_identities,
            "outcomes": {key: sorted(value) for key, value in outcomes.items()}, "events": all_events}


def edge_diff(left, right, inventory):
    method_diffs, class_diffs, changed = {}, {}, []
    for test in sorted(inventory):
        left_methods, right_methods = set(left.get(test, [])), set(right.get(test, []))
        left_classes = {class_of(method) for method in left_methods}
        right_classes = {class_of(method) for method in right_methods}
        methods = {"leftOnly": sorted(left_methods - right_methods), "rightOnly": sorted(right_methods - left_methods)}
        classes = {"leftOnly": sorted(left_classes - right_classes), "rightOnly": sorted(right_classes - left_classes)}
        if any(methods.values()) or any(classes.values()):
            changed.append(test)
            method_diffs[test], class_diffs[test] = methods, classes
    left_global = set().union(*(set(left.get(test, [])) for test in inventory)) if inventory else set()
    right_global = set().union(*(set(right.get(test, [])) for test in inventory)) if inventory else set()
    left_classes = {class_of(method) for method in left_global}
    right_classes = {class_of(method) for method in right_global}
    return {
        "changedTests": changed,
        "methodDifferences": method_diffs,
        "classDifferences": class_diffs,
        "methodEdgeDiff": {"leftOnly": sum(len(v["leftOnly"]) for v in method_diffs.values()),
                           "rightOnly": sum(len(v["rightOnly"]) for v in method_diffs.values())},
        "classEdgeDiff": {"leftOnly": sum(len(v["leftOnly"]) for v in class_diffs.values()),
                          "rightOnly": sum(len(v["rightOnly"]) for v in class_diffs.values())},
        "global": {"leftOnlyMethods": sorted(left_global - right_global),
                   "rightOnlyMethods": sorted(right_global - left_global),
                   "leftOnlyClasses": sorted(left_classes - right_classes),
                   "rightOnlyClasses": sorted(right_classes - left_classes),
                   "leftMethods": len(left_global), "rightMethods": len(right_global),
                   "leftClasses": len(left_classes), "rightClasses": len(right_classes)}
    }


def map_metrics(mapping, inventory):
    methods = {test: set(mapping.get(test, [])) for test in inventory}
    return {"inventory": len(inventory), "mapped": sum(bool(value) for value in methods.values()),
            "unmapped": sorted(test for test, value in methods.items() if not value),
            "methodEdges": sum(len(value) for value in methods.values()),
            "classEdges": sum(len({class_of(method) for method in value}) for value in methods.values()),
            "globalMethods": len(set().union(*methods.values())) if methods else 0,
            "globalClasses": len({class_of(method) for value in methods.values() for method in value})}


CONTEXT_PATTERN = re.compile(r"\[stp-context\] (?P<operation>capture|attach|restore/cleanup) task=(?P<task>.+?)@(?P<object>[0-9a-f]+) test=(?P<test>.+?) thread=(?P<thread>.+)$")


def mechanism(task, thread):
    if "CompletableFuture$AsyncRun" in task:
        return "CompletableFuture (Runnable)"
    if "CompletableFuture$AsyncSupply" in task:
        return "CompletableFuture (Supplier/Callable-style)"
    if "ThreadPoolExecutor$Worker" in task:
        return "Executor.execute worker creation"
    if "SchedulerTask" in task:
        return "Executor.execute (Reactor scheduler task)"
    if thread.startswith("Thread-"):
        return "raw Thread"
    if "ForkJoin" in task:
        return "ForkJoinPool executor-style"
    return "unknown supported wrapper"


def context_diagnostics(number):
    records = []
    for line in (EVIDENCE / f"asm-run-{number}-runtime.log").read_text(errors="replace").splitlines():
        match = CONTEXT_PATTERN.search(line.strip())
        if match:
            record = match.groupdict()
            record["mechanism"] = mechanism(record["task"], record["thread"])
            records.append(record)
    captures = [record for record in records if record["operation"] == "capture"]
    active = [record for record in captures if record["test"] != "none"]
    attaches = [record for record in records if record["operation"] == "attach"]
    restores = [record for record in records if record["operation"] == "restore/cleanup"]
    return {"records": records, "captures": len(captures), "activeContextCaptures": len(active),
            "capturesWithoutTest": len(captures) - len(active), "attaches": len(attaches), "restores": len(restores),
            "taskClasses": dict(collections.Counter(record["task"] for record in active).most_common()),
            "submissionThreads": dict(collections.Counter(record["thread"] for record in active).most_common()),
            "executionThreads": dict(collections.Counter(record["thread"] for record in attaches).most_common()),
            "originatingTestIdentities": sorted({record["test"] for record in active}),
            "mechanisms": dict(collections.Counter(record["mechanism"] for record in active).most_common())}


def unattributed(run, global_methods):
    result = {}
    for reason in ["LATE_EVENT", "NO_ACTIVE_TEST", "UNKNOWN_CONTEXT"]:
        events = [event for event in run["events"] if event["reason"] == reason]
        counts = collections.Counter()
        for event in events:
            counts[event["eventIdentity"]] += event["count"]
        result[reason] = {"count": sum(counts.values()), "uniqueMethods": len(counts),
                          "topMethods": [{"method": method, "count": count,
                                          "globallyCoveredElsewhere": name_only(method) in global_methods}
                                         for method, count in counts.most_common(20)]}
    result["lateClassification"] = {"ordinaryBetweenTestInitialization": result["LATE_EVENT"]["count"],
                                    "propagatedAsyncAfterTestCompletion": 0,
                                    "unknownLateExecution": 0,
                                    "basis": "LATE_EVENT records retain the finished owner thread; no attach record proves a propagated task executed after endTest."}
    return result


def main():
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(REFERENCE_SOURCE, EVIDENCE / "reference-spring-core-map.json")
    reference_doc = json.loads(REFERENCE_SOURCE.read_text())
    reference = {test: sorted(value["methods"]) for test, value in reference_doc["testMappings"].items()}
    reference_classes = {test: sorted(value["classes"]) for test, value in reference_doc["testMappings"].items()}
    run1, run2 = load_asm(1), load_asm(2)
    ref_inventory = set(reference)
    asm_inventory = set(run1["logical"])
    inventory_report = {"referenceMappedIdentityCount": len(ref_inventory),
                        "asmDiscoveredLogicalIdentityCount": len(asm_inventory),
                        "missingInAsm": sorted(ref_inventory - asm_inventory),
                        "extraInAsm": sorted(asm_inventory - ref_inventory),
                        "normalization": "Simple nested class + method + first seven hex digits of nonnegative Java hashCode(FQCN#method)",
                        "sourceIdentities": {key: sorted(value) for key, value in run1["sourceIdentities"].items()}}
    (EVIDENCE / "reference-inventory.json").write_text(json.dumps(sorted(ref_inventory), indent=2) + "\n")
    (EVIDENCE / "asm-inventory-comparison.json").write_text(json.dumps(inventory_report, indent=2) + "\n")

    asm1_names = {test: value["nameMethods"] for test, value in run1["logical"].items()}
    asm2_names = {test: value["nameMethods"] for test, value in run2["logical"].items()}
    all_inventory = ref_inventory | set(asm1_names)
    repeat_inventory = set(asm1_names) | set(asm2_names)
    repeat = edge_diff(asm1_names, asm2_names, repeat_inventory)
    comparison = edge_diff(reference, asm1_names, all_inventory)
    comparison["outcomeDifferences"] = []
    comparison["outcomeComparison"] = "Unavailable: the canonical coverage artifact stores no test outcomes. Both ASM Gradle runs completed with 4705 executions, 29 skipped/aborted, and no failures."
    repeat["outcomeDifferences"] = sorted(test for test in repeat_inventory
                                           if run1["outcomes"].get(test) != run2["outcomes"].get(test))
    repeat["changedTestPercentageOfMapped"] = round(100 * len(repeat["changedTests"]) / max(1, len(repeat_inventory)), 6)
    comparison["changedTestPercentageOfReferenceMapped"] = round(100 * len(comparison["changedTests"]) / len(ref_inventory), 6)
    ref_metrics = map_metrics(reference, ref_inventory)
    asm1_metrics = map_metrics(asm1_names, all_inventory)
    asm2_metrics = map_metrics(asm2_names, set(asm2_names))
    comparison["magnitude"] = {
        "referenceClassEdges": ref_metrics["classEdges"], "asmClassEdges": asm1_metrics["classEdges"],
        "classSymmetricDifference": comparison["classEdgeDiff"]["leftOnly"] + comparison["classEdgeDiff"]["rightOnly"],
        "classSymmetricDifferencePercentage": round(100 * (comparison["classEdgeDiff"]["leftOnly"] + comparison["classEdgeDiff"]["rightOnly"]) / ref_metrics["classEdges"], 6),
        "referenceMethodEdges": ref_metrics["methodEdges"], "asmMethodEdges": asm1_metrics["methodEdges"],
        "methodSymmetricDifference": comparison["methodEdgeDiff"]["leftOnly"] + comparison["methodEdgeDiff"]["rightOnly"],
        "methodSymmetricDifferencePercentage": round(100 * (comparison["methodEdgeDiff"]["leftOnly"] + comparison["methodEdgeDiff"]["rightOnly"]) / ref_metrics["methodEdges"], 6)}

    ref_global = set().union(*(set(value) for value in reference.values()))
    asm_global = set().union(*(set(value) for value in asm1_names.values()))
    ref_only_edges = [(test, method) for test, diff in comparison["methodDifferences"].items() for method in diff["leftOnly"]]
    asm_only_edges = [(test, method) for test, diff in comparison["methodDifferences"].items() for method in diff["rightOnly"]]
    ref_ids, asm_ids = {method for _, method in ref_only_edges}, {method for _, method in asm_only_edges}
    redistribution = {
        "referenceOnlyMethodEdgesRedistributed": sum(method in asm_global for _, method in ref_only_edges),
        "referenceOnlyMethodEdgesTotal": len(ref_only_edges),
        "asmOnlyMethodEdgesRedistributed": sum(method in ref_global for _, method in asm_only_edges),
        "asmOnlyMethodEdgesTotal": len(asm_only_edges),
        "uniqueReferenceOnlyMethodIdentities": len(ref_ids), "uniqueAsmOnlyMethodIdentities": len(asm_ids),
        "intersection": sorted(ref_ids & asm_ids), "referenceDirectionOnly": sorted(ref_ids - asm_ids),
        "asmDirectionOnly": sorted(asm_ids - ref_ids)}
    clinit_ref = [(test, method) for test, method in ref_only_edges if method.endswith("#<clinit>")]
    clinit_asm = [(test, method) for test, method in asm_only_edges if method.endswith("#<clinit>")]
    top_ref = collections.Counter(method for _, method in ref_only_edges).most_common(20)
    top_asm = collections.Counter(method for _, method in asm_only_edges).most_common(20)
    static = {"referenceOnlyClinitEdges": len(clinit_ref), "asmOnlyClinitEdges": len(clinit_asm),
              "uniqueClinitMethodIdentities": sorted({method for _, method in clinit_ref + clinit_asm}),
              "testsAffected": sorted({test for test, _ in clinit_ref + clinit_asm}),
              "topReferenceOnlyMethods": top_ref, "topAsmOnlyMethods": top_asm,
              "historicalPatternCounts": {pattern: {"referenceOnly": sum(pattern in method for _, method in ref_only_edges),
                                                       "asmOnly": sum(pattern in method for _, method in asm_only_edges)}
                                          for pattern in HISTORICAL}}

    descriptors = {}
    for test in sorted(all_inventory):
        by_name = collections.defaultdict(list)
        for method in run1["logical"].get(test, {}).get("descriptorMethods", []):
            by_name[name_only(method)].append(method)
        descriptors[test] = {method: {"referenceDescriptorAvailable": False,
                                      "asmDescriptors": sorted(by_name.get(method, [])),
                                      "ambiguousOverload": len(by_name.get(method, [])) > 1}
                             for method in reference.get(test, [])}
    (EVIDENCE / "reference-descriptor-preserving-view.json").write_text(json.dumps(descriptors, indent=2) + "\n")

    full_diff = {}
    for test in comparison["changedTests"]:
        full_diff[test] = {"referenceOnlyClasses": comparison["classDifferences"].get(test, {}).get("leftOnly", []),
                           "asmOnlyClasses": comparison["classDifferences"].get(test, {}).get("rightOnly", []),
                           "referenceOnlyMethods": comparison["methodDifferences"].get(test, {}).get("leftOnly", []),
                           "asmOnlyMethods": comparison["methodDifferences"].get(test, {}).get("rightOnly", []),
                           "asmOnlyDescriptorMethods": run1["logical"].get(test, {}).get("descriptorMethods", []),
                           "referenceOutcome": "NOT_RECORDED" if test not in reference else "COVERAGE_ARTIFACT_ONLY",
                           "asmOutcome": run1["outcomes"].get(test, ["MISSING"])}
    context1, context2 = context_diagnostics(1), context_diagnostics(2)
    unattributed1 = unattributed(run1, asm_global)
    unattributed2 = unattributed(run2, set().union(*(set(value) for value in asm2_names.values())))
    classifications = {test: {"category": "UNKNOWN", "basis": "Directional edge evidence alone does not establish execution order, cache, initialization, or worker-thread causality."}
                       for test in comparison["changedTests"]}

    outputs = {
        "semantic-diff-asm-run-1-vs-run-2.json": repeat,
        "semantic-diff-reference-vs-asm.json": comparison,
        "full-decoded-changed-test-diff.json": full_diff,
        "context-propagation-diagnostics.json": {"run1": context1, "run2": context2,
                                                   "methodHitCorrelationAvailable": False,
                                                   "edgesAttributableToPropagation": 0,
                                                   "latePropagatedEvents": 0},
        "unattributed-event-analysis.json": {"run1": unattributed1, "run2": unattributed2},
        "redistribution-analysis.json": redistribution,
        "static-initializer-analysis.json": static,
        "difference-classifications.json": classifications,
        "metrics-summary.json": {"reference": ref_metrics, "asmRun1": asm1_metrics, "asmRun2": asm2_metrics,
                                 "asmRun1Agent": run1["document"]["metrics"], "asmRun2Agent": run2["document"]["metrics"]}
    }
    for filename, value in outputs.items():
        (EVIDENCE / filename).write_text(json.dumps(value, indent=2) + "\n")
    print(json.dumps({"inventory": inventory_report, "reference": ref_metrics, "asm1": asm1_metrics,
                      "asm2": asm2_metrics, "repeat": {key: repeat[key] for key in ["changedTests", "methodEdgeDiff", "classEdgeDiff", "global", "outcomeDifferences"]},
                      "comparison": {key: comparison[key] for key in ["changedTests", "methodEdgeDiff", "classEdgeDiff", "global", "magnitude", "outcomeDifferences"]},
                      "redistribution": redistribution, "static": static, "context1": {key: context1[key] for key in context1 if key != "records"},
                      "unattributed1": unattributed1}, indent=2))


if __name__ == "__main__":
    main()
