#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0
"""Reconstruct TASK 20's immutable input from the committed TASK 19 joins."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TASK19 = ROOT / "stp-spring-core-spike/task19/evidence"
OUTPUT = ROOT / "stp-spring-core-spike/task20/evidence/jacoco-fn-inventory.json"


def category(method):
    if "ConcurrentReferenceHashMap" in method:
        return "CONCURRENT_REFERENCE_HASH_MAP"
    if "BridgeMethodResolver" in method or "ReflectionUtils" in method or "reflect" in method.lower():
        return "REFLECTION"
    if method.endswith("#<clinit>"):
        return "STATIC_INITIALIZATION"
    if method.endswith("#<init>"):
        return "CONSTRUCTOR"
    if any(token in method for token in ("CompletableFuture", "Async", "Executor", "Thread", "DataBufferUtils")):
        return "ASYNC"
    if any(token in method.lower() for token in ("setup", "before", "after", "lifecycle")):
        return "SETUP_LIFECYCLE"
    return "ORDINARY"


def main():
    records = []
    for path in sorted((TASK19 / "runs").glob("*/joined-test-observation.json")):
        document = json.loads(path.read_text())
        for item in document["comparison"]["perMethod"]:
            if item["classification"] != "ASM_TRUE_POSITIVE_JACOCO_FALSE_NEGATIVE":
                continue
            class_name, method_name = item["methodIdentity"].split("#", 1)
            records.append({
                "testIdentity": document["testIdentity"],
                "methodIdentity": item["methodIdentity"],
                "className": class_name,
                "methodName": method_name,
                "descriptorCandidates": [value[len(item["methodIdentity"]):]
                                         for value in item["level2Descriptors"]],
                "task19Category": category(item["methodIdentity"]),
                "oracleOwnership": item["oracleOwnership"],
                "asmPresent": item["asmPresent"],
                "jacocoPresent": item["jacocoPresent"],
            })
    if len(records) != 57:
        raise SystemExit(f"STOP: expected exactly 57 TASK 19 JaCoCo FNs, reconstructed {len(records)}")
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps({"schemaVersion": "task20-jacoco-fn-inventory-1",
                                  "source": "task19/evidence/runs/*/joined-test-observation.json",
                                  "total": len(records), "edges": records},
                                 indent=2, sort_keys=True) + "\n")


if __name__ == "__main__":
    main()
