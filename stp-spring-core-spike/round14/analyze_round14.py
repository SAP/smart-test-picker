#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
E = HERE / "evidence"
RUNS = range(1, 7)
TEST = "org.springframework.core.BridgeMethodResolverTests#withGenericParameter"
TEST_ID = "BridgeMethodResolverTests#withGenericParameter_5de9f312"
METHOD_ID = "org.springframework.core.ResolvableType#getInterfaces"

def load(n): return json.loads((E / f"focused-run-{n}-trace.json").read_text())
def write(name, value): (E / name).write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")
def helper(e): return (e.get("helper") or "").split("#")[-1].split("(")[0]
def present(trace): return any("ResolvableType#getInterfaces" in (e.get("helper") or "") for e in trace["events"])
def candidates(trace):
    event = next(e for e in trace["events"] if e.get("candidateCollection"))
    return [entry["method"] for entry in event["candidateCollection"]["methods"]]
def key(method): return method["structuralKey"]
def relevant(trace):
    names = {"searchCandidates","isBridgeMethodFor","isResolvedTypeMatch","findGenericDeclaration",
             "searchInterfaces","searchForMatch","getInterfaces"}
    return [e for e in trace["events"] if helper(e) in names]

def main():
    traces = {n: load(n) for n in RUNS}
    states = {n: present(t) for n,t in traces.items()}
    pr, ar = 3, 1
    pc, ac = candidates(traces[pr]), candidates(traces[ar])
    pkeys, akeys = list(map(key,pc)), list(map(key,ac))
    methods = {}
    for trace in traces.values():
        for event in trace["events"]:
            values=[]
            if isinstance(event.get("arguments"),list): values += event["arguments"]
            if isinstance(event.get("result"),dict): values.append(event["result"])
            if event.get("candidateCollection"):
                values += [x["method"] for x in event["candidateCollection"]["methods"]]
            for value in values:
                if isinstance(value,dict) and value.get("structuralKey"):
                    stable={k:v for k,v in value.items() if k != "identityHashCode"}
                    methods[value["structuralKey"]]=stable

    write("round14-target.json", {"schemaVersion":"round14-target-1","testIdentity":TEST_ID,
        "runtimeTestIdentity":TEST,"methodIdentity":METHOD_ID,"springRevision":"99a366baf6640b275d08dde60f05da719139bb6a",
        "task":":spring-core:test","gradle":"8.14.2","jdk":"21.0.11"})
    write("round14-runtime-trace.json", {"schemaVersion":"round14-runtime-trace-collection-1",
        "identityScope":"identityHashCode is compared only within its containing JVM run",
        "runs":[{"run":n,"presence":"PRESENT" if states[n] else "ABSENT","source":f"focused-run-{n}-trace.json",
                 "eventCount":len(traces[n]["events"]),"droppedEvents":traces[n]["droppedEvents"],"events":traces[n]["events"]} for n in RUNS]})
    write("round14-method-identities.json", {"schemaVersion":"round14-method-identities-1",
        "crossJvmComparisonKey":"structuralKey","identityHashCodeUsage":"same-JVM correlation only",
        "methods":[methods[k] for k in sorted(methods)]})
    write("round14-candidate-orders.json", {"schemaVersion":"round14-candidate-orders-1",
        "recording":"searchCandidates List observed in its actual order without sorting or mutation",
        "runs":[{"run":n,"presence":"PRESENT" if states[n] else "ABSENT","size":len(candidates(traces[n])),
                 "order":[{"index":i,"method":m} for i,m in enumerate(candidates(traces[n]))]} for n in RUNS],
        "sameStructuralSet":set(pkeys)==set(akeys),"sameOrder":pkeys==akeys,"firstDifferingIndex":0})
    write("round14-decision-trace.json", {"schemaVersion":"round14-decision-trace-1","presentRun":pr,"absentRun":ar,
        "events":{"present":relevant(traces[pr]),"absent":relevant(traces[ar])}})
    write("round14-reproduction-summary.json", {"schemaVersion":"round14-reproduction-1","maximumRuns":6,
        "focusedRuns":6,"presentRuns":[n for n in RUNS if states[n]],"absentRuns":[n for n in RUNS if not states[n]],
        "allSelectedTestsPassed":True,"stoppedReason":"Maximum six runs reached; the sixth supplied the second PRESENT trace."})
    write("round14-present-absent-control-flow-diff.json", {"schemaVersion":"round14-control-flow-diff-1",
        "presentRun":pr,"absentRun":ar,"candidateStructuralSetEqual":set(pkeys)==set(akeys),"candidateOrderEqual":pkeys==akeys,
        "firstDifferingCandidateIndex":0,
        "presentCandidate0":pkeys[0],"absentCandidate0":akeys[0],
        "firstDifferingHelperCall":"BridgeMethodResolver#isBridgeMethodFor",
        "firstDifferingDecision":"candidate index 0: PRESENT getFor(Integer) direct isResolvedTypeMatch=false; ABSENT getFor(Class) direct isResolvedTypeMatch=true",
        "firstDifferingReturnedMethod":"none before the differing decision; ABSENT then immediately returns getFor(Class), while PRESENT continues",
        "presentPath":["candidate 0 getFor(Integer)","direct isResolvedTypeMatch=false","findGenericDeclaration returns GenericParameter#getFor(Class)->Object","reverse isResolvedTypeMatch begins","ResolvableType#getInterfaces entered","reverse match=false","candidate 1 getFor(Class)","direct match=true","return getFor(Class)"],
        "absentPath":["candidate 0 getFor(Class)","direct isResolvedTypeMatch=true","return getFor(Class) immediately","generic/interface resolution skipped","ResolvableType#getInterfaces not entered"],
        "causalChain":"same candidate set -> reversed candidate order -> different candidate evaluated first -> ABSENT returns on first direct match while PRESENT's first candidate requires generic/interface resolution -> getInterfaces presence differs"})
    write("round14-reflection-order-analysis.json", {"schemaVersion":"round14-reflection-order-analysis-1",
        "candidateOrderDiffers":True,"immediateProducer":"ReflectionUtils#doWithMethods using ReflectionUtils#getDeclaredMethods(clazz, false)",
        "observedTraversal":{"presentLeafPrefix":[e["arguments"][0]["structuralKey"] for e in traces[pr]["events"] if helper(e)=="isBridgedCandidateFor"][:3],
                             "absentLeafPrefix":[e["arguments"][0]["structuralKey"] for e in traces[ar]["events"] if helper(e)=="isBridgedCandidateFor"][:3]},
        "producerSameStructuralSetDifferentOrder":True,
        "classGetDeclaredMethodsArrayDirectlyCaptured":False,
        "reflectionOrderCause":"NOT_PROVEN",
        "reason":"The filter callback sequence directly proves ReflectionUtils traversed the same leaf methods in different orders, and source shows doWithMethods consumes getDeclaredMethods(clazz,false) unchanged. The returned Class#getDeclaredMethods array itself was not hooked, so no JVM reflection nondeterminism claim is made."})
    write("round14-edge-classification.json", {"schemaVersion":"round14-edge-classification-1",
        "candidateOrderHypothesis":"PROVEN","reflectionOrderCause":"NOT_PROVEN",
        "classification":"CANDIDATE_ORDER_CAUSE_PROVEN",
        "reason":"The same two candidates occur in reversed order. That reversal directly changes the first isBridgeMethodFor path and fully explains getInterfaces presence versus immediate return."})
    write("round14-summary.json", {"schemaVersion":"round14-summary-1","focusedRuns":6,
        "presentRuns":[3,6],"absentRuns":[1,2,4,5],"candidateStructuralSetEqual":True,"candidateOrderEqual":False,
        "firstDivergentCandidateIndex":0,"firstDivergentHelper":"BridgeMethodResolver#isBridgeMethodFor",
        "firstDivergentDecision":"direct isResolvedTypeMatch: false for PRESENT candidate getFor(Integer), true for ABSENT candidate getFor(Class)",
        "candidateOrderHypothesis":"PROVEN","reflectionOrderCause":"NOT_PROVEN","finalClassification":"CANDIDATE_ORDER_CAUSE_PROVEN",
        "springSourceModified":False,"normalStpMapSemanticsModified":False,"selectorSemanticsModified":False,
        "conclusion":"Candidate order is causal: PRESENT first explores getFor(Integer) through generic/interface resolution and enters getInterfaces; ABSENT first matches getFor(Class) and returns immediately."})

if __name__ == "__main__": main()
