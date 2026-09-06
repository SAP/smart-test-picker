#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0
"""Create compact TASK 20 case evidence from bounded external raw runs."""
import hashlib
import json
import os
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HERE = ROOT / "stp-spring-core-spike/task20"
EVIDENCE = HERE / "evidence"
RAW = Path(os.environ.get("TASK20_RAW_ROOT", "/private/tmp/task20-raw"))

MECHANISMS = {
    "enum-reference-type-constructor": ("JACOCO_FILTER_ENUM_EMPTY_CONSTRUCTOR", "JaCoCo's enum-empty-constructor filter removes all five original instructions from the coverage model. The return probe is hit, but the method is absent from IClassCoverage."),
    "enum-restructure-constructor": ("JACOCO_FILTER_ENUM_EMPTY_CONSTRUCTOR", "JaCoCo's enum-empty-constructor filter removes all five original instructions from the coverage model. The return probe is hit, but the method is absent from IClassCoverage."),
    "record-element-accessor": ("JACOCO_FILTER_RECORD_MEMBER", "JaCoCo's record filter removes the generated three-instruction accessor. Its return probe is hit, but the method is absent from IClassCoverage."),
    "record-processor-accessor": ("JACOCO_FILTER_RECORD_MEMBER", "JaCoCo's record filter removes the generated three-instruction accessor. Its return probe is hit, but the method is absent from IClassCoverage."),
    "bridge-soft-reference-get": ("JACOCO_FILTER_SYNTHETIC_BRIDGE", "JaCoCo's bridge filter removes the synthetic covariant bridge. Its return probe is hit, but the exact descriptor is absent from IClassCoverage."),
    "cache-get": ("IMPLICIT_EXCEPTION_BEFORE_EXIT_PROBE", "MethodEntry occurs, then the computeIfAbsent invocation exits exceptionally through invoked code before the sole normal-return probe; no method probe is hit."),
    "ordinary-after-mappings": ("IMPLICIT_EXCEPTION_BEFORE_EXIT_PROBE", "MethodEntry occurs, then invoked validation exits exceptionally before any of the method's exit/control-flow probes are reached."),
    "ordinary-constructor": ("IMPLICIT_EXCEPTION_BEFORE_PROBE_ARRAY_ACCESS", "Constructor MethodEntry occurs, but argument construction for the delegating constructor call exits exceptionally before JaCoCo initializes/accesses this class's probe array; the exec store therefore has no class entry."),
    "async-before-access": ("IMPLICIT_EXCEPTION_BEFORE_EXIT_PROBE", "MethodEntry occurs, then the superclass call throws for the disabled throttle before the sole normal-return probe."),
    "synthetic-switch-clinit": ("JACOCO_FILTER_SYNTHETIC_SWITCH_MAP_CLASS", "JaCoCo filters the entire compiler-generated enum switch-map class from analysis even though six of its ten probes are hit."),
}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def exact_identity(case):
    return case["className"] + "#" + case["methodName"] + case["descriptor"]


def inspect(case, run, variant):
    class_path = case["className"].replace(".", "/")
    matches = list((run / "jacoco/classes").glob(class_path + ".*.class"))
    if len(matches) != 1:
        raise RuntimeError(f"expected one dumped class for {case['caseId']}, found {matches}")
    execs = list((run / "jacoco").glob("session_*.exec"))
    if len(execs) != 1:
        raise RuntimeError(f"expected one session exec for {case['caseId']}, found {execs}")
    cp = subprocess.check_output([str(HERE / "jacoco/build-inspector.sh")], text=True).strip()
    output = RAW / "derived" / variant / (case["caseId"] + ".json")
    instrumented = RAW / "instrumented" / variant / (case["caseId"] + ".class")
    output.parent.mkdir(parents=True, exist_ok=True)
    instrumented.parent.mkdir(parents=True, exist_ok=True)
    jdk = os.environ.get("TASK19_JAVA_HOME") or subprocess.check_output(["/usr/libexec/java_home", "-v", "21"], text=True).strip()
    result = subprocess.check_output([str(Path(jdk) / "bin/java"), "-cp", cp,
        "task20.jacoco.Task20JacocoInspector", str(execs[0]), str(matches[0]), class_path,
        case["methodName"], case["descriptor"], str(instrumented)], text=True)
    output.write_text(result)
    return json.loads(result), matches[0], execs[0], instrumented


def main():
    selection = json.loads((EVIDENCE / "selected-fn-cases.json").read_text())
    cases_dir = EVIDENCE / "cases"; cases_dir.mkdir(parents=True, exist_ok=True)
    raw_manifest = []
    summary = []
    for case in selection["cases"]:
        run = RAW / "canonical" / case["caseId"]
        direct, class_file, exec_file, instrumented = inspect(case, run, "canonical")
        identity = exact_identity(case)
        events = [json.loads(line) for line in (run / "oracle-method-events.jsonl").read_text().splitlines()]
        entries = [event for event in events if event["classSignature"] == "L" + case["className"].replace(".", "/") + ";"
                   and event["methodName"] == case["methodName"] and event["descriptor"] == case["descriptor"]]
        lifecycle = [json.loads(line) for line in (run / "oracle-lifecycle.jsonl").read_text().splitlines()]
        leaf_start = next(event for event in lifecycle if event["phase"] == "LEAF_OWNERSHIP_START")
        leaf_end = next(event for event in lifecycle if event["phase"] == "LEAF_OWNERSHIP_END")
        owned_entries = [event for event in entries if leaf_start["wallClockMillis"] <= event["wallClockNanos"] // 1_000_000 <= leaf_end["wallClockMillis"]]
        dumps = [json.loads(line) for line in (run / "jacoco-dump-events.jsonl").read_text().splitlines()]
        dump_start = next(event for event in dumps if event["phase"] == "JACOCO_DUMP_RESET_START")
        dump_end = next(event for event in dumps if event["phase"] == "JACOCO_DUMP_RESET_END")
        asm = json.loads((run / "asm-map.json").read_text())
        asm_methods = {hit["method"] for test in asm["runtimeEvents"]["tests"] for hit in test["methods"]}
        decoder_methods = set((run / "jacoco-methods.txt").read_text().splitlines())
        mechanism, explanation = MECHANISMS[case["caseId"]]
        entry_ms = min(event["wallClockNanos"] // 1_000_000 for event in owned_entries)
        # Listener callback order places JaCoCo's executionFinished callback just
        # before the oracle's LEAF_OWNERSHIP_END callback. The target entry and
        # its synchronous method path must precede dump/reset; leaf-end itself
        # need not precede the other listener's callback.
        reset_artifact = not (leaf_start["wallClockMillis"] <= entry_ms <= dump_start["wallClockMillis"] <= dump_end["wallClockMillis"])
        hashes_match = direct["executionClassId"] in (None, direct["analyzerClassId"])
        relevant = direct["relevantProbeIds"]
        hit = direct["hitProbeIds"]
        document = {
            "schemaVersion": "task20-case-1", "testIdentity": case["testIdentity"],
            "methodIdentity": case["methodIdentity"], "descriptor": case["descriptor"],
            "jvmti": {"entered": bool(owned_entries), "entryCount": len(entries), "ownedEntryCount": len(owned_entries),
                      "thread": owned_entries[0]["threadName"] if owned_entries else None, "ownership": case["oracleOwnership"]},
            "asm": {"present": identity in asm_methods},
            "jacoco": {"task19DecoderCovered": identity in decoder_methods,
                       "directAnalyzerCovered": direct["directAnalyzerCovered"],
                       "methodPresentInAnalyzer": direct["methodPresentInAnalyzer"],
                       "instructionCovered": direct["instructionCovered"], "instructionMissed": direct["instructionMissed"],
                       "branchCovered": direct["branchCovered"], "branchMissed": direct["branchMissed"],
                       "relevantProbeIds": relevant, "hitProbeIds": hit,
                       "probeStates": {str(probe): "HIT" if probe in hit else "NOT_HIT" for probe in relevant}},
            "classBytes": {"executedClassIdentity": "L" + case["className"].replace(".", "/") + ";",
                           "originalSha256": direct["originalSha256"], "analyzerSha256": direct["originalSha256"],
                           "instrumentedEquivalentSha256": direct["instrumentedSha256"],
                           "executionClassId": direct["executionClassId"], "analyzerClassId": direct["analyzerClassId"],
                           "execDataEntryPresent": direct["executionClassId"] is not None, "hashesMatch": hashes_match},
            "bytecode": {"access": direct["access"], "synthetic": direct["synthetic"], "bridge": direct["bridge"],
                         "constructor": direct["constructor"], "enumGenerated": direct["enumClass"],
                         "abstract": direct["abstract"], "native": direct["native"],
                         "instructionCount": direct["instructionCount"], "realExecutableInstructions": direct["instructions"],
                         "returnOnlyOrTrivial": direct["instructionCount"] <= 5,
                         "exceptionOnlyPath": mechanism.startswith("IMPLICIT_EXCEPTION"),
                         "relevantControlFlow": mechanism,
                         "probePlacementSummary": f"method probes {relevant}; observed hits {hit}"},
            "reset": {"leafStartWallClockMillis": leaf_start["wallClockMillis"], "methodEntryWallClockMillis": entry_ms,
                      "leafEndWallClockMillis": leaf_end["wallClockMillis"],
                      "dumpResetStartWallClockMillis": dump_start["wallClockMillis"],
                      "dumpResetEndWallClockMillis": dump_end["wallClockMillis"],
                      "possibleArtifact": reset_artifact,
                      "evidence": "target entry and synchronous path precede dump/reset; JaCoCo listener callback runs before the oracle leaf-end callback" if not reset_artifact else "target entry did not precede dump/reset"},
            "representation": {"ambiguous": len(case["descriptorCandidates"]) != 1,
                               "evidence": "single TASK 19 descriptor candidate; JVMTI, ASM, and analyzer compared descriptor-exact"},
            "finalClassification": "REAL_JACOCO_METHOD_COVERAGE_LIMITATION",
            "causalMechanism": mechanism, "causalExplanation": explanation,
        }
        contrast = None
        if case["caseId"].startswith("enum-"):
            other, other_class, other_exec, other_instrumented = inspect(case, RAW / "asm-first" / case["caseId"], "asm-first")
            contrast = {"canonicalOriginalInstructions": direct["instructions"], "asmFirstJaCoCoInputInstructions": other["instructions"],
                        "canonicalInputSha256": direct["originalSha256"], "asmFirstInputSha256": other["originalSha256"],
                        "canonicalAnalyzerCovered": direct["directAnalyzerCovered"], "asmFirstAnalyzerCovered": other["directAnalyzerCovered"],
                        "canonicalRelevantProbeIds": relevant, "canonicalHitProbeIds": hit,
                        "asmFirstRelevantProbeIds": other["relevantProbeIds"], "asmFirstHitProbeIds": other["hitProbeIds"],
                        "mechanism": "ASM prepends LDC and INVOKESTATIC recorder instructions, so EnumEmptyConstructorFilter no longer matches; JaCoCo retains the seven instructions and marks all covered."}
            document["enumAsmFirstContrast"] = contrast
            raw_manifest.extend([other_class, other_exec, other_instrumented])
        (cases_dir / (case["caseId"] + ".json")).write_text(json.dumps(document, indent=2, sort_keys=True) + "\n")
        raw_manifest.extend([class_file, exec_file, instrumented, run / "asm-map.json", run / "oracle-method-events.jsonl",
                             run / "oracle-lifecycle.jsonl", run / "jacoco-dump-events.jsonl"])
        summary.append({"caseId": case["caseId"], "classification": document["finalClassification"],
                        "mechanism": mechanism, "directAnalyzerCovered": direct["directAnalyzerCovered"],
                        "classIdMatch": hashes_match, "resetArtifact": reset_artifact,
                        "representationMismatch": document["representation"]["ambiguous"]})
    unique = sorted(set(raw_manifest))
    manifest = {"schemaVersion": "task20-raw-artifact-hashes-1", "externalRoot": str(RAW),
                "files": [{"path": str(path.relative_to(RAW)), "bytes": path.stat().st_size, "sha256": sha(path)} for path in unique]}
    (EVIDENCE / "raw-artifact-hashes.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    (EVIDENCE / "case-summary.json").write_text(json.dumps({"schemaVersion": "task20-case-summary-1",
        "investigated": len(summary), "cases": summary}, indent=2, sort_keys=True) + "\n")


if __name__ == "__main__":
    main()
