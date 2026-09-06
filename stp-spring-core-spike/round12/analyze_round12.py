#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0

import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
E = HERE / "evidence"
R9 = HERE.parent / "round9" / "evidence"
R11 = HERE.parent / "round11" / "evidence"

TARGETS = [
    ("ResolvableTypeTests#classWithGenericsAs_1378ee35", "org.springframework.util.ConcurrentReferenceHashMap$Segment#restructure",
     "Repeated map-internal movement on a ResolvableType test; tests whether prior cache population causes a resize/restructure.",
     "suspected ResolvableType.cache; exact ConcurrentReferenceHashMap receiver not proven"),
    ("ResolvableTypeTests#forMethodParameterWithNestingAndLevels_3ac7fcf8", "org.springframework.util.ConcurrentReferenceHashMap$SoftEntryReference#get",
     "Repeated reference-entry movement (runs 1 and 3) on a ResolvableType test; representative retained-reference read.",
     "suspected ResolvableType.cache; exact ConcurrentReferenceHashMap receiver not proven"),
    ("BridgeMethodResolverTests#withGenericParameter_5de9f312", "org.springframework.core.ResolvableType#getInterfaces",
     "Direct ResolvableType-owned UNKNOWN edge on the BridgeMethodResolver miss path, contrasting with map internals.",
     "BridgeMethodResolver.cache and ResolvableType.cache are implicated by the call path"),
    ("MapToMapConverterTests#collectionMapSourceTarget_5bff386f", "org.springframework.core.SerializableTypeWrapper#unwrap",
     "Non-ConcurrentReferenceHashMap control with a directly implicated cache owner.",
     "SerializableTypeWrapper.cache"),
]

def load(path):
    return json.loads(path.read_text())

def write(name, value):
    (E / name).write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")

def short_test(full):
    return full.split(".")[-1]

def method_names(map_doc, wanted_test):
    result = set()
    for test in map_doc["runtimeEvents"]["tests"]:
        identity = f'{test["testClass"]}#{test["testMethod"]}'
        if identity == wanted_test:
            result.update(item["method"].split("(", 1)[0] for item in test["methods"])
    return result

def main():
    inventory = load(R11 / "round11-unknown-edge-inventory.json")["edges"]
    index = {(x["testIdentity"], x["methodIdentity"]): x for x in inventory}
    target_docs = []
    for test, method, reason, owner in TARGETS:
        item = index[(test, method)]
        target_docs.append({
            "testIdentity": test,
            "methodIdentity": method,
            "round10PresenceAbsencePattern": {"presentInRuns": item["round10RunPresence"],
                "absentInRuns": [n for n in (1, 2, 3) if n not in item["round10RunPresence"]]},
            "reasonSelected": reason,
            "suspectedStateOwner": owner,
            "suspectedIsProof": False,
        })
    write("round12-targets.json", {"schemaVersion": "round12-targets-1", "targetCount": 4, "targets": target_docs})

    raw_timeline = [json.loads(line) for line in (E / "timeline.jsonl").read_text().splitlines()]
    starts = {x["logicalUniqueId"]: x for x in raw_timeline if x["event"] == "START"}
    tests = []
    for end in (x for x in raw_timeline if x["event"] == "END"):
        start = starts[end["logicalUniqueId"]]
        tests.append({"testIdentity": end["testIdentity"], "logicalTestIdentity": end["logicalUniqueId"],
            "startTime": start["startTime"], "endTime": end["endTime"],
            "thread": {"javaThreadId": start["startThreadId"], "name": start["startThreadName"]},
            "propagatedLogicalWorkObserved": False})
    write("round12-test-timeline.json", {"schemaVersion": "round12-timeline-1",
        "clock": "java.time.Instant wall clock from the same test JVM as JFR; System.nanoTime retained in raw timeline.jsonl",
        "tests": tests})

    allocation = []
    for target in target_docs:
        allocation.append({"testIdentity": target["testIdentity"], "methodIdentity": target["methodIdentity"],
            "relevantAllocationObserved": False, "allocationTestIdentity": None, "allocationStack": None,
            "exactObjectInstanceIdentified": False, "confidence": "high for absence from JFR samples; no inference about unsampled allocations",
            "evidence": "No allocation event named ResolvableType, ConcurrentReferenceHashMap/Segment/SoftEntryReference, SerializableTypeWrapper, or BridgeMethodResolver. JFR allocation events are samples/TLAB events, not an allocation census."})
    write("round12-allocation-analysis.json", {"schemaVersion": "round12-allocation-1",
        "recordingEventCounts": {"ObjectAllocationInNewTLAB": 440, "ObjectAllocationOutsideTLAB": 50,
            "ObjectAllocationSample": 431}, "focusedRelevantAllocationEventCount": 0, "targets": allocation})

    retention = []
    for target in target_docs:
        retention.append({"testIdentity": target["testIdentity"], "methodIdentity": target["methodIdentity"],
            "relevantOldObjectSampleObserved": False, "objectClass": None, "allocationStack": None,
            "age": None, "retainingPath": None, "gcRoot": None, "relevantCacheMapOrFieldInPath": None,
            "sameInstanceLaterObserved": False, "sameInstanceIdentity": "same-instance identity not proven"})
    write("round12-retention-analysis.json", {"schemaVersion": "round12-retention-1", "oldObjectSampleCount": 20,
        "pathToGcRootsEnabled": True, "relevantOldObjectSampleCount": 0,
        "note": "Usable root paths existed for unrelated samples (for example JDK locale caches), demonstrating capture operation, but none sampled a selected Spring object or retaining path.",
        "targets": retention})

    write("round12-heap-retention-analysis.json", {"schemaVersion": "round12-heap-retention-1", "heapDumpUsed": False,
        "reason": "JFR found no relevant sampled instance to anchor a targeted heap query. An end-of-subset dump could enumerate equal-class objects but could not associate an instance with an earlier TestIdentity or the moving edge; an exact between-test trigger would add behavior-changing control instrumentation. It would not materially answer the selected causal questions."})

    focused = load(E / "focused-map.json")
    isolated = load(E / "contrast-isolated-map.json")
    contrast_test = "org.springframework.core.ResolvableTypeTests#classWithGenericsAs"
    edge = TARGETS[0][1]
    focused_methods = method_names(focused, contrast_test)
    isolated_methods = method_names(isolated, contrast_test)
    write("round12-controlled-contrast.json", {"schemaVersion": "round12-contrast-1",
        "case": {"testIdentity": TARGETS[0][0], "methodIdentity": edge},
        "dimensions": {"warm": "BridgeMethodResolver test and another ResolvableType test ran first in the same fresh JVM",
            "cold": "classWithGenericsAs ran alone in a separate fresh JVM", "otherwiseIdentical": "JDK 21, Gradle 8.14.2, revision, task, agent configuration"},
        "warmEdgeObserved": edge in focused_methods, "coldEdgeObserved": edge in isolated_methods,
        "result": "The selected restructure edge was absent in both focused executions. The contrast did not reproduce ROUND 10 movement and supplies no allocation/retention/reuse linkage; object history is neither proven nor directly rejected."})

    classifications = []
    for target in target_docs:
        classifications.append({"testIdentity": target["testIdentity"], "methodIdentity": target["methodIdentity"],
            "classification": "INSUFFICIENT_EVIDENCE",
            "claims": {"allocatedByEarlierSpecificTest": False, "retainedAfterEarlierTest": False,
                "retainedThroughSuspectedStructure": False, "sameInstanceUsedByLaterTest": False,
                "movingEdgeChangedBecauseOfState": False},
            "reason": "Relevant allocation sample, retaining path, exact instance identity, and later reuse were not established."})
    write("round12-edge-classification.json", {"schemaVersion": "round12-classification-1",
        "counts": {"OBJECT_HISTORY_PROVEN": 0, "OBJECT_HISTORY_REJECTED": 0, "INSUFFICIENT_EVIDENCE": 4},
        "classifications": classifications})

    reference = load(R9 / "reference-spring-core-map.json")["testMappings"]
    global_methods = {m for record in reference.values() for m in record.get("methods", [])}
    comparisons = []
    for target in target_docs:
        test, method = target["testIdentity"], target["methodIdentity"]
        if method in reference.get(test, {}).get("methods", []): status = "ASSIGNS_TO_SAME_TEST"
        elif method in global_methods: status = "ASSIGNS_TO_ANOTHER_TEST"
        else: status = "DOES_NOT_CONTAIN_OR_DISTINGUISH_EDGE"
        comparisons.append({"testIdentity": test, "methodIdentity": method, "jacocoStatus": status})
    write("round12-jacoco-comparison.json", {"schemaVersion": "round12-jacoco-1",
        "provenObjectHistoryEdges": 0, "comparisonRequiredForProvenEdge": False,
        "regenerated": False, "reference": "existing ROUND 9 JaCoCo map", "selectedTargetsForContextOnly": comparisons,
        "conclusion": "No object-history causality was proven, so the conditional JaCoCo comparison is not applicable. The reference exposes ordinary coverage assignment only and no prior-object/JVM-history evidence."})

    write("round12-summary.json", {"schemaVersion": "round12-summary-1", "targets": 4,
        "jfrRecording": "round12-recording.jfr", "jfrValid": True, "heapDumpUsed": False,
        "jfrCounts": {"allocationEvents": 921, "oldObjectSamples": 20, "relevantAllocationEvents": 0, "relevantOldObjectSamples": 0},
        "controlledContrast": "selected ResolvableType restructure edge absent in both warm and isolated fresh-JVM cases",
        "classifications": {"OBJECT_HISTORY_PROVEN": 0, "OBJECT_HISTORY_REJECTED": 0, "INSUFFICIENT_EVIDENCE": 4},
        "conclusion": "JFR/runtime evidence does not establish object-history causality for the selected cases.",
        "sameInstanceIdentityProven": False, "springSourceModified": False, "normalProductionMapSemanticsModified": False})

if __name__ == "__main__":
    main()
