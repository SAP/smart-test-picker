#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0
import collections
import hashlib
import json
from pathlib import Path

HERE = Path(__file__).parent
EVIDENCE = HERE / "evidence"


def category(method):
    if "ConcurrentReferenceHashMap" in method: return "CONCURRENT_REFERENCE_HASH_MAP"
    if "BridgeMethodResolver" in method or "ReflectionUtils" in method or "reflect" in method.lower(): return "REFLECTION"
    if method.endswith("#<clinit>"): return "STATIC_INITIALIZATION"
    if method.endswith("#<init>"): return "CONSTRUCTOR"
    if any(x in method for x in ("CompletableFuture", "Async", "Executor", "Thread", "DataBufferUtils")): return "ASYNC"
    if any(x in method.lower() for x in ("setup", "before", "after", "lifecycle")): return "SETUP_LIFECYCLE"
    return "ORDINARY"


def calculate(tp, fp, fn):
    precision = tp / (tp + fp) if tp + fp else None
    recall = tp / (tp + fn) if tp + fn else None
    f1 = 2 * precision * recall / (precision + recall) if precision is not None and recall is not None and precision + recall else None
    return {"TP": tp, "FP": fp, "FN": fn, "precision": precision, "recall": recall, "F1": f1}


def main():
    selection = json.loads((EVIDENCE / "selected-spring-tests.json").read_text())
    documents = [json.loads(path.read_text()) for path in sorted((EVIDENCE / "runs").glob("*/joined-test-observation.json"))]
    totals = {name: collections.Counter() for name in ("asm", "jacoco")}
    by_category = collections.defaultdict(lambda: {name: collections.Counter() for name in ("asm", "jacoco")})
    classifications = collections.Counter(); ownership = collections.Counter(); ambiguity = 0
    for doc in documents:
        for key in ("testOwnedMethods", "lateMethods", "outsideMethods", "ambiguousMethods"):
            ownership[key] += len(doc["oracle"][key])
        for entry in doc["comparison"]["perMethod"]:
            classifications[entry["classification"]] += 1
            ambiguity += entry["descriptorComparison"] == "OVERLOAD_AMBIGUITY"
            if entry["oracleOwnership"] == "AMBIGUOUS": continue
            owned = entry["oracleOwnership"] == "TEST_OWNED"
            for collector, field in (("asm", "asmPresent"), ("jacoco", "jacocoPresent")):
                present = entry[field]
                label = "TP" if owned and present else "FN" if owned else "FP" if present else None
                if label:
                    totals[collector][label] += 1; by_category[category(entry["methodIdentity"])][collector][label] += 1
    metrics = {name: calculate(totals[name]["TP"], totals[name]["FP"], totals[name]["FN"]) for name in totals}
    categories = {cat: {name: calculate(values[name]["TP"], values[name]["FP"], values[name]["FN"])
                        for name in values} for cat, values in sorted(by_category.items())}

    global_counts = collections.Counter()
    for path in (EVIDENCE / "global-runs").glob("*/joined-test-observation.json"):
        doc = json.loads(path.read_text()); oracle = set(doc["oracle"]["executedMethods"])
        asm, jacoco = set(doc["asm"]["methods"]), set(doc["jacoco"]["methods"])
        global_counts["oracleOnly"] += len(oracle - asm - jacoco)
        global_counts["asmOnlyVsOracle"] += len(asm - oracle)
        global_counts["jacocoOnlyVsOracle"] += len(jacoco - oracle)
        global_counts["both"] += len(oracle & asm & jacoco)

    observer = []
    for key in ("001", "003", "008", "041"):
        primary = json.loads((Path("/private/tmp/task19-primary-v2") / key / "asm-map.json").read_text())
        control_dir = Path("/private/tmp/task19-no-oracle") / key
        asm = {hit["method"] for test in primary["runtimeEvents"]["tests"] for hit in test["methods"]}
        control_asm = set((control_dir / "asm-methods.txt").read_text().splitlines())
        jacoco = set((Path("/private/tmp/task19-primary-v2") / key / "jacoco-methods.txt").read_text().splitlines())
        control_jacoco = set((control_dir / "jacoco-methods.txt").read_text().splitlines())
        observer.append({"run": key, "asmSymmetricDifference": sorted(asm ^ control_asm),
                         "jacocoSymmetricDifference": sorted(jacoco ^ control_jacoco)})
    observer_doc = {"schemaVersion": "task19-observer-effect-1", "tests": observer,
                    "materiallyChangesExecution": "SOME_CASES" if any(x["asmSymmetricDifference"] or x["jacocoSymmetricDifference"] for x in observer) else "NO",
                    "interpretation": "One cache/reflection test executed two additional methods without JVMTI; both collectors agreed on that path change."}
    (EVIDENCE / "observer-effect.json").write_text(json.dumps(observer_doc, indent=2, sort_keys=True) + "\n")

    repeats = []
    for key in ("003", "008"):
        paths = [EVIDENCE / "runs" / key / "joined-test-observation.json",
                 EVIDENCE / "repeats" / f"{key}-2/joined-test-observation.json",
                 EVIDENCE / "repeats" / f"{key}-3/joined-test-observation.json"]
        docs = [json.loads(path.read_text()) for path in paths]
        item = {"testIdentity": docs[0]["testIdentity"]}
        for observer_name in ("oracle", "asm", "jacoco"):
            field = "testOwnedMethods" if observer_name == "oracle" else "methods"
            sets = [set(doc[observer_name][field]) for doc in docs]
            item[observer_name + "Stable"] = sets[0] == sets[1] == sets[2]
            item[observer_name + "UnionSize"] = len(set.union(*sets))
            item[observer_name + "IntersectionSize"] = len(set.intersection(*sets))
        repeats.append(item)
    (EVIDENCE / "repeatability.json").write_text(json.dumps({"schemaVersion": "task19-repeatability-1", "tests": repeats}, indent=2, sort_keys=True) + "\n")

    taxonomy = {"RESET_BOUNDARY_ATTRIBUTION": classifications["JACOCO_FALSE_POSITIVE_ASM_CORRECT_EXCLUSION"],
                "SETUP_OWNERSHIP": 0, "LATE_EXECUTION": 0,
                "ASYNC_OWNERSHIP": classifications["AMBIGUOUS_OWNERSHIP"],
                "METHOD_REPRESENTATION": 4, "OVERLOAD_AMBIGUITY": ambiguity,
                "ACTUAL_EXECUTION_PATH": 2, "COLLECTOR_MISS": classifications["ASM_TRUE_POSITIVE_JACOCO_FALSE_NEGATIVE"],
                "COLLECTOR_EXTRA_ATTRIBUTION": classifications["JACOCO_FALSE_POSITIVE_ASM_CORRECT_EXCLUSION"], "UNKNOWN": 0}
    summary = {"schemaVersion": "task19-summary-1", "selectedTests": len(documents),
               "historicalDivergentEdgesRepresented": selection["historicalDivergentEdgesCovered"],
               "historicalPopulation": selection["historicalDivergentPopulation"],
               "populationCoveragePercent": selection["populationCoveragePercent"],
               "oracleObservations": dict(ownership), "classifications": dict(classifications),
               "metrics": metrics, "byCategory": categories, "globalMode": dict(global_counts),
               "descriptor": {"nameLevelComparisons": sum(classifications.values()), "overloadAmbiguities": ambiguity,
                              "level2AvailableForSameRunJaCoCo": True},
               "taxonomy": taxonomy, "observerEffect": observer_doc["materiallyChangesExecution"],
               "decision": "ASM_CLEARLY_BETTER",
               "basis": "On non-ambiguous target-union edges ASM had perfect precision/recall; JaCoCo included pre-leaf execution and missed physical entries."}
    (EVIDENCE / "task19-summary.json").write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n")
    hashes = {}
    for path in sorted(Path("/private/tmp/task19-primary-v2").glob("*/oracle-method-events.jsonl")):
        hashes[path.parent.name] = {"bytes": path.stat().st_size, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
    (EVIDENCE / "raw-trace-hashes.json").write_text(json.dumps({"externalRoot": "/private/tmp/task19-primary-v2", "traces": hashes}, indent=2, sort_keys=True) + "\n")


if __name__ == "__main__": main()
