#!/usr/bin/env python3
"""Build the retained ROUND 17 grouped analysis from canonical ROUND 16 evidence."""

import collections
import hashlib
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
EVIDENCE = HERE / "evidence"
ROUND16 = HERE.parent / "round16" / "evidence"
ROUND15 = HERE.parent / "round15" / "evidence"

CATEGORIES = [
    "JDK_REFLECTION_ORDER", "SPRING_ORDER_SENSITIVE_CONTROL_FLOW", "EXECUTION_ORDER",
    "SHARED_JVM_STATE", "REFERENCE_LIFECYCLE", "STATIC_INITIALIZATION",
    "LATE_EXECUTION", "ASYNC_CONTEXT", "OTHER_PROVEN_RUNTIME_CAUSE",
    "NOT_REPRODUCED_ROUND17", "UNKNOWN",
]


def load(path):
    return json.loads(path.read_text())


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")


def pair(edge):
    return edge["testIdentity"], edge["methodIdentity"]


def edge_view(edge):
    return {
        "testIdentity": edge["testIdentity"],
        "methodIdentity": edge["methodIdentity"],
        "presenceVector": edge["presenceVector"],
    }


def name_only(method):
    return method.split("(", 1)[0]


def group_for(edge):
    method = edge["methodIdentity"]
    test = edge["testIdentity"]
    if "ConcurrentReferenceHashMap" in method:
        if "$SoftEntryReference#" in method:
            return "G02"
        return "G01"
    if test == "BridgeMethodResolverTests#withGenericParameter_5de9f312":
        return "G03"
    if test in {
        "MergedAnnotationsComposedOnSingleAnnotatedElementTests#typeHierarchyStrategyMultipleComposedAnnotationsOnBridgeMethod_7472b95d",
        "MultipleComposedAnnotationsOnSingleAnnotatedElementTests#findMultipleComposedAnnotationsOnBridgeMethod_6ecf9afa",
    }:
        return "G04"
    return "G05"


GROUP_META = {
    "G01": {
        "groupName": "ConcurrentReferenceHashMap restructure and capacity operations",
        "candidateMechanisms": ["map occupancy", "resize restructure", "purge restructure", "execution-order-dependent cache activity"],
        "whyGroupedTogether": {
            "STRUCTURAL_GROUPING": "All edges enter getLoadFactor, Segment#createReferenceArray, or Segment#restructure.",
            "OBSERVED_COMOVEMENT": "Twenty-two test-local triplets have identical five-run vectors; three additional restructure edges move alone.",
            "CAUSAL_EVIDENCE": "The triplets establish one internal operation cluster per test/run, but retained traces do not identify the concrete receiver or distinguish resize from purge for these exact invocations.",
        },
        "sharedCallPathEvidence": "Segment#restructure invokes getLoadFactor and createReferenceArray on resize paths; exact triplet equality is visible in ROUND 16.",
        "result": "INSUFFICIENT_EVIDENCE",
    },
    "G02": {
        "groupName": "ConcurrentReferenceHashMap soft-reference traversal operations",
        "candidateMechanisms": ["bucket-chain traversal", "reference lifecycle", "purge of cleared references", "occupancy-dependent collision traversal"],
        "whyGroupedTogether": {
            "STRUCTURAL_GROUPING": "All edges enter SoftEntryReference#get, #getHash, or #getNext.",
            "OBSERVED_COMOVEMENT": "getHash/getNext pairs move together for three tests; the remaining reference methods have distinct test-local signatures.",
            "CAUSAL_EVIDENCE": "ROUND 11 observed no reclaimed relevant reference; method names and correlated presence do not prove clearing, purge, or a concrete owner.",
        },
        "sharedCallPathEvidence": "The paired getHash/getNext edges are compatible with one bucket/reference traversal, but no retained trace follows exact reference identity.",
        "result": "INSUFFICIENT_EVIDENCE",
    },
    "G03": {
        "groupName": "Proven withGenericParameter reflection-order path",
        "candidateMechanisms": ["JDK Class#getDeclaredMethods order consumed by Spring candidate traversal"],
        "whyGroupedTogether": {
            "STRUCTURAL_GROUPING": "All eight input edges are on the BridgeMethodResolver withGenericParameter candidate-search path.",
            "OBSERVED_COMOVEMENT": "All eight have ROUND 16 vector [1,0,1,1,1], and all are absent in ROUND 15 run 1 and present in run 5.",
            "CAUSAL_EVIDENCE": "ROUND 15 observes the same structural Method set in a different raw order, preserved into candidate order; ROUND 14 observes the resulting early-return versus generic/interface-resolution branch. The retained maps show all eight edges only on the latter branch.",
        },
        "sharedCallPathEvidence": "One raw-order swap changes the first candidate and gates the complete eight-edge downstream path.",
        "result": "GROUP_CAUSE_PROVEN",
    },
    "G04": {
        "groupName": "Correlated annotation bridge-resolution paths",
        "candidateMechanisms": ["reflection order", "Spring order-sensitive traversal", "shared bridge-resolution path"],
        "whyGroupedTogether": {
            "STRUCTURAL_GROUPING": "Two annotation tests each exercise the same ten-method BridgeMethodResolver/ResolvableType/SerializableTypeWrapper/ClassUtils path.",
            "OBSERVED_COMOVEMENT": "All twenty edges share ROUND 16 vector [1,0,1,1,1].",
            "CAUSAL_EVIDENCE": "No target-connected producer-order trace exists for either test. The proven G03 event cannot be generalized from equal vectors and similar methods.",
        },
        "sharedCallPathEvidence": "Method-entry co-presence supports a shared path within each test, not a shared trigger across tests.",
        "result": "INSUFFICIENT_EVIDENCE",
    },
    "G05": {
        "groupName": "Isolated SerializableTypeWrapper unwrap edge",
        "candidateMechanisms": ["memoized type unwrapping", "execution-order-dependent cache state"],
        "whyGroupedTogether": {
            "STRUCTURAL_GROUPING": "The edge is the only remaining singleton outside the four recurrent families.",
            "OBSERVED_COMOVEMENT": "No same-group edge exists; its vector is [1,0,0,0,0].",
            "CAUSAL_EVIDENCE": "ROUND 11 did not obtain a contrasting SerializableTypeWrapper hit/miss proof.",
        },
        "sharedCallPathEvidence": "None beyond the single method-entry edge.",
        "result": "INSUFFICIENT_EVIDENCE",
    },
}


def main():
    source = load(ROUND16 / "round16-causal-classification.json")
    unknown = sorted((edge for edge in source["entries"] if edge["classification"] == "UNKNOWN"), key=pair)
    pairs = [{"testIdentity": x["testIdentity"], "methodIdentity": x["methodIdentity"]} for x in unknown]
    canonical = json.dumps(pairs, separators=(",", ":"), sort_keys=True).encode()
    input_doc = {
        "schemaVersion": "round17-input-unknown-1",
        "source": "stp-spring-core-spike/round16/evidence/round16-causal-classification.json",
        "selection": "entries whose classification is exactly UNKNOWN",
        "sourceUnknownCount": len(unknown),
        "pairCount": len(set(map(pair, unknown))),
        "sha256OfCanonicalSortedPairs": hashlib.sha256(canonical).hexdigest(),
        "edges": pairs,
    }
    if input_doc["sourceUnknownCount"] != 113 or input_doc["pairCount"] != 113:
        raise SystemExit("canonical ROUND 16 UNKNOWN population is not 113 unique pairs")
    write(EVIDENCE / "round17-input-unknown.json", input_doc)

    grouped = collections.defaultdict(list)
    for edge in unknown:
        grouped[group_for(edge)].append(edge)
    groups = []
    for group_id in sorted(grouped):
        edges = grouped[group_id]
        meta = GROUP_META[group_id]
        vectors = collections.Counter("".join(map(str, x["presenceVector"])) for x in edges)
        tests = sorted({x["testIdentity"] for x in edges})
        methods = sorted({x["methodIdentity"] for x in edges})
        classes = sorted({x["methodIdentity"].split("#", 1)[0] for x in edges})
        groups.append({
            "groupId": group_id, "groupName": meta["groupName"], "edgeCount": len(edges),
            "testCount": len(tests), "methodCount": len(methods), "classCount": len(classes),
            "tests": tests, "methods": methods,
            "presenceVectors": [{"signature": list(map(int, key)), "edgeCount": count} for key, count in sorted(vectors.items())],
            "coMovementClusters": [
                {"testIdentity": test, "signature": next(x["presenceVector"] for x in edges if x["testIdentity"] == test),
                 "edgeCount": sum(x["testIdentity"] == test for x in edges)} for test in tests
            ],
            "sharedCallPathEvidence": meta["sharedCallPathEvidence"],
            "candidateMechanisms": meta["candidateMechanisms"], "whyGroupedTogether": meta["whyGroupedTogether"],
            "edges": [edge_view(x) for x in edges],
        })
    if sum(x["edgeCount"] for x in groups) != 113:
        raise SystemExit("groups are not exhaustive")
    write(EVIDENCE / "round17-edge-groups.json", {
        "schemaVersion": "round17-edge-groups-1", "inputEdgeCount": 113,
        "exclusivePrimaryAssignment": True, "groupCount": len(groups), "groups": groups,
    })
    write(EVIDENCE / "round17-group-summary.json", {
        "schemaVersion": "round17-group-summary-1", "inputEdgeCount": 113, "groupCount": len(groups),
        "groups": [{k: g[k] for k in ("groupId", "groupName", "edgeCount", "testCount", "methodCount", "classCount")} for g in groups],
        "populationStructure": {"ConcurrentReferenceHashMapInternals": 84, "bridgeAndReflectionPaths": 28, "isolatedEdges": 1},
    })
    priorities = [
        ("G01", 1, "69 edges and 22 exact triplets; one mechanism could explain most of the population."),
        ("G03", 2, "Eight edges are covered by an existing direct producer-to-consumer causal trace."),
        ("G04", 3, "Twenty perfectly co-moving edges, but target-specific producer traces are absent."),
        ("G02", 4, "Fifteen reference-operation edges; direct identity tracking is unavailable and prior GC evidence was negative."),
        ("G05", 5, "One edge with low expected information gain."),
    ]
    write(EVIDENCE / "round17-group-priority.json", {
        "schemaVersion": "round17-group-priority-1",
        "factors": ["edge count", "co-movement", "shared method family", "reproducibility", "existing instrumentation", "multi-edge explanatory potential"],
        "ranking": [{"groupId": gid, "rank": rank, "rationale": rationale} for gid, rank, rationale in priorities],
        "highPriorityGroups": ["G01", "G03", "G04"],
    })
    clusters = []
    for group in groups:
        by_sig = collections.defaultdict(list)
        for edge in group["edges"]:
            by_sig[tuple(edge["presenceVector"])].append({k: edge[k] for k in ("testIdentity", "methodIdentity")})
        clusters.append({"groupId": group["groupId"], "clusters": [
            {"signature": list(signature), "edgeCount": len(edges), "edges": edges,
             "interpretation": "observed co-movement only; causality requires separate direct evidence"}
            for signature, edges in sorted(by_sig.items())
        ]})
    write(EVIDENCE / "round17-comovement-analysis.json", {
        "schemaVersion": "round17-comovement-analysis-1", "runSource": "ROUND 16 five accepted identical fresh-JVM full runs",
        "newFullSuiteRuns": 0, "reasonNoNewRuns": "The canonical five-run vectors already reproduce movement for every input edge and separate the analysis groups.",
        "groups": clusters,
    })

    # Audit the group-wide extension of the ROUND 14-15 proof against the
    # retained maps. Classification is allowed only when every G03 target is
    # absent/present with the already-traced branch in the selected runs.
    focused = {}
    for run in (1, 5):
        doc = load(ROUND15 / f"focused-run-{run}-map.json")
        focused[run] = {name_only(hit["method"]) for hit in doc["runtimeEvents"]["tests"][0]["methods"]}
    g03_methods = {x["methodIdentity"] for x in grouped["G03"]}
    absent = sorted(g03_methods & focused[1])
    present = sorted(g03_methods & focused[5])
    if absent or set(present) != g03_methods:
        raise SystemExit("ROUND 15 maps do not establish the complete G03 absent/present path")
    write(HERE / "groups" / "G03" / "evidence" / "group-path-contrast.json", {
        "schemaVersion": "round17-g03-path-contrast-1",
        "testIdentity": "BridgeMethodResolverTests#withGenericParameter_5de9f312",
        "absentRun": 1, "presentRun": 5,
        "sameRawStructuralSet": True, "rawOrderDifferent": True,
        "firstDifferingStage": "Class#getDeclaredMethods order",
        "firstDifferingDecision": "BridgeMethodResolver candidate 0 direct type match",
        "absentTargetMethods": absent, "presentTargetMethods": present,
        "allEightTargetsMoveWithTracedBranch": True,
        "sourceEvidence": [
            "round15/evidence/round15-present-absent-order-diff.json",
            "round15/evidence/round15-candidate-propagation.json",
            "round14/evidence/round14-present-absent-control-flow-diff.json",
            "round15/evidence/focused-run-1-map.json",
            "round15/evidence/focused-run-5-map.json",
        ],
    })

    triplets = []
    triplet_methods = {
        "org.springframework.util.ConcurrentReferenceHashMap#getLoadFactor",
        "org.springframework.util.ConcurrentReferenceHashMap$Segment#createReferenceArray",
        "org.springframework.util.ConcurrentReferenceHashMap$Segment#restructure",
    }
    for test in sorted({x["testIdentity"] for x in grouped["G01"]}):
        selected = [x for x in grouped["G01"] if x["testIdentity"] == test and x["methodIdentity"] in triplet_methods]
        if {x["methodIdentity"] for x in selected} == triplet_methods and len({tuple(x["presenceVector"]) for x in selected}) == 1:
            triplets.append({"testIdentity": test, "signature": selected[0]["presenceVector"], "methods": sorted(triplet_methods)})
    if len(triplets) != 22:
        raise SystemExit("expected 22 exact G01 operation triplets")
    write(HERE / "groups" / "G01" / "evidence" / "operation-clusters.json", {
        "schemaVersion": "round17-g01-operation-clusters-1", "clusterCount": len(triplets),
        "representedEdges": 3 * len(triplets), "clusters": triplets,
        "causalLimit": "Method-entry shape and co-movement do not identify receiver, owner, occupancy, or restructure reason.",
    })

    proven = {pair(x) for x in grouped["G03"]}
    final = []
    for edge in unknown:
        category = "JDK_REFLECTION_ORDER" if pair(edge) in proven else "UNKNOWN"
        evidence = ({
            "patternId": "P01",
            "directCausalChain": "Class#getDeclaredMethods order -> ReflectionUtils traversal -> BridgeMethodResolver candidate order -> early return versus generic/interface-resolution path -> all listed method entries absent/present together",
            "presentAbsentMaps": ["round15/focused-run-1-map.json", "round15/focused-run-5-map.json"],
            "scope": "only BridgeMethodResolverTests#withGenericParameter and the eight listed ROUND 17 edges",
        } if category != "UNKNOWN" else {
            "reason": "No direct controlled event/path explains this edge; structural grouping and presence correlation are insufficient."
        })
        final.append({**edge_view(edge), "groupId": group_for(edge), "classification": category, "evidence": evidence})
    write(EVIDENCE / "round17-final-edge-classification.json", {
        "schemaVersion": "round17-final-edge-classification-1", "inputEdgeCount": 113, "entries": final,
    })
    counts = collections.Counter(x["classification"] for x in final)
    summary_counts = {category: counts[category] for category in CATEGORIES}
    group_results = collections.Counter(GROUP_META[x]["result"] for x in GROUP_META)
    final_summary = {
        "schemaVersion": "round17-final-summary-1", "inputUnknownEdges": 113,
        "classificationCounts": summary_counts, "classificationTotal": sum(summary_counts.values()),
        "groupsInvestigated": 5, "groupsWithOneProvenSharedCause": group_results["GROUP_CAUSE_PROVEN"],
        "groupsWithMultipleProvenCauses": 0, "groupsPartiallyExplained": 0,
        "groupsWithNoSharedCause": 0, "groupsNotReproduced": 0,
        "groupsInsufficient": group_results["INSUFFICIENT_EVIDENCE"],
        "edgesExplainedByGroupLevelPattern": 8, "edgesExplainedIndividually": 0, "edgesRemainingUnknown": counts["UNKNOWN"],
        "patternResult": "NO",
        "patternResultRationale": "One proven pattern explains 8/113 edges (7.08%); 105/113 remain UNKNOWN across four groups. This is not a material collapse into a small recurring-mechanism set.",
        "collectorCorrectnessDefectFound": False, "newFullSuiteRuns": 0,
    }
    if final_summary["classificationTotal"] != 113:
        raise SystemExit("final classification does not total 113")
    write(EVIDENCE / "round17-final-summary.json", final_summary)
    pattern_edges = [{"testIdentity": x["testIdentity"], "methodIdentity": x["methodIdentity"]} for x in final if x["classification"] == "JDK_REFLECTION_ORDER"]
    write(EVIDENCE / "round17-causal-pattern-catalog.json", {
        "schemaVersion": "round17-causal-pattern-catalog-1", "patternCount": 1,
        "patterns": [{
            "patternId": "P01", "name": "Reflection result order gates a multi-method BridgeMethodResolver path",
            "trigger": "Class#getDeclaredMethods returns the same three-method structural set with the two non-bridge candidates swapped.",
            "runtimeMechanism": "ReflectionUtils preserves raw order; BridgeMethodResolver preserves candidate insertion order; the Class candidate returns early while the Integer candidate enters generic/interface resolution.",
            "affectedGroups": ["G03"], "affectedEdges": pattern_edges,
            "evidence": ["round15-raw-reflection-orders.json", "round15-candidate-propagation.json", "round14-present-absent-control-flow-diff.json", "round15 focused maps"],
            "controlledProof": "ROUND 15 fresh JVM runs 1 and 5 have the same structural Method set but different order and opposite presence for all eight edges; ROUND 14 locates the first differing decision.",
            "scopeLimits": "Does not classify G04 despite equal ROUND 16 vectors and similar method families; those tests lack a target-connected producer trace.",
        }],
    })
    write(EVIDENCE / "run-manifest.json", {
        "schemaVersion": "stp-asm-round17-runs-1",
        "stpBaselineCommit": "67ce76bb877e6aa87f34bd3be87677532f2206d0",
        "baselineTag": "asm-spring-stability-baseline",
        "springCommit": "99a366baf6640b275d08dde60f05da719139bb6a",
        "task": ":spring-core:test", "gradle": "8.14.2", "jdk": "21.0.11",
        "topology": "ROUND 16 normal single-test-task topology; fresh worker JVM per accepted run",
        "newFullSuiteRuns": 0, "reusedAcceptedFullRuns": 5,
        "reason": "Existing canonical presence vectors reproduce all input movement and suffice for group selection.",
        "springSourceModified": False, "normalMapSemanticsModified": False,
        "selectorSemanticsModified": False, "collectorDiagnosticsAdded": False,
    })

    for group in groups:
        group_dir = HERE / "groups" / group["groupId"]
        write(group_dir / "targets.json", {"schemaVersion": "round17-group-targets-1", "groupId": group["groupId"], "edges": group["edges"]})
        write(group_dir / "evidence" / "evidence-index.json", {
            "schemaVersion": "round17-group-evidence-index-1", "groupId": group["groupId"],
            "retainedEvidenceOnly": True, "newRuntimeCapture": False,
            "sources": {
                "G01": ["round16-causal-classification.json", "round11-state-groups.json", "round11-gc-reference-analysis.json"],
                "G02": ["round16-causal-classification.json", "round11-gc-reference-analysis.json", "round12-retention-analysis.json"],
                "G03": ["round15-present-absent-order-diff.json", "round15-candidate-propagation.json", "round14-present-absent-control-flow-diff.json", "round15 focused maps"],
                "G04": ["round16-causal-classification.json", "round11-controlled-order-proof.json"],
                "G05": ["round16-causal-classification.json", "round11-state-groups.json"],
            }[group["groupId"]],
        })
        write(group_dir / "result.json", {
            "schemaVersion": "round17-group-result-1", "groupId": group["groupId"],
            "primaryResult": GROUP_META[group["groupId"]]["result"],
            "edgeCount": group["edgeCount"],
            "classifiedEdges": 8 if group["groupId"] == "G03" else 0,
            "unknownEdges": 0 if group["groupId"] == "G03" else group["edgeCount"],
            "answer": GROUP_META[group["groupId"]]["whyGroupedTogether"]["CAUSAL_EVIDENCE"],
        })


if __name__ == "__main__":
    main()
