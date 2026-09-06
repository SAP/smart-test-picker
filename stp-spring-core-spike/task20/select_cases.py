#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0
"""Select a fixed, descriptor-exact, shape-diverse subset of TASK 19 FNs."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
EVIDENCE = ROOT / "stp-spring-core-spike/task20/evidence"
SELECTION = ROOT / "stp-spring-core-spike/task19/evidence/selected-spring-tests.json"

WANTED = [
    ("enum-reference-type-constructor", "AccessControlTests#forMemberWhenPublicClassWithPublicMethodAndPackagePrivateGenericOnReturnType_78b5ffb", "org.springframework.util.ConcurrentReferenceHashMap$ReferenceType#<init>", "implicit enum constructor; mandatory order contrast"),
    ("enum-restructure-constructor", "AccessControlTests#forMemberWhenPublicClassWithPublicMethodAndPackagePrivateGenericOnReturnType_78b5ffb", "org.springframework.util.ConcurrentReferenceHashMap$Restructure#<init>", "second implicit enum constructor; mandatory order contrast"),
    ("record-element-accessor", "ReflectiveRuntimeHintsRegistrarTests#shouldProcessWithMultipleProcessorsWithAnnotationOnType_4c715ce", "org.springframework.aot.hint.annotation.ReflectiveRuntimeHintsRegistrar$Entry#element", "simple record accessor in reflection-related path"),
    ("record-processor-accessor", "ReflectiveRuntimeHintsRegistrarTests#shouldProcessWithMultipleProcessorsWithAnnotationOnType_4c715ce", "org.springframework.aot.hint.annotation.ReflectiveRuntimeHintsRegistrar$Entry#processor", "second simple record accessor"),
    ("bridge-soft-reference-get", "InheritedAnnotationsAnnotationMetadataTests#hasAnnotation_40576fd", "org.springframework.util.ConcurrentReferenceHashMap$SoftEntryReference#get", "cache-internal synthetic bridge method; stable alternate TASK 19 edge after the bridge-named test reproduced its known reflection-order assertion failure twice"),
    ("cache-get", "MergedAnnotationsTests#synthesizeWhenAttributeAliasForMetaAnnotationThatIsNotMetaPresent_1a62141", "org.springframework.core.annotation.AnnotationTypeMappings$Cache#get", "ordinary cache-internal method"),
    ("ordinary-after-mappings", "MergedAnnotationsTests#synthesizeWhenAttributeAliasForMetaAnnotationThatIsNotMetaPresent_1a62141", "org.springframework.core.annotation.AnnotationTypeMapping#afterAllMappingsSet", "ordinary synchronous method"),
    ("ordinary-constructor", "ConvertingComparatorTests#shouldThrowOnNullType_4e2242d", "org.springframework.core.convert.converter.ConvertingComparator#<init>", "non-enum constructor"),
    ("async-before-access", "SimpleAsyncTaskExecutorTests#cannotExecuteWhenConcurrencyIsSwitchedOff_33a3d64", "org.springframework.core.task.SimpleAsyncTaskExecutor$ConcurrencyThrottleAdapter#beforeAccess", "short non-constructor async-related method"),
    ("synthetic-switch-clinit", "BindingReflectionHintsRegistrarTests#registerTypeForJacksonAnnotations_203971c", "org.springframework.core.annotation.AnnotationsScanner$1#<clinit>", "synthetic switch-map static initializer"),
]


def main():
    inventory = json.loads((EVIDENCE / "jacoco-fn-inventory.json").read_text())["edges"]
    tests = json.loads(SELECTION.read_text())["tests"]
    selectors = {item["testIdentity"]: item["gradleSelector"] for item in tests}
    by_key = {(item["testIdentity"], item["methodIdentity"]): item for item in inventory}
    selected = []
    for case_id, test, method, reason in WANTED:
        item = dict(by_key[(test, method)])
        if len(item["descriptorCandidates"]) != 1:
            raise SystemExit(f"case {case_id} is not descriptor-exact")
        item.update({"caseId": case_id, "descriptor": item["descriptorCandidates"][0],
                     "gradleSelector": selectors[test], "selectionReason": reason})
        selected.append(item)
    (EVIDENCE / "selected-fn-cases.json").write_text(json.dumps({
        "schemaVersion": "task20-selected-fn-cases-1", "count": len(selected),
        "selectionRule": "Fixed shape-diverse descriptor-exact subset of the exact 57-edge input",
        "cases": selected}, indent=2, sort_keys=True) + "\n")


if __name__ == "__main__":
    main()
