#!/usr/bin/env python3
"""Independently reconcile EI-13 R0 Surefire execution and STP evidence.

The baseline side is derived only from Surefire XML.  STP inventory/evidence/map
are loaded later and compared against that independent record.  Raw testcase
records and every normalization candidate are retained in the JSON output.
"""

import argparse
import collections
import glob
import hashlib
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

TARGET = "surefire@default-test@examples"


def identity_parts(value):
    owner, test = value.split("::", 1)
    clazz, signature = test.rsplit("#", 1)
    return owner, clazz, signature, signature.split("(", 1)[0]


def module_from_report(root, report):
    relative = report.relative_to(root).as_posix()
    marker = "/target/surefire-reports/"
    if marker not in relative:
        raise ValueError(f"report is outside a Maven Surefire target: {report}")
    return relative.split(marker, 1)[0]


def method_name(display_name):
    # Surefire 3 writes Java method names before the descriptor and invocation
    # suffix. This intentionally preserves the complete display name elsewhere.
    return re.split(r"[([]", display_name, maxsplit=1)[0]


def invocation_arity(display_name):
    match = re.match(r"^[^(]+\(([^)]*)\)", display_name)
    if not match:
        return 0
    value = match.group(1).strip()
    return 0 if not value else len(value.split(","))


def identity_arity(identity):
    signature = identity_parts(identity)[2]
    if "(" not in signature:
        return 0
    value = signature.split("(", 1)[1].rsplit(")", 1)[0].strip()
    return 0 if not value else len(value.split(","))


def read_reports(root, inventory_index=None, shard=None):
    root = Path(root).resolve()
    records = []
    for report in sorted(root.glob("**/target/surefire-reports/TEST-*.xml")):
        module = module_from_report(root, report)
        suite = ET.parse(report).getroot()
        for ordinal, case in enumerate(suite.findall(".//testcase")):
            name = case.attrib["name"]
            clazz = case.attrib["classname"]
            status = "skipped" if case.find("skipped") is not None else (
                "failed" if case.find("failure") is not None or case.find("error") is not None else "executed")
            key = f"maven:{module}@{TARGET}::{clazz}#{method_name(name)}"
            candidates = [] if inventory_index is None else inventory_index.get(key, [])
            if len(candidates) > 1:
                by_arity = [value for value in candidates if identity_arity(value) == invocation_arity(name)]
                if by_arity:
                    candidates = by_arity
            records.append({
                "module": module, "executionTarget": TARGET, "suite": suite.attrib.get("name"),
                "className": clazz, "displayName": name, "methodName": method_name(name),
                "status": status, "time": case.attrib.get("time"), "report": str(report),
                "ordinal": ordinal, "shard": shard, "normalizationKey": key,
                "canonicalCandidates": candidates,
            })
    return records


def fingerprint(record):
    # Invocation suffixes and parameter displays remain part of the key. This
    # catches a missing repetition/parameter row rather than hiding it.
    return (record["module"], record["executionTarget"], record["className"],
            record["displayName"], record["status"])


def source_evidence(project_root, identity):
    owner, clazz, _, method = identity_parts(identity)
    module = owner[len("maven:"):].split("@", 1)[0]
    simple = clazz.split(".")[-1].split("$")[0]
    candidates = list((Path(project_root) / module / "src" / "test").glob(f"**/{simple}.java"))
    for source in candidates:
        lines = source.read_text(errors="replace").splitlines()
        for number, line in enumerate(lines, 1):
            if re.search(rf"\b{re.escape(method)}\s*\(", line):
                annotations = []
                i = number - 2
                while i >= 0 and (lines[i].strip().startswith("@") or not lines[i].strip()):
                    if lines[i].strip().startswith("@"): annotations.append(lines[i].strip())
                    i -= 1
                return {"path": str(source), "line": number, "annotations": list(reversed(annotations))}
    return None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--baseline", required=True)
    parser.add_argument("--mapping-shard", action="append", required=True,
                        help="SHARD_ID=workspace root (repeat for each shard)")
    parser.add_argument("--inventory", required=True)
    parser.add_argument("--evidence", action="append", required=True)
    parser.add_argument("--original-evidence", action="append", default=[],
                        help="historical evidence whose NON_EXECUTED set must be accounted separately")
    parser.add_argument("--fragment", action="append", required=True)
    parser.add_argument("--coverage-map", required=True)
    parser.add_argument("--project-source", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    inventory_doc = json.loads(Path(args.inventory).read_text())
    inventory = inventory_doc["tests"]
    index = collections.defaultdict(list)
    for identity in inventory:
        owner, clazz, _, method = identity_parts(identity)
        index[f"{owner}::{clazz}#{method}"].append(identity)

    baseline_raw = read_reports(args.baseline, index)
    mapping_raw = []
    shard_roots = {}
    for spec in args.mapping_shard:
        shard, root = spec.split("=", 1)
        shard_roots[shard] = str(Path(root).resolve())
        mapping_raw.extend(read_reports(root, index, shard))

    baseline_counts = collections.Counter(map(fingerprint, baseline_raw))
    mapping_counts = collections.Counter(map(fingerprint, mapping_raw))
    missing = list((baseline_counts - mapping_counts).elements())
    unexpected = list((mapping_counts - baseline_counts).elements())
    shard_owners = collections.defaultdict(set)
    for record in mapping_raw:
        if record["status"] == "executed": shard_owners[fingerprint(record)].add(record["shard"])
    duplicates = [{"invocation": list(key), "shards": sorted(shards)}
                  for key, shards in shard_owners.items() if len(shards) > 1]

    evidence_docs = [json.loads(Path(p).read_text()) for p in args.evidence]
    evidence_executed = set().union(*(set(x["EXECUTED"]) for x in evidence_docs))
    evidence_nonexecuted = set().union(*(set(x["NON_EXECUTED"]) for x in evidence_docs))
    evidence_overlap = sorted(evidence_executed & evidence_nonexecuted)
    evidence_duplicates = sorted(x for x, count in collections.Counter(
        y for doc in evidence_docs for y in doc["EXECUTED"] + doc["NON_EXECUTED"]).items() if count > 1)
    coverage = json.loads(Path(args.coverage_map).read_text())
    map_tests = set(coverage["tests"])
    collector_missing = sorted(evidence_executed - map_tests)
    collector_unexpected = sorted(map_tests - evidence_executed)

    mapping_executed_keys = {r["normalizationKey"] for r in mapping_raw if r["status"] == "executed"}
    evidence_keys = {f"{identity_parts(x)[0]}::{identity_parts(x)[1]}#{identity_parts(x)[3]}"
                     for x in evidence_executed}
    xml_without_evidence = sorted(mapping_executed_keys - evidence_keys)
    evidence_without_xml = sorted(evidence_keys - mapping_executed_keys)
    ambiguities = []
    for record in baseline_raw:
        if len(record["canonicalCandidates"]) > 1:
            ambiguities.append({"report": record["report"], "ordinal": record["ordinal"],
                                "displayName": record["displayName"],
                                "canonicalCandidates": record["canonicalCandidates"]})

    baseline_by_key = collections.defaultdict(list)
    mapping_by_key = collections.defaultdict(list)
    for record in baseline_raw: baseline_by_key[record["normalizationKey"]].append(record)
    for record in mapping_raw: mapping_by_key[record["normalizationKey"]].append(record)
    accounting = []
    for identity in sorted(evidence_nonexecuted):
        owner, _, _, method = identity_parts(identity)
        key = f"{owner}::{identity_parts(identity)[1]}#{method}"
        base = baseline_by_key[key]
        mapped = mapping_by_key[key]
        if base and all(x["status"] == "skipped" for x in base):
            reason, classification = "all baseline Surefire invocations are explicitly skipped", "GENUINE_SKIP"
        elif not base:
            reason, classification = "baseline Surefire XML contains no invocation for the discovered parameterized template", "TEMPLATE_NO_INVOCATIONS"
        else:
            reason, classification = "baseline contains an executable invocation but mapping did not classify it executed", "UNRESOLVED"
        accounting.append({
            "canonicalIdentity": identity, "module": owner[len("maven:"):].split("@", 1)[0],
            "executionTarget": TARGET,
            "baselineEvidence": [{k: r[k] for k in ("report", "ordinal", "displayName", "status")} for r in base],
            "mappingEvidence": [{k: r[k] for k in ("report", "ordinal", "displayName", "status", "shard")} for r in mapped],
            "sourceEvidence": source_evidence(args.project_source, identity),
            "reason": reason, "classification": classification,
        })

    accounting_by_identity = {x["canonicalIdentity"]: x for x in accounting}
    original_nonexecuted = set()
    original_executed = set()
    for path in args.original_evidence:
        historical = json.loads(Path(path).read_text())
        original_nonexecuted.update(historical["NON_EXECUTED"])
        original_executed.update(historical["EXECUTED"])
    original_accounting = [accounting_by_identity[x] for x in sorted(original_nonexecuted)]
    corrected_false_executed = [accounting_by_identity[x] for x in sorted(
        (original_executed & evidence_nonexecuted))]

    unresolved_nonexecuted = [x for x in accounting if x["classification"] == "UNRESOLVED"]
    result = {
        "schemaVersion": 1,
        "provenance": {
            "projectRevision": inventory_doc.get("revision"), "reactorProfile": "examples",
            "executionTarget": TARGET, "baselineRoot": str(Path(args.baseline).resolve()),
            "mappingShardRoots": shard_roots,
            "inputs": {p: hashlib.sha256(Path(p).read_bytes()).hexdigest() for p in
                       [args.inventory, *args.evidence, *args.fragment, args.coverage_map]},
        },
        "counts": {
            "inventoryIdentities": len(inventory), "baselineInvocations": len(baseline_raw),
            "baselineExecutedInvocations": sum(r["status"] == "executed" for r in baseline_raw),
            "baselineSkippedInvocations": sum(r["status"] == "skipped" for r in baseline_raw),
            "mappingInvocations": len(mapping_raw),
            "mappingExecutedInvocations": sum(r["status"] == "executed" for r in mapping_raw),
            "mappingSkippedInvocations": sum(r["status"] == "skipped" for r in mapping_raw),
            "evidenceExecutedIdentities": len(evidence_executed),
            "evidenceNonExecutedIdentities": len(evidence_nonexecuted), "mapIdentities": len(map_tests),
        },
        "baselineToMapping": {"missingInvocations": [list(x) for x in missing],
                              "unexpectedInvocations": [list(x) for x in unexpected],
                              "crossShardDuplicateInvocations": duplicates},
        "mappingToCollector": {"xmlKeysWithoutEvidence": xml_without_evidence,
                               "evidenceKeysWithoutXml": evidence_without_xml,
                               "executedEvidenceMissingFromMap": collector_missing,
                               "mapEntriesWithoutExecutedEvidence": collector_unexpected},
        "evidenceIntegrity": {"overlap": evidence_overlap, "crossShardDuplicates": evidence_duplicates},
        "identityAmbiguities": ambiguities,
        "nonExecutedAccounting": accounting,
        "originalNonExecutedAccounting": original_accounting,
        "correctedFalseExecutedAccounting": corrected_false_executed,
        "rawBaselineInvocations": baseline_raw,
        "rawMappingInvocations": mapping_raw,
    }
    failures = bool(missing or unexpected or duplicates or xml_without_evidence or evidence_without_xml or
                    collector_missing or collector_unexpected or evidence_overlap or evidence_duplicates or
                    unresolved_nonexecuted or ambiguities)
    result["status"] = "FAIL" if failures else "PASS"
    Path(args.output).write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
    print(json.dumps({"status": result["status"], **result["counts"],
                      "missing": len(missing), "unexpected": len(unexpected),
                      "duplicates": len(duplicates), "ambiguities": len(ambiguities),
                      "unresolvedNonExecuted": len(unresolved_nonexecuted)}, indent=2))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
