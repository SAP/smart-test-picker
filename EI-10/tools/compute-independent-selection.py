#!/usr/bin/env python3
"""Independently derive the deterministic EI-10 class-level expectation from the R0 map."""
import itertools
import json
import pathlib
import random
import re
import sys

SEED = 1010
METHODS = {
    "org.springframework.core.io.support.PathMatchingResourcePatternResolver": "getResource(String)",
    "org.springframework.core.io.support.DefaultPropertySourceFactory": "createPropertySource(String, EncodedResource)",
    "org.springframework.core.convert.support.MapToMapConverter": "convert(Object, TypeDescriptor, TypeDescriptor)",
}

evidence = pathlib.Path(__file__).resolve().parents[1]
workspace = evidence.parent
spring = workspace / "EI-9/spring-framework"
map_file = evidence / "jenkins/job-a-build-14-artifacts/archive/build/stp/coverage-map-ei10.json"
inventory_file = evidence / "jenkins/job-a-build-14-artifacts/archive/build/executable-head-test-inventory.json"
coverage = json.loads(map_file.read_text())
inventory = json.loads(inventory_file.read_text())["tests"]
mapped_inventory = set(coverage["tests"])
by_class = {}
for test, value in coverage["tests"].items():
    for class_name in value["classes"]:
        if "$" not in class_name:
            by_class.setdefault(class_name, set()).add(test)

candidates = []
filter_rejections = {}
source_root = spring / "spring-core/src/main/java"
for class_name, tests in sorted(by_class.items()):
    source = source_root.joinpath(*class_name.split(".")).with_suffix(".java")
    reason = None
    if not source.is_file():
        reason = "no main source"
    else:
        text = source.read_text(errors="ignore")
        simple = re.escape(class_name.rsplit(".", 1)[-1])
        if (re.search(r"\babstract\s+class\s+" + simple + r"\b", text)
                or re.search(r"\b(interface|enum|record)\s+" + simple + r"\b", text)):
            reason = "non-concrete"
        elif source.name in {"package-info.java", "module-info.java"} or "/generated/" in str(source):
            reason = "unsafe source"
        elif not tests or tests == mapped_inventory:
            reason = "empty or complete inventory mapping"
    if reason:
        filter_rejections[reason] = filter_rejections.get(reason, 0) + 1
    else:
        candidates.append((class_name, source, tests))

ordered = candidates[:]
random.Random(SEED).shuffle(ordered)
combination_rejections = []
chosen = None
for combination in itertools.combinations(ordered, 3):
    union = set().union(*(item[2] for item in combination))
    if not union:
        combination_rejections.append({"classes": [item[0] for item in combination], "reason": "empty union"})
    elif len(union) >= len(inventory):
        combination_rejections.append({"classes": [item[0] for item in combination], "reason": "not a strict subset"})
    else:
        chosen = combination
        break
if chosen is None:
    raise SystemExit("no eligible deterministic combination")
if [item[0] for item in chosen] != list(METHODS):
    raise SystemExit("deterministic selection changed")

head_tests = {identity.rsplit("::", 1)[-1] for identity in inventory}
tests_by_container = {}
for test in head_tests:
    tests_by_container.setdefault(test.split("#", 1)[0], set()).add(test)
coverage_reasons = {}
selected_set = set()
for class_name, _source, direct_executables in chosen:
    direct = {identity.rsplit("::", 1)[-1] for identity in direct_executables}
    setup = set()
    setup_scopes = []
    for scope in coverage["setupScopes"]:
        if class_name not in scope["coveredClasses"]:
            continue
        setup_scopes.append(scope["id"])
        for container in scope["affectedContainers"]:
            setup.update(tests_by_container.get(container, set()))
    tests = direct | setup
    selected_set.update(tests)
    coverage_reasons[class_name] = {
        "directCoverageTests": sorted(direct),
        "setupScopeIds": sorted(setup_scopes),
        "setupScopeTests": sorted(setup),
        "tests": sorted(tests),
    }
selected = sorted(selected_set & head_tests)
result = {
    "semantics": "class-level coverage selection",
    "randomSeed": SEED,
    "candidateCount": len(candidates),
    "filterRejections": filter_rejections,
    "rejectedDeterministicCombinations": combination_rejections,
    "changedFiles": [str(item[1].relative_to(spring)) for item in chosen],
    "changedClasses": [item[0] for item in chosen],
    "changedMethods": METHODS,
    "coverageReasons": coverage_reasons,
    "expectedTests": selected,
    "expectedCount": len(selected),
    "inventoryCount": len(inventory),
    "mappedInventoryCount": len(mapped_inventory),
    "expectedPercentage": len(selected) * 100.0 / len(inventory),
    "nonEmpty": bool(selected),
    "strictSubset": len(selected) < len(inventory),
}
json.dump(result, sys.stdout, indent=2)
sys.stdout.write("\n")
