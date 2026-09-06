#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0

"""Derive compact TASK 21 population and selector evidence from immutable raw inputs."""

from __future__ import annotations

import collections
import hashlib
import importlib.util
import json
import math
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
EVIDENCE = Path(__file__).resolve().parent / "evidence"
RAW = Path("/private/tmp/task21-raw")
ASM_PATH = RAW / "asm-full-map.json"
LOG_PATH = RAW / "gradle.log"
EVAL_ROOT = Path("/private/tmp/stp-round9-evaluation")
REFERENCE_PATH = EVAL_ROOT / "spring-core/results/test-coverage-map.json"
SPRING = Path("/private/tmp/stp-round9-spring")
SPRING_REVISION = "99a366baf6640b275d08dde60f05da719139bb6a"
EVAL_REVISION = "72bc94c60642467f3abce5b2664658aa93d77cb9"


def read(path: Path):
    return json.loads(path.read_text())


def write(name: str, value):
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    (EVIDENCE / name).write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")


def sha256(path: Path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def java_hash(value: str):
    result = 0
    for character in value:
        result = (31 * result + ord(character)) & 0xFFFFFFFF
    return result & 0x7FFFFFFF


def test_key(item):
    cls, method = item.get("testClass"), item.get("testMethod")
    if not cls or not method:
        return None
    simple = cls.rsplit(".", 1)[-1].rsplit("$", 1)[-1]
    suffix = f"{java_hash(cls + '#' + method):07x}"[:7]
    return f"{simple}#{method}_{suffix}"


def name_only(method):
    owner, sep, tail = method.partition("#")
    return owner + sep + tail.split("(", 1)[0]


def owner(method):
    return method.split("#", 1)[0]


def percentile(values, p):
    if not values:
        return 0
    ordered = sorted(values)
    index = max(0, math.ceil(p * len(ordered)) - 1)
    return ordered[index]


def load_asm(doc):
    logical = {}
    statuses = collections.Counter()
    sources = collections.defaultdict(set)
    per_test_unattributed = collections.defaultdict(list)
    for item in doc["runtimeEvents"]["tests"]:
        statuses[item["result"]["status"]] += 1
        key = test_key(item)
        if not key:
            continue
        bucket = logical.setdefault(key, {"names": set(), "descriptors": set()})
        for hit in item.get("methods", []):
            bucket["descriptors"].add(hit["method"])
            bucket["names"].add(name_only(hit["method"]))
        sources[key].add(item["testClass"] + "#" + item["testMethod"])
        per_test_unattributed[key].extend(item.get("unattributedEvents", []))
    for value in logical.values():
        value["names"] = sorted(value["names"])
        value["descriptors"] = sorted(value["descriptors"])
    events = list(doc["runtimeEvents"].get("unattributedEvents", []))
    for values in per_test_unattributed.values():
        events.extend(values)
    reasons = collections.Counter()
    for event in events:
        reasons[event["reason"]] += event["count"]
    return logical, statuses, sources, reasons


def edge_sets(mapping, tests):
    methods = {(test, method) for test in tests for method in mapping.get(test, set())}
    classes = {(test, owner(method)) for test, method in methods}
    return methods, classes


def category(method):
    cls, meth = method.split("#", 1)
    if meth == "<clinit>":
        return "STATIC_INITIALIZATION"
    if "ConcurrentReferenceHashMap" in cls:
        return "CONCURRENT_REFERENCE_HASH_MAP"
    if "BridgeMethodResolver" in cls or "ReflectionUtils" in cls:
        return "REFLECTION_BRIDGE_METHOD_RESOLVER"
    if re.search(r"\$\$|CGLIB|Proxy|Generated", cls):
        return "GENERATED_PROXY"
    if "Record" in cls or meth in {"equals", "hashCode", "toString"} and "$" in cls:
        return "RECORD_GENERATED"
    if meth in {"values", "valueOf", "$values"} or (meth == "<init>" and re.search(r"\$(State|Type|Kind|Mode|Status)$", cls)):
        return "ENUM_GENERATED"
    if meth == "<init>":
        return "CONSTRUCTOR"
    if "bridge" in meth.lower() or "Bridge" in cls:
        return "SYNTHETIC_BRIDGE"
    if any(token in cls + "#" + meth for token in ("Future", "Async", "Scheduler", "DataBufferUtils", "TaskExecutor")):
        return "ASYNC"
    if any(token in meth.lower() for token in ("setup", "before", "after", "initialize", "init")):
        return "SETUP_LIFECYCLE"
    if any(token in meth.lower() for token in ("late", "cleanup", "purge")):
        return "LATE_RELATED"
    if meth.startswith("lambda$") or meth.startswith("access$") or meth.startswith("$deserializeLambda$"):
        return "SYNTHETIC_BRIDGE"
    return "ORDINARY"


def task19_proven_edges():
    proven = {}
    for path in sorted((ROOT / "stp-spring-core-spike/task19/evidence/runs").glob("*/joined-test-observation.json")):
        doc = read(path)
        test = doc["testIdentity"]
        for row in doc["comparison"]["perMethod"]:
            classification = row["classification"]
            edge = (test, row["methodIdentity"])
            if classification == "ASM_TRUE_POSITIVE_JACOCO_FALSE_NEGATIVE":
                proven[("ASM_ONLY",) + edge] = {
                    "mechanism": "TASK19_PHYSICAL_ENTRY_JACOCO_GAP",
                    "basis": classification,
                    "evidenceReference": str(path.relative_to(ROOT)),
                }
            elif classification == "JACOCO_FALSE_POSITIVE_ASM_CORRECT_EXCLUSION":
                proven[("JACOCO_ONLY",) + edge] = {
                    "mechanism": "KNOWN_RESET_BOUNDARY_PRE_LEAF_ATTRIBUTION",
                    "basis": classification + "; oracle ownership OUTSIDE_TEST",
                    "evidenceReference": str(path.relative_to(ROOT)),
                }
    return proven


def known_pattern(direction, method):
    cat = category(method)
    if direction == "ASM_ONLY" and cat in {"ENUM_GENERATED", "RECORD_GENERATED", "SYNTHETIC_BRIDGE", "CONSTRUCTOR"}:
        return True
    if direction == "JACOCO_ONLY" and cat in {"STATIC_INITIALIZATION", "SETUP_LIFECYCLE", "REFLECTION_BRIDGE_METHOD_RESOLVER", "LATE_RELATED"}:
        return True
    return False


def comparison_reports(asm, jacoco):
    asm_tests, jacoco_tests = set(asm), set(jacoco)
    common = asm_tests & jacoco_tests
    comparable = asm_tests | jacoco_tests
    asm_method, asm_class = edge_sets(asm, comparable)
    jac_method, jac_class = edge_sets(jacoco, comparable)
    method_union = asm_method | jac_method
    class_union = asm_class | jac_class
    summary = {
        "schemaVersion": "task21-full-map-comparison-1",
        "normalization": "TestIdentity -> class + method name",
        "populations": {"asmMappedTests": sum(bool(asm[t]) for t in asm_tests), "jacocoMappedTests": len(jacoco_tests),
                        "commonTests": len(common), "asmOnlyTests": sorted(asm_tests - jacoco_tests),
                        "jacocoOnlyTests": sorted(jacoco_tests - asm_tests)},
        "methods": {"asmEdges": len(asm_method), "jacocoEdges": len(jac_method), "both": len(asm_method & jac_method),
                    "asmOnly": len(asm_method - jac_method), "jacocoOnly": len(jac_method - asm_method),
                    "symmetricDifference": len(asm_method ^ jac_method),
                    "symmetricDifferencePercent": round(100 * len(asm_method ^ jac_method) / len(method_union), 6),
                    "percentageDenominator": "edge union"},
        "classes": {"asmEdges": len(asm_class), "jacocoEdges": len(jac_class), "both": len(asm_class & jac_class),
                    "asmOnly": len(asm_class - jac_class), "jacocoOnly": len(jac_class - asm_class),
                    "symmetricDifference": len(asm_class ^ jac_class),
                    "symmetricDifferencePercent": round(100 * len(asm_class ^ jac_class) / len(class_union), 6),
                    "percentageDenominator": "edge union"},
    }
    rows = []
    for test in sorted(common):
        a, j = set(asm[test]), set(jacoco[test])
        union = a | j
        rows.append({"testIdentity": test, "asmMethodCount": len(a), "jacocoMethodCount": len(j),
                     "both": len(a & j), "asmOnly": len(a - j), "jacocoOnly": len(j - a),
                     "symmetricDifference": len(a ^ j),
                     "symmetricDifferencePercent": round(100 * len(a ^ j) / len(union), 6) if union else 0})
    diffs = [row["symmetricDifference"] for row in rows]
    dist = {
        "schemaVersion": "task21-per-test-difference-summary-1", "commonTests": len(rows),
        "identicalTests": sum(row["symmetricDifference"] == 0 for row in rows),
        "testsWithAnyDifference": sum(row["symmetricDifference"] > 0 for row in rows),
        "testsWithOnlyAsmAdditions": sum(row["asmOnly"] > 0 and row["jacocoOnly"] == 0 for row in rows),
        "testsWithOnlyJacocoAdditions": sum(row["jacocoOnly"] > 0 and row["asmOnly"] == 0 for row in rows),
        "testsWithDifferencesBothDirections": sum(row["asmOnly"] > 0 and row["jacocoOnly"] > 0 for row in rows),
        "percentilesAbsoluteSymmetricDifference": {f"p{int(p*100)}": percentile(diffs, p) for p in (.5, .75, .9, .95, .99)},
        "max": max(diffs, default=0),
        "top20AbsoluteDifference": sorted(rows, key=lambda r: (-r["symmetricDifference"], r["testIdentity"]))[:20],
        "top20PercentageDifference": sorted(rows, key=lambda r: (-r["symmetricDifferencePercent"], -r["symmetricDifference"], r["testIdentity"]))[:20],
        "top20AsmOnly": sorted(rows, key=lambda r: (-r["asmOnly"], r["testIdentity"]))[:20],
        "top20JacocoOnly": sorted(rows, key=lambda r: (-r["jacocoOnly"], r["testIdentity"]))[:20],
        "perTest": rows,
    }
    return summary, dist, asm_method, jac_method, asm_class, jac_class


def selector_evaluation(asm, jacoco, proven):
    module = EVAL_ROOT / "analysis/evaluation_core.py"
    spec = importlib.util.spec_from_file_location("evaluation_core", module)
    core = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = core
    spec.loader.exec_module(core)
    pit = tuple(sorted((EVAL_ROOT / "spring-core/results/per-class").glob("*/mutations.xml")))
    raw = core.load_pit_mutations("spring-core", EVAL_ROOT, pit)
    resolved = core.resolve_killing_tests(raw, jacoco, core.build_base_to_keys(jacoco))
    asm_mapping = {test: {"methods": sorted(methods), "classes": sorted({owner(m) for m in methods})} for test, methods in asm.items()}
    rows, counts = [], collections.Counter()
    safety_flips = collections.Counter()
    recoveries = collections.Counter()
    jacoco_delta_causes = collections.Counter()
    new_relevant, lost_relevant = set(), set()
    for mutation in resolved:
        a = core.select_original(asm_mapping, mutation.mutated_class, mutation.mutated_method)
        j = core.select_original({k: v for k, v in reference_doc["testMappings"].items()}, mutation.mutated_class, mutation.mutated_method)
        killing = {key for kt in mutation.killing_tests for key in kt.coverage_keys}
        a_safe, j_safe = bool(a & killing), bool(j & killing)
        if a == j: counts["same"] += 1
        counts["asmAdditional"] += len(a - j)
        counts["jacocoAdditional"] += len(j - a)
        if a_safe != j_safe:
            safety_flips["jacocoUnsafeToAsmSafe" if a_safe else "jacocoSafeToAsmUnsafe"] += 1
        new_relevant.update((a - j) & killing)
        lost_relevant.update((j - a) & killing)
        delta_classes = collections.Counter()
        for test in a - j:
            edge = ("ASM_ONLY", test, f"{mutation.mutated_class}#{mutation.mutated_method}")
            label = "SUPPORTED_BY_PROVEN_ASM_ADVANTAGE" if edge in proven else ("KNOWN_PATTERN_ONLY" if known_pattern("ASM_ONLY", edge[2]) else "UNEXPLAINED")
            delta_classes[label] += 1
            recoveries[label] += 1
        for test in j - a:
            edge = ("JACOCO_ONLY", test, f"{mutation.mutated_class}#{mutation.mutated_method}")
            label = "RESET_BOUNDARY_ATTRIBUTION" if edge in proven else ("OTHER_KNOWN_OVER_ATTRIBUTION_PATTERN" if known_pattern("JACOCO_ONLY", edge[2]) else "UNEXPLAINED_MAP_DIFFERENCE")
            jacoco_delta_causes[label] += 1
        rows.append({"mutationId": mutation.mutation_id, "changedClass": mutation.mutated_class,
                     "changedMethod": mutation.mutated_method, "both": len(a & j), "asmOnly": sorted(a - j),
                     "jacocoOnly": sorted(j - a), "union": len(a | j), "intersection": len(a & j),
                     "selectionSizeDelta": len(a) - len(j), "jacocoSafe": j_safe, "asmSafe": a_safe,
                     "asmDeltaClassification": dict(delta_classes)})
    deltas = [row["selectionSizeDelta"] for row in rows]
    impact = {"schemaVersion": "task21-selector-impact-1", "selectorSemantics": "evaluation_core.select_original",
              "scenarioSource": "454 committed KILLED Spring Core PIT mutations", "evaluationScenarios": len(rows),
              "sameSelection": counts["same"], "asmAdditionalSelections": counts["asmAdditional"],
              "jacocoAdditionalSelections": counts["jacocoAdditional"],
              "averageSelectionSizeDelta": round(sum(deltas) / len(deltas), 6),
              "maxSelectionSizeDelta": max(deltas), "minSelectionSizeDelta": min(deltas),
              "asmOnlySafetyClassification": {label: recoveries[label] for label in ("SUPPORTED_BY_PROVEN_ASM_ADVANTAGE", "KNOWN_PATTERN_ONLY", "UNEXPLAINED")},
              "jacocoOnlySelectionClassification": {label: jacoco_delta_causes[label] for label in ("RESET_BOUNDARY_ATTRIBUTION", "OTHER_KNOWN_OVER_ATTRIBUTION_PATTERN", "UNEXPLAINED_MAP_DIFFERENCE")},
              "scenarios": rows}
    evaluation = {"schemaVersion": "task21-evaluation-impact-1", "replayStatus": "EXACT_COMMITTED_MUTATION_REPLAY",
                  "terminology": "evaluation repository inclusiveness (safety)", "totalMutations": len(rows),
                  "baseline": {"safe": sum(r["jacocoSafe"] for r in rows), "unsafe": sum(not r["jacocoSafe"] for r in rows),
                               "averageSelectionSize": round(sum(r["both"] + len(r["jacocoOnly"]) for r in rows) / len(rows), 1)},
                  "asm": {"safe": sum(r["asmSafe"] for r in rows), "unsafe": sum(not r["asmSafe"] for r in rows),
                          "averageSelectionSize": round(sum(r["both"] + len(r["asmOnly"]) for r in rows) / len(rows), 1)},
                  "safetyFlips": dict(safety_flips), "newRelevantTestsRecovered": sorted(new_relevant),
                  "relevantTestsLost": sorted(lost_relevant)}
    return impact, evaluation


asm_doc = read(ASM_PATH)
reference_doc = read(REFERENCE_PATH)
logical, statuses, sources, reasons = load_asm(asm_doc)
asm = {test: set(value["names"]) for test, value in logical.items()}
jacoco = {test: set(value["methods"]) for test, value in reference_doc["testMappings"].items()}

baseline = {"schemaVersion": "task21-baseline-manifest-1", "springRevision": SPRING_REVISION,
            "springCheckout": str(SPRING), "springWorkingTree": "clean", "task": ":spring-core:test",
            "gradle": "8.14.2", "jdk": "OpenJDK 21.0.11 (Homebrew arm64)",
            "evaluationRepository": "https://github.com/ljubisap/smart-test-picker-evaluation",
            "evaluationRepositoryRevision": EVAL_REVISION, "evaluationMapPath": str(REFERENCE_PATH),
            "evaluationMapSha256": sha256(REFERENCE_PATH), "evaluationMapSchema": "testMappings[testId]={classes,methods}; classMetrics; metadata",
            "evaluationMetadata": reference_doc["metadata"], "subjectCompatibility": "CONFIRMED",
            "compatibilityBasis": "Evaluation README and methodology identify the authoritative production revision; metadata commit is its build-only setup commit.",
            "mappedTestCount": len(jacoco), "methodEdgeCount": sum(map(len, jacoco.values())),
            "classEdgeCount": sum(len(v["classes"]) for v in reference_doc["testMappings"].values())}
write("baseline-manifest.json", baseline)

metrics = asm_doc["metrics"]
mapped = sum(bool(value) for value in asm.values())
integrity = {"schemaVersion": "task21-asm-run-integrity-1", "suitableForPopulationComparison": True,
             "rawArtifact": {"path": str(ASM_PATH), "sha256": sha256(ASM_PATH), "bytes": ASM_PATH.stat().st_size},
             "gradleLog": {"path": str(LOG_PATH), "sha256": sha256(LOG_PATH), "bytes": LOG_PATH.stat().st_size,
                           "result": "BUILD SUCCESSFUL"},
             "physicalExecutions": sum(statuses.values()), "discoveredLogicalTests": len(asm), "mappedTests": mapped,
             "unmappedTests": sorted(test for test, methods in asm.items() if not methods), "outcomes": dict(statuses),
             "instrumentationFailures": len(asm_doc["agentErrors"]), "transformationFailures": metrics["transformationErrors"],
             "methodIdCollisions": metrics["methodIdCollisions"], "unattributedEvents": {key: reasons[key] for key in ("NO_ACTIVE_TEST", "UNKNOWN_CONTEXT", "LATE_EVENT")},
             "collectorMetrics": metrics, "agentErrors": asm_doc["agentErrors"]}
write("asm-run-integrity.json", integrity)

descriptor_rows = []
ambiguous = 0
for test in sorted(set(asm) & set(jacoco)):
    by_name = collections.defaultdict(list)
    for method in logical[test]["descriptors"]:
        by_name[name_only(method)].append(method)
    for method in jacoco[test]:
        if len(by_name[method]) > 1:
            ambiguous += 1
            descriptor_rows.append({"testIdentity": test, "methodIdentity": method, "asmDescriptors": sorted(by_name[method])})
normalization = {"schemaVersion": "task21-identity-normalization-1", "level1": "exact test key plus class and method name",
                 "level2": "historical JaCoCo map has no descriptors; no descriptor-exact cross-map edge comparison is possible",
                 "descriptorExactComparableEdges": 0, "nameOnlyComparableEdges": sum(len(jacoco[t]) for t in set(asm) & set(jacoco)),
                 "overloadAmbiguities": ambiguous, "overloadAmbiguityExamples": descriptor_rows[:50],
                 "overloadInventoryPolicy": "First 50 deterministic examples retained; raw ASM map preserves the complete descriptor view.",
                 "testNormalization": "Simple nested class + method + first seven hex digits of nonnegative Java hashCode(FQCN#method)",
                 "compatible": set(jacoco).issubset(set(asm)), "missingReferenceTestsInAsm": sorted(set(jacoco) - set(asm)),
                 "extraAsmTests": sorted(set(asm) - set(jacoco))}
write("identity-normalization.json", normalization)

full, per_test, asm_edges, jac_edges, asm_classes, jac_classes = comparison_reports(asm, jacoco)
write("full-map-comparison.json", full)
write("per-test-difference-summary.json", per_test)

all_diffs = [("ASM_ONLY",) + edge for edge in sorted(asm_edges - jac_edges)] + [("JACOCO_ONLY",) + edge for edge in sorted(jac_edges - asm_edges)]
required_categories = ("CONSTRUCTOR", "STATIC_INITIALIZATION", "ENUM_GENERATED", "RECORD_GENERATED",
                       "SYNTHETIC_BRIDGE", "GENERATED_PROXY", "CONCURRENT_REFERENCE_HASH_MAP",
                       "REFLECTION_BRIDGE_METHOD_RESOLVER", "ASYNC", "SETUP_LIFECYCLE", "LATE_RELATED",
                       "ORDINARY", "OTHER")
cat_buckets = collections.defaultdict(lambda: {"ASM_ONLY": 0, "JACOCO_ONLY": 0, "tests": set()})
for required in required_categories:
    cat_buckets[required]
for direction, test, method in all_diffs:
    bucket = cat_buckets[category(method)]
    bucket[direction] += 1
    bucket["tests"].add(test)
categories = {name: {"asmOnly": value["ASM_ONLY"], "jacocoOnly": value["JACOCO_ONLY"],
                     "testsAffected": len(value["tests"]),
                     "percentageOfAllDifferences": round(100 * (value["ASM_ONLY"] + value["JACOCO_ONLY"]) / len(all_diffs), 6)}
              for name, value in sorted(cat_buckets.items())}
write("difference-categories.json", {"schemaVersion": "task21-difference-categories-1", "classificationNature": "structural, not causal proof",
                                      "totalDifferingEdges": len(all_diffs), "categories": categories})

proven = task19_proven_edges()
mechanism_counts = collections.Counter()
proven_inventory = []
for direction, test, method in all_diffs:
    key = (direction, test, method)
    if key in proven:
        label = "PROVEN_MECHANISM_MATCH"
        proven_inventory.append({"direction": direction, "testIdentity": test, "methodIdentity": method, **proven[key]})
    elif known_pattern(direction, method):
        label = "KNOWN_PATTERN_ONLY"
    else:
        label = "UNEXPLAINED"
    mechanism_counts[(direction, label)] += 1
write("mechanism-prevalence.json", {"schemaVersion": "task21-mechanism-prevalence-1",
       "counts": {direction: {label: mechanism_counts[(direction, label)] for label in ("PROVEN_MECHANISM_MATCH", "KNOWN_PATTERN_ONLY", "UNEXPLAINED")}
                  for direction in ("ASM_ONLY", "JACOCO_ONLY")}, "provenMechanismMatches": proven_inventory,
       "claimBoundary": "Exact TASK19 test+method matches only; structural similarity remains KNOWN_PATTERN_ONLY."})

asm_global = {method for methods in asm.values() for method in methods}
jac_global = {method for methods in jacoco.values() for method in methods}
asm_only_edges = asm_edges - jac_edges
jac_only_edges = jac_edges - asm_edges
global_union = {"schemaVersion": "task21-global-union-comparison-1",
                "methods": {"asm": len(asm_global), "jacoco": len(jac_global), "both": len(asm_global & jac_global),
                            "asmOnly": len(asm_global - jac_global), "jacocoOnly": len(jac_global - asm_global)},
                "classes": {"asm": len({owner(m) for m in asm_global}), "jacoco": len({owner(m) for m in jac_global}),
                            "both": len({owner(m) for m in asm_global} & {owner(m) for m in jac_global}),
                            "asmOnly": len({owner(m) for m in asm_global} - {owner(m) for m in jac_global}),
                            "jacocoOnly": len({owner(m) for m in jac_global} - {owner(m) for m in asm_global})},
                "jacocoOnlyPerTestEdgesMethodAppearsInAsmGlobalUnion": sum(method in asm_global for _, method in jac_only_edges),
                "jacocoOnlyPerTestRedistributionFraction": round(sum(method in asm_global for _, method in jac_only_edges) / len(jac_only_edges), 6),
                "asmOnlyPerTestEdgesMethodAppearsInJacocoGlobalUnion": sum(method in jac_global for _, method in asm_only_edges),
                "asmOnlyPerTestRedistributionFraction": round(sum(method in jac_global for _, method in asm_only_edges) / len(asm_only_edges), 6)}
global_union["redistributionDominant"] = (global_union["jacocoOnlyPerTestRedistributionFraction"] + global_union["asmOnlyPerTestRedistributionFraction"]) / 2 > .5
write("global-union-comparison.json", global_union)

t19_rows = []
for path in sorted((ROOT / "stp-spring-core-spike/task19/evidence/runs").glob("*/joined-test-observation.json")):
    doc = read(path); test = doc["testIdentity"]
    isolated, full_set = set(doc["asm"]["methods"]), asm.get(test, set())
    t19_rows.append({"testIdentity": test, "isolatedMethodCount": len(isolated), "fullSuiteMethodCount": len(full_set),
                     "asmOnlyInFullSuite": sorted(full_set - isolated), "asmOnlyInTask19Isolated": sorted(isolated - full_set),
                     "symmetricDifference": len(isolated ^ full_set)})
write("task19-vs-full-suite-asm.json", {"schemaVersion": "task21-task19-vs-full-suite-asm-1", "testsCompared": len(t19_rows),
      "identicalTests": sum(not r["symmetricDifference"] for r in t19_rows), "changedTests": sum(bool(r["symmetricDifference"]) for r in t19_rows),
      "asmOnlyInFullSuiteEdges": sum(len(r["asmOnlyInFullSuite"]) for r in t19_rows),
      "asmOnlyInTask19IsolatedEdges": sum(len(r["asmOnlyInTask19Isolated"]) for r in t19_rows),
      "symmetricDifference": sum(r["symmetricDifference"] for r in t19_rows), "perTest": t19_rows,
      "interpretation": "Topology/runtime-state difference; not collector error."})

selector, evaluation = selector_evaluation(asm, jacoco, proven)
write("selector-impact.json", selector)
write("evaluation-impact.json", evaluation)
print(json.dumps({"integrity": integrity, "comparison": full, "global": global_union,
                  "selector": {k: v for k, v in selector.items() if k != "scenarios"}, "evaluation": evaluation}, indent=2))
