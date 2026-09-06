#!/usr/bin/env python3
import collections
import hashlib
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
EVIDENCE = HERE / "evidence"
ROUND10 = HERE.parent / "round10" / "evidence"
RUNS = range(1, 6)
CATEGORIES = ["JDK_REFLECTION_ORDER", "SPRING_ORDER_SENSITIVE_CONTROL_FLOW", "EXECUTION_ORDER",
    "SHARED_JVM_STATE", "STATIC_INITIALIZATION", "LATE_EXECUTION", "ASYNC_CONTEXT",
    "OTHER_PROVEN_RUNTIME_CAUSE", "STABLE_IN_ROUND16", "UNKNOWN"]

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

def name_only(method): return method.split("(", 1)[0]
def write(name, value): (EVIDENCE / name).write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")

def load_map(number):
    doc = json.loads((EVIDENCE / f"asm-run-{number}-map.json").read_text())
    mapping = collections.defaultdict(set)
    for test in doc["runtimeEvents"]["tests"]:
        key = canonical_test(test)
        if key: mapping[key].update(name_only(hit["method"]) for hit in test["methods"])
    return doc, mapping

def main():
    prior = json.loads((ROUND10 / "unstable-edge-cause-classification.json").read_text())
    inputs = [{"testIdentity": x["testIdentity"], "methodIdentity": x["methodIdentity"]}
              for x in prior["classifications"] if x["cause"] == "UNKNOWN"]
    inputs.sort(key=lambda x: (x["testIdentity"], x["methodIdentity"]))
    canonical = json.dumps(inputs, separators=(",", ":"), sort_keys=True).encode()
    input_doc = {"schemaVersion":"round16-input-192-1", "source":"ROUND 10 unstable-edge-cause-classification.json entries with cause UNKNOWN",
        "sourceCount":len(inputs), "pairCount":len(set((x['testIdentity'],x['methodIdentity']) for x in inputs)),
        "sha256OfCanonicalPairs":hashlib.sha256(canonical).hexdigest(), "edges":inputs}
    write("round16-input-192.json", input_doc)
    if len(inputs) != 192 or input_doc["pairCount"] != 192: raise SystemExit("fixed input is not exactly 192 unique pairs")

    missing = [n for n in RUNS if not (EVIDENCE / f"asm-run-{n}-map.json").exists()]
    if missing:
        print(f"fixed input written; awaiting full-run maps: {missing}")
        return

    loaded = [load_map(n) for n in RUNS]
    maps = [x[1] for x in loaded]
    stability=[]
    for edge in inputs:
        vector=[1 if edge['methodIdentity'] in m.get(edge['testIdentity'],set()) else 0 for m in maps]
        stability.append({**edge,"presenceVector":vector,"presenceCount":sum(vector),"movedInRound16":len(set(vector)) > 1})
    write("round16-full-run-stability.json", {"schemaVersion":"round16-full-run-stability-1","acceptedRuns":list(RUNS),"inputEdgeCount":192,"edges":stability})

    grouped=collections.defaultdict(list)
    for edge in stability:
        family=edge['methodIdentity'].split('#')[0]
        vector=''.join(map(str,edge['presenceVector']))
        grouped[(edge['testIdentity'],family,vector)].append(edge)
    groups=[]
    for i,(key,edges) in enumerate(sorted(grouped.items()),1):
        groups.append({"groupId":f"G{i:03d}","basis":{"testIdentity":key[0],"declaringClass":key[1],"presenceVector":list(map(int,key[2]))},
            "analysisOnly":True,"edgeCount":len(edges),"edges":[{"testIdentity":x['testIdentity'],"methodIdentity":x['methodIdentity']} for x in edges]})
    write("round16-edge-groups.json", {"schemaVersion":"round16-edge-groups-1","exclusive":True,"inputEdgeCount":192,"groupCount":len(groups),"groups":groups})

    classifications=[]
    for edge in stability:
        category="STABLE_IN_ROUND16" if len(set(edge['presenceVector'])) == 1 else "UNKNOWN"
        evidence={"evidenceType":"five identical full fresh-test-worker JVM runs","runsInvolved":list(RUNS),
            "controlledContrast":None,"directCausalChain":None,"confidence":None}
        if edge['testIdentity']=="BridgeMethodResolverTests#withGenericParameter_5de9f312" and edge['methodIdentity']=="org.springframework.core.ResolvableType#getInterfaces":
            category="JDK_REFLECTION_ORDER"
            evidence={"evidenceType":"ROUND 14-15 focused present/absent raw-order and control-flow contrast","runsInvolved":["ROUND14:1,3","ROUND15:1,5"],
                "controlledContrast":"Same structural Method set; Class#getDeclaredMethods indexes 1/2 swapped across fresh JVMs.",
                "directCausalChain":"raw declared-method order -> ReflectionUtils order -> candidate insertion/search order -> first candidate branch -> ResolvableType#getInterfaces presence/absence",
                "confidence":"PROVEN"}
        hints=[]
        if "BridgeMethodResolver" in edge['methodIdentity'] or "ResolvableType" in edge['methodIdentity']: hints.append("reflection-related")
        if "ConcurrentReferenceHashMap" in edge['methodIdentity']: hints.append("ConcurrentReferenceHashMap family")
        classifications.append({**edge,"classification":category,"evidence":evidence,"characterizationHints":hints})
    write("round16-causal-classification.json", {"schemaVersion":"round16-causal-classification-1","inputEdgeCount":192,"entries":classifications})

    summary={"input edges":192}
    for category in CATEGORIES:
        selected=[x for x in classifications if x['classification']==category]
        summary[category]={"edges":len(selected),"tests affected":len({x['testIdentity'] for x in selected}),
            "production methods affected":len({x['methodIdentity'] for x in selected}),
            "classes affected":len({x['methodIdentity'].split('#')[0] for x in selected})}
    all_edges=set()
    for m in maps: all_edges.update((t,method) for t,methods in m.items() for method in methods)
    stable=sum(all(method in m.get(test,set()) for m in maps) for test,method in all_edges)
    unstable=len(all_edges)-stable
    logical=set().union(*(set(m) for m in maps))
    summary["fullMapRepeatability"]={"acceptedRuns":5,"logicalTestIdentities":len(logical),
        "mappedIdentities":sum(any(m.get(t) for m in maps) for t in logical),"totalDistinctPerTestMethodEdges":len(all_edges),
        "stableEdges":stable,"unstableEdges":unstable,"unstablePercentage":round(100*unstable/len(all_edges),6)}
    summary["round10Comparison"]={"stableEdges":149705,"unstableEdges":221,"unstablePercentage":0.147406,
        "directlyComparable":False,"note":"Subject and launch configuration match, but five-run sampling has more opportunities to expose movement than ROUND 10's three-run sampling; no improvement/regression claim is made."}
    write("round16-causal-summary.json", summary)

if __name__ == "__main__": main()
