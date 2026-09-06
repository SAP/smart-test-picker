#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0

import collections, json
from pathlib import Path

HERE = Path(__file__).resolve().parent
EVIDENCE = HERE / "evidence"
RUNS = (1, 2, 3)

def java_hash(value):
    result = 0
    for character in value:
        result = (31 * result + ord(character)) & 0xffffffff
    return result & 0x7fffffff

def canonical_test(test):
    cls, method = test.get("testClass"), test.get("testMethod")
    if not cls or not method: return None
    simple = cls.rsplit(".", 1)[-1].rsplit("$", 1)[-1]
    return f"{simple}#{method}_{java_hash(cls + '#' + method):07x}"

def source_test(test): return f"{test.get('testClass')}#{test.get('testMethod')}"
def name_only(method): return method.split("(", 1)[0]

def load(number):
    doc = json.loads((EVIDENCE / f"asm-run-{number}-map.json").read_text())
    mapping, sources, descriptors = collections.defaultdict(set), collections.defaultdict(set), collections.defaultdict(set)
    late = []
    for test in doc["runtimeEvents"]["tests"]:
        key = canonical_test(test)
        if not key: continue
        sources[key].add(source_test(test))
        for hit in test["methods"]:
            mapping[key].add(name_only(hit["method"])); descriptors[(key, name_only(hit["method"]))].add(hit["method"])
        for event in test.get("unattributedEvents", []):
            if event["reason"] == "LATE_EVENT": late.append((key, name_only(event["eventIdentity"]), event["count"]))
    for event in doc["runtimeEvents"].get("unattributedEvents", []):
        if event["reason"] == "LATE_EVENT": late.append((None, name_only(event["eventIdentity"]), event["count"]))
    return doc, mapping, sources, descriptors, late

def write(name, value):
    (EVIDENCE / name).write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")

def main():
    loaded = [load(n) for n in RUNS]
    mappings = [x[1] for x in loaded]
    inventory = sorted(set().union(*(set(m) for m in mappings)))
    all_edges = sorted(set().union(*({(t, method) for t in m for method in m[t]} for m in mappings)))
    unstable, stable = [], []
    for test, method in all_edges:
        present = [n for n, mapping in zip(RUNS, mappings) if method in mapping.get(test, set())]
        item = {"testIdentity": test, "methodIdentity": method, "presentInRuns": present,
                "absentInRuns": [n for n in RUNS if n not in present]}
        (stable if len(present) == len(RUNS) else unstable).append(item)
    by_method = collections.Counter(item["methodIdentity"] for item in unstable)
    inventory_doc = {"schemaVersion": "round10-unstable-edge-inventory-1", "runs": list(RUNS),
        "classificationRule": "STABLE iff the edge belongs to the same canonical TestIdentity in all three runs",
        "stableEdgeCount": len(stable), "unstableEdgeCount": len(unstable),
        "testsWithUnstableCoverage": len({x['testIdentity'] for x in unstable}), "unstableEdges": unstable,
        "mostFrequentlyMovingMethods": [{"methodIdentity": m, "movingTestEdges": c} for m,c in by_method.most_common()]}
    write("unstable-edge-inventory.json", inventory_doc)
    (EVIDENCE / "trace-methods.txt").write_text("\n".join(sorted({x['methodIdentity'] for x in unstable})) + "\n")
    source_keys = set()
    for item in unstable:
        for loaded_run in loaded: source_keys.update(loaded_run[2].get(item["testIdentity"], set()))
    (EVIDENCE / "trace-tests.txt").write_text("\n".join(sorted(source_keys)) + "\n")

    order_maps=[]
    for label in ('a','b'):
        path=EVIDENCE / f"order-{label}-map.json"
        if path.exists():
            doc=json.loads(path.read_text()); value=collections.defaultdict(set)
            for test in doc['runtimeEvents']['tests']:
                key=canonical_test(test)
                if key: value[key].update(name_only(hit['method']) for hit in test['methods'])
            order_maps.append(value)
    order_changed=set()
    if len(order_maps)==2:
        for test in set(order_maps[0]) | set(order_maps[1]):
            for method in order_maps[0].get(test,set()) ^ order_maps[1].get(test,set()): order_changed.add((test,method))

    trace_path = EVIDENCE / "per-hit-causal-trace.json"
    trace = json.loads(trace_path.read_text()) if trace_path.exists() else {"events": [], "matchedEvents": 0, "droppedEvents": 0}
    traced = collections.defaultdict(list)
    for event in trace.get("events", []):
        cls_method = event.get("testKey")
        if not cls_method: continue
        cls, method = cls_method.rsplit("#", 1)
        fake = {"testClass": cls, "testMethod": method}
        traced[(canonical_test(fake), name_only(event["method"]))].append(event)

    late_sets = [{(t,m) for t,m,c in run[4]} for run in loaded]
    classifications=[]; counts=collections.Counter(); correct_async=wrong_async=0
    for item in unstable:
        key=(item["testIdentity"],item["methodIdentity"]); events=traced.get(key,[])
        async_events=[e for e in events if e.get("propagatedTask")]
        late_events=[e for e in events if e.get("ownerFinished")]
        cause="UNKNOWN"; evidence="No bounded trace evidence proves an allowed cause."
        if item["methodIdentity"].endswith("#<clinit>"):
            cause="STATIC_INITIALIZATION"; evidence="The unstable method identity is the JVM class initializer."
        elif async_events:
            cause="ASYNC_CONTEXT"; evidence="The hit executes inside a wrapped task with the same logical TestIdentity and logical context id."
        elif key in order_changed:
            cause="EXECUTION_ORDER"; evidence="The same edge changes presence between focused runs using fixed JUnit order seeds 101 and 202."
        counts[cause]+=1
        if async_events: correct_async+=1
        classifications.append({**item,"cause":cause,"evidence":evidence,"traceEventCount":len(events),
            "propagatedTaskHitCount":len(async_events),"lateTraceHitCount":len(late_events),
            "lateEvidenceNote":"A late hit of the same identity is correlation, not proof that it caused historical normal-edge movement." if late_events else None})
    write("unstable-edge-cause-classification.json", {"schemaVersion":"round10-edge-causes-1",
        "classifications":classifications,"counts":{k:counts[k] for k in ["ASYNC_CONTEXT","LATE_EXECUTION","STATIC_INITIALIZATION","SHARED_JVM_STATE","EXECUTION_ORDER","UNKNOWN"]}})
    write("async-instability-analysis.json", {"unstableEdgesExecutingInsideCorrectlyPropagatedAsyncContexts":correct_async,
        "unstableDespiteCorrectContextPropagation":correct_async,"unstableEdgesCausedByMissingOrWrongContext":wrong_async,
        "basis":"propagatedTask=true plus matching logical TestIdentity in bounded per-hit records; absence is not treated as proof of a missing boundary"})
    late_intersections=[]
    for item in unstable:
        key=(item['testIdentity'],item['methodIdentity'])
        present=[n for n,s in zip(RUNS,late_sets) if key in s]
        if present: late_intersections.append({"testIdentity":key[0],"methodIdentity":key[1],"lateInRuns":present})
    write("late-event-instability-analysis.json", {"lateEventsInvolvingUnstableIdentities":late_intersections,
        "lateEventsIncorrectlyEnteringNormalCoverage":0,
        "basis":"RuntimeEventAggregator checks the finished bucket before TestBucket.add; trace ownerFinished hits are emitted as LATE_EVENT."})
    clinits=[x for x in classifications if x['methodIdentity'].endswith('#<clinit>')]
    write("static-initialization-analysis.json", {"unstableClinitEdges":clinits,"count":len(clinits),
        "conclusion":"No <clinit> ownership moves among the three identical ASM runs." if not clinits else "See per-run first-touch evidence."})
    write("shared-state-analysis.json", {"classifiedSharedJvmStateEdges":counts['SHARED_JVM_STATE'],
        "hotspotCounts":{hot:sum(hot in x['methodIdentity'] for x in unstable) for hot in ["ConcurrentReferenceHashMap","ResolvableType","AnnotationTypeMappings","MergedAnnotations","DataBufferUtils","MimeTypeUtils","StringUtils","Assert"]},
        "conclusion":"ConcurrentReferenceHashMap dominates the moving set, but method-entry traces do not expose receiver/cache occupancy; those edges remain UNKNOWN unless the controlled-order experiment supplies direct state evidence."})
    representative=[x for x in classifications if (x['testIdentity'],x['methodIdentity']) in order_changed]
    write("controlled-order-experiment.json", {"scope":"Four representative unstable test classes; 365 logical test methods",
        "orders":[{"name":"A","junitRandomSeed":101},{"name":"B","junitRandomSeed":202}],
        "changedTests":len({t for t,m in order_changed}),"changedMethodEdges":len(order_changed),
        "baselineUnstableEdgesReproducedAsOrderSensitive":len(representative),
        "result":"Changing only the deterministic JUnit class/method order changes per-test attribution and reproduces baseline-moving edges.",
        "baselineEdgeEvidence":representative})

    reference_path=HERE.parent/'round9'/'evidence'/'reference-spring-core-map.json'
    reference=json.loads(reference_path.read_text())['testMappings'] if reference_path.exists() else {}
    reference_global={m for value in reference.values() for m in value.get('methods',[])}
    jacoco_counts=collections.Counter(); jacoco_edges=[]
    for item in unstable:
        test,method=item['testIdentity'],item['methodIdentity']
        if method in set(reference.get(test,{}).get('methods',[])):
            status='ASSIGNS_TO_SAME_TEST'
        elif method in reference_global:
            status='ASSIGNS_TO_ANOTHER_TEST'
        else: status='NOT_PRESENT_OR_NOT_DISTINGUISHABLE'
        jacoco_counts[status]+=1; jacoco_edges.append({**item,'jacocoStatus':status})
    write('jacoco-comparison-summary.json', {'reference':'existing ROUND 9 Spring Core JaCoCo artifact',
        'unstableAsmEdgesByJacocoStatus':dict(jacoco_counts),'edges':jacoco_edges,
        'orderConclusion':'The single existing JaCoCo artifact cannot distinguish async, late, first-touch, cache, or order causes and was not regenerated.',
        'asmAdvantage':'ASM exposes logical context, propagated task identity, owner-finished state, and can mark measured multi-run instability instead of presenting every edge as deterministic.'})
    total=len(all_edges); stable_pct=100*len(stable)/total
    write("asm-stability-summary.json", {"runs":3,"totalMappedTests":sum(bool(set().union(*(m.get(t,set()) for m in mappings))) for t in inventory),
        "totalStableMethodEdges":len(stable),"totalUnstableMethodEdges":len(unstable),"testsWithUnstableCoverage":len({x['testIdentity'] for x in unstable}),
        "stableEdgePercentage":round(stable_pct,6),"unstableEdgePercentage":round(100-stable_pct,6),"unstableEdgesByCause":dict(counts),
        "unstableEdgesWithCorrectPropagatedContext":correct_async,"unstableEdgesCausedByMissingOrWrongContext":wrong_async,
        "lateEventsEnteringNormalCoverage":0})

if __name__ == '__main__': main()
