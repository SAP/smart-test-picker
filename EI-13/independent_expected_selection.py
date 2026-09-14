#!/usr/bin/env python3
"""Independently derive EI-13's class-granularity Maven selection.

This intentionally does not import or invoke Smart Test Picker.  It implements the
installed Maven adapter contract: direct class-coverage edges plus published unmapped
tests, intersected with the head inventory, then normalized to Surefire's supported
class-plus-method-name selection granularity.
"""

import argparse
import hashlib
import json
import pathlib
import re
import subprocess


def git(repo, *args):
    return subprocess.check_output(["git", "-C", repo, *args], text=True)


def test_class(identity):
    return identity.split("::", 1)[1].split("#", 1)[0]


def maven_execution_identity(identity):
    prefix, method = identity.rsplit("#", 1)
    return prefix + "#" + method.split("(", 1)[0]


parser = argparse.ArgumentParser()
parser.add_argument("--repo", required=True)
parser.add_argument("--base", required=True)
parser.add_argument("--head", required=True)
parser.add_argument("--map", required=True)
parser.add_argument("--inventory", required=True)
parser.add_argument("--output", required=True)
args = parser.parse_args()

coverage_map = json.loads(pathlib.Path(args.map).read_text())
inventory = json.loads(pathlib.Path(args.inventory).read_text())["tests"]
inventory_set = set(inventory)

changed_files = git(args.repo, "diff", "--name-only", args.base, args.head).splitlines()
changed_classes = []
for relative in changed_files:
    if "/src/main/java/" not in "/" + relative or not relative.endswith(".java"):
        continue
    source = git(args.repo, "show", f"{args.head}:{relative}")
    package = re.search(r"^package\s+([\w.]+);", source, re.MULTILINE)
    if not package:
        raise SystemExit(f"No package declaration in {relative}")
    changed_classes.append(f"{package.group(1)}.{pathlib.Path(relative).stem}")

direct = {
    identity
    for identity, facts in coverage_map["tests"].items()
    if set(facts.get("classes", ())).intersection(changed_classes)
}
unmapped = {
    item if isinstance(item, str) else item["test"]
    for item in coverage_map.get("unmapped", ())
}
seed = (direct | unmapped) & inventory_set
selected_declared_signatures = sorted(seed)
selected = sorted({maven_execution_identity(identity) for identity in seed})
selected_classes = {test_class(identity) for identity in selected}

canonical = "\n".join(selected) + ("\n" if selected else "")
result = {
    "algorithm": "independent documented class-coverage union normalized to Maven Surefire method-name granularity",
    "base": args.base,
    "head": args.head,
    "mapRevision": coverage_map["revision"],
    "changedProductionClasses": sorted(changed_classes),
    "directCoverageIdentities": sorted(direct),
    "publishedUnmappedIdentities": sorted(unmapped),
    "selectedDeclaredSignatures": selected_declared_signatures,
    "selectedTestClasses": sorted(selected_classes),
    "selectedTests": selected,
    "selectedCount": len(selected),
    "canonicalSha256": hashlib.sha256(canonical.encode()).hexdigest(),
}
pathlib.Path(args.output).write_text(json.dumps(result, indent=2) + "\n")
print(json.dumps({k: result[k] for k in ("selectedCount", "canonicalSha256")}, indent=2))
