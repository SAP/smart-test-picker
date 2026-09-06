#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0
"""Reconstruct TASK 19's sampling population from the retained ROUND 9 inputs.

The large inputs intentionally stay outside Git. Paths may be overridden so the
script also works against a restored research bundle.
"""

import argparse
import collections
import json
from pathlib import Path


def category(method: str) -> str:
    if "ConcurrentReferenceHashMap" in method:
        return "CONCURRENT_REFERENCE_HASH_MAP"
    if "BridgeMethodResolver" in method or "ReflectionUtils" in method or "reflect" in method.lower():
        return "REFLECTION_BRIDGE_METHOD_RESOLVER"
    if method.endswith("#<clinit>"):
        return "STATIC_INITIALIZATION"
    if method.endswith("#<init>"):
        return "CONSTRUCTOR"
    if any(token in method for token in ("CompletableFuture", "Async", "Executor", "Thread", "DataBufferUtils")):
        return "ASYNC"
    if any(token in method.lower() for token in ("setup", "before", "after", "lifecycle")):
        return "SETUP_LIFECYCLE"
    if any(token in method for token in ("$$", "$Proxy", "CGLIB", "ByteBuddy", "Subclass")):
        return "GENERATED_PROXY"
    return "ORDINARY_SYNCHRONOUS"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--semantic-diff", type=Path, required=True)
    parser.add_argument("--descriptor-view", type=Path, required=True)
    parser.add_argument("--late-analysis", type=Path)
    parser.add_argument("--output", type=Path,
                        default=Path(__file__).parent / "evidence/divergent-edge-inventory.json")
    args = parser.parse_args()
    diff = json.loads(args.semantic_diff.read_text())
    descriptors = json.loads(args.descriptor_view.read_text())
    edges = []
    controls = []
    for test, values in sorted(diff["methodDifferences"].items()):
        for direction, asm_present, jacoco_present in (("leftOnly", False, True), ("rightOnly", True, False)):
            for method in values[direction]:
                edges.append({
                    "testIdentity": test,
                    "methodIdentity": method,
                    "asmPresent": asm_present,
                    "jacocoPresent": jacoco_present,
                    "differenceType": "ASM_ONLY" if asm_present else "JACOCO_ONLY",
                })

    # Deterministic positive controls: up to two intersections for every divergent test.
    # The descriptor view contains the normalized union and exposes which side(s) supplied descriptors.
    for test in sorted(diff["methodDifferences"]):
        divergent = set(diff["methodDifferences"][test]["leftOnly"] + diff["methodDifferences"][test]["rightOnly"])
        candidates = [method for method in sorted(descriptors.get(test, {})) if method not in divergent]
        for method in candidates[:2]:
            controls.append({"testIdentity": test, "methodIdentity": method, "asmPresent": True,
                             "jacocoPresent": True, "differenceType": "BOTH_PRESENT"})
        if len(controls) >= 64:
            break

    counts = collections.Counter(edge["differenceType"] for edge in edges)
    categories = collections.Counter(category(edge["methodIdentity"]) for edge in edges)
    grouped = collections.defaultdict(list)
    for edge in edges:
        grouped[category(edge["methodIdentity"])].append(edge)
    grouped["OTHER"] = []
    if args.late_analysis:
        late_document = json.loads(args.late_analysis.read_text())
        late_tests = {entry["testIdentity"] for entry in late_document["lateEventsInvolvingUnstableIdentities"]}
        grouped["LATE_EVENT_RELATED"] = [edge for edge in edges if edge["testIdentity"] in late_tests]
    categories.update({key: len(value) for key, value in grouped.items() if key in ("OTHER", "LATE_EVENT_RELATED")})
    document = {
        "schemaVersion": "task19-divergent-edge-inventory-1",
        "historicalEvidenceOnly": True,
        "direction": {"left": "JaCoCo", "right": "ASM"},
        "normalizationLevel": "class + method name",
        "edgeCount": len(edges),
        "testCount": len({edge["testIdentity"] for edge in edges}),
        "countsByDifferenceType": dict(sorted(counts.items())),
        "countsByCategory": dict(sorted(categories.items())),
        "samplingStrataMayOverlap": True,
        "edgesByCategory": dict(sorted(grouped.items())),
        "bothPresentSamples": controls,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    # This canonical inventory is intentionally compact: the 25k required edges
    # stay below the repository's large-artifact threshold without losing data.
    args.output.write_text(json.dumps(document, separators=(",", ":"), sort_keys=True) + "\n")


if __name__ == "__main__":
    main()
