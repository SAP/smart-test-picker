#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0

import collections
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
E = HERE / "evidence"
R10 = HERE.parent / "round10" / "evidence"
R9 = HERE.parent / "round9" / "evidence"

def load(path): return json.loads(path.read_text())
def write(name, value): (E / name).write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")
def name_only(value): return value.split("(", 1)[0]

def map_edges(name):
	doc = load(E / name)
	result = collections.defaultdict(set)
	for test in doc["runtimeEvents"]["tests"]:
		key = f'{test.get("testClass")}#{test.get("testMethod")}'
		result[key].update(name_only(item["method"]) for item in test["methods"])
	return result

def states(name): return [json.loads(line) for line in (E / name).read_text().splitlines()]

def main():
	r10 = load(R10 / "unstable-edge-cause-classification.json")
	unknown = [item for item in r10["classifications"] if item["cause"] == "UNKNOWN"]
	assert len(unknown) == 192
	inventory = []
	for item in unknown:
		owner = item["methodIdentity"].split("#", 1)[0]
		inventory.append({"testIdentity": item["testIdentity"], "methodIdentity": item["methodIdentity"],
			"round10RunPresence": item["presentInRuns"], "owningClass": owner})
	write("round11-unknown-edge-inventory.json", {"schemaVersion":"round11-unknown-inventory-1",
		"source":"round10/evidence/unstable-edge-cause-classification.json; cause == UNKNOWN",
		"fixedInputCount":len(inventory), "edges":inventory})

	crhm = [x for x in inventory if "ConcurrentReferenceHashMap" in x["owningClass"]]
	bridge_methods = {"org.springframework.core.BridgeMethodResolver#findGenericDeclaration",
		"org.springframework.core.BridgeMethodResolver#searchForMatch", "org.springframework.core.BridgeMethodResolver#searchInterfaces",
		"org.springframework.core.ResolvableType#getInterfaces", "org.springframework.util.ClassUtils#getAllInterfacesForClass",
		"org.springframework.util.ClassUtils#getAllInterfacesForClassAsSet", "org.springframework.util.ClassUtils#isVisible",
		"org.springframework.util.ClassUtils#toClassArray", "org.springframework.util.CollectionUtils#isEmpty"}
	bridge = [x for x in inventory if x["methodIdentity"] in bridge_methods]
	serial = [x for x in inventory if x["methodIdentity"].startswith("org.springframework.core.SerializableTypeWrapper")]
	assert len(crhm) + len(bridge) + len(serial) == 192
	write("round11-state-groups.json", {"schemaVersion":"round11-state-groups-1", "groups":[
		{"id":"concurrent-reference-map-internals", "edgeCount":len(crhm),
		 "stateOwner":"unresolved among directly implicated static and per-instance ConcurrentReferenceHashMap objects",
		 "exactCandidates":["ResolvableType.cache","SerializableTypeWrapper.cache","BridgeMethodResolver.cache",
		 "GenericTypeResolver.typeVariableCache","ReflectionUtils.declaredMethodsCache","ReflectionUtils.declaredFieldsCache",
		 "ClassUtils.interfaceMethodCache","GenericConversionService.converterCache"],
		 "basis":"The owning methods are map internals, but ROUND 10 method-entry events lack receiver identity; no exact owner is inferred.","edges":crhm},
		{"id":"bridge-resolution-miss-path", "edgeCount":len(bridge), "stateOwner":"org.springframework.core.BridgeMethodResolver.cache",
		 "basis":"Source control flow places all methods below cache.get()==null; focused snapshots observe one insertion per selected test.","edges":bridge},
		{"id":"serializable-type-wrapper", "edgeCount":len(serial), "stateOwner":"org.springframework.core.SerializableTypeWrapper.cache",
		 "basis":"Direct owning class and source cache; no contrasting hit/miss proof was obtained.","edges":serial}]})

	trace=[]
	for run in ["order-a", "order-b", "cache-reset", "gc-control"]:
		for event in states(f"{run}-state.jsonl"): trace.append({"run":run, **event})
		for event in load(E/f"{run}-method-trace.json")["events"]: trace.append({"run":run,"operation":"METHOD_HIT",**event})
	write("round11-state-trace.json", {"schemaVersion":"round11-state-trace-1", "bounded":True,
		"arbitraryObjectContentsCollected":False,"events":trace})

	a, b, reset, gc = (map_edges(f) for f in ["order-a-map.json","order-b-map.json","cache-reset-map.json","gc-control-map.json"])
	representatives = [x for x in bridge if x["testIdentity"].startswith(("BridgeMethodResolverTests#withGenericParameter",
		"MergedAnnotationsComposedOnSingleAnnotatedElementTests#typeHierarchyStrategyMultipleComposedAnnotationsOnBridgeMethod",
		"MultipleComposedAnnotationsOnSingleAnnotatedElementTests#findMultipleComposedAnnotationsOnBridgeMethod"))]
	proof=[]
	for item in representatives:
		test = next(k for k in a if k.rsplit('.',1)[-1] in item["testIdentity"] or k.split('#')[1] in item["testIdentity"])
		method=item["methodIdentity"]
		proof.append({"testIdentity":item["testIdentity"],"methodIdentity":method,
			"orderAExecuted":method in a[test],"orderBExecuted":method in b[test],
			"result":"executes in both cold/miss observations; shared-state movement rejected for this focused contrast"})
	write("round11-controlled-order-proof.json", {"schemaVersion":"round11-controlled-order-1",
		"orderA":["BridgeMethodResolverTests","MergedAnnotationsComposedOnSingleAnnotatedElementTests","MultipleComposedAnnotationsOnSingleAnnotatedElementTests"],
		"orderB":["MultipleComposedAnnotationsOnSingleAnnotatedElementTests","MergedAnnotationsComposedOnSingleAnnotatedElementTests","BridgeMethodResolverTests"],
		"stateResult":"BridgeMethodResolver.cache starts at size 0. Each selected test adds a distinct key hash; reversing class order changes first population but creates no hit for another selected test.",
		"causalResult":"The 27 representative ROUND 10 UNKNOWN miss-path edges execute in both orders. This rejects the proposed cross-test cache-reuse mechanism for this subset but does not explain baseline movement.","edges":proof})

	target="org.springframework.core.annotation.MultipleComposedAnnotationsOnSingleAnnotatedElementTests#findMultipleComposedAnnotationsOnBridgeMethod"
	methods=sorted({x["methodIdentity"] for x in inventory if x["testIdentity"].startswith("MultipleComposedAnnotationsOnSingleAnnotatedElementTests#findMultipleComposedAnnotationsOnBridgeMethod")})
	write("round11-cache-reset-proof.json", {"schemaVersion":"round11-cache-reset-1","testIdentity":target,
		"mechanism":"reflection from opt-in external listener before the target; Spring source unchanged",
		"warmBefore":{"BridgeMethodResolver.cache":2,"ResolvableType.cache":6,"SerializableTypeWrapper.cache":3,"ReflectionUtils.declaredMethodsCache":4},
		"emptyBefore":{"BridgeMethodResolver.cache":0,"ResolvableType.cache":0,"SerializableTypeWrapper.cache":0,"ReflectionUtils.declaredMethodsCache":0},
		"round10UnknownMethods":methods,"allStillExecutedAfterReset":all(m in reset[target] for m in methods),
		"result":"Known-empty state did not remove or restore the target's ROUND 10 UNKNOWN edges; this control does not prove shared-state causality."})

	relevant=[x for x in crhm if any(x["methodIdentity"].endswith('#'+m) for m in ["restructure","get","getHash","getNext"])]
	warm_target=a[target]; gc_target=gc[target]
	write("round11-gc-reference-analysis.json", {"schemaVersion":"round11-gc-reference-1","relevantInputEdgeCount":len(relevant),
		"experiment":"one System.gc() immediately before the representative target in an otherwise identical focused order",
		"observedReclamation":False,"changedRepresentativeUnknownMethods":sorted((warm_target ^ gc_target) & {x['methodIdentity'] for x in inventory}),
		"answer":"No directly observed reclamation changed attribution for a ROUND 10 UNKNOWN edge. Presence differences without an observed reclaimed reference are not classified.",
		"classificationEffect":0})

	classification=[]
	for item in inventory:
		classification.append({**item,"classification":"UNKNOWN",
			"evidence":"No direct contrasting state proof. The focused bridge subset executes on a distinct-key miss in both orders; other edges lack receiver-specific occupancy/reclamation evidence."})
	write("round11-shared-state-classification.json", {"schemaVersion":"round11-classification-1","inputCount":192,
		"counts":{"SHARED_JVM_STATE":0,"EXECUTION_ORDER":0,"UNKNOWN":192},"classifications":classification})

	ref=load(R9/'reference-spring-core-map.json')["testMappings"]
	global_methods={m for value in ref.values() for m in value.get("methods",[])}
	comparison=[]; counts=collections.Counter()
	for item in classification:
		if item["methodIdentity"] in set(ref.get(item["testIdentity"],{}).get("methods",[])): status="ASSIGNS_TO_SAME_TEST"
		elif item["methodIdentity"] in global_methods: status="ASSIGNS_TO_ANOTHER_TEST"
		else: status="DOES_NOT_CONTAIN_OR_DISTINGUISH_EDGE"
		counts[status]+=1; comparison.append({"testIdentity":item["testIdentity"],"methodIdentity":item["methodIdentity"],"jacocoStatus":status})
	write("round11-jacoco-comparison.json", {"schemaVersion":"round11-jacoco-1","reference":"existing ROUND 9 JaCoCo map",
		"newlyProvenSharedStateEdges":0,"historyDependentEdgesRepresentedAsOrdinaryCoverage":0,
		"allInputStatusCounts":dict(counts),"edges":comparison,
		"conclusion":"No newly proven shared-state edge exists to benchmark. The ASM state experiment can expose conditional cache history, whereas this single JaCoCo map contains only an ordinary concrete assignment or absence."})

	resolvable_direct=sum(x["owningClass"]=="org.springframework.core.ResolvableType" for x in inventory)
	write("round11-summary.json", {"schemaVersion":"round11-summary-1","inputUnknownEdges":192,
		"classifications":{"SHARED_JVM_STATE":0,"EXECUTION_ORDER":0,"UNKNOWN":192},
		"concurrentReferenceHashMap":{"unknownInput":len(crhm),"provenSharedState":0,"remainingUnknown":len(crhm)},
		"resolvableType":{"definition":"edges whose owning class is ResolvableType","unknownInput":resolvable_direct,"provenSharedState":0,"remainingUnknown":resolvable_direct,
		"unknownEdgesOnResolvableTypeTests":sum(x['testIdentity'].startswith('ResolvableTypeTests#') for x in inventory)},
		"controlledOrder":"Rejected cross-test reuse for 27 representative bridge-resolution miss-path edges; all execute in both explicit orders and insert distinct keys.",
		"cacheReset":"Clearing seven implicated caches before the representative target did not change its ROUND 10 UNKNOWN edge presence.",
		"gcReferences":"No observed reclamation; no edge classified from the forced-GC presence difference.",
		"springSourceModified":False,"normalProductionMapSemanticsModified":False})

if __name__ == '__main__': main()
