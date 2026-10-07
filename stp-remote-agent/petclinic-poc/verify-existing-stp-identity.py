#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0
import json
import re
import sys
from collections import defaultdict
from pathlib import Path


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def main():
    if len(sys.argv) != 6:
        raise SystemExit("usage: verify-existing-stp-identity.py CLIENT.json REMOTE.json TEST-OUTPUT.log CONTROL-STATUS.txt MODE")
    client_path, remote_path, test_output_path, control_path = map(Path, sys.argv[1:5])
    mode = sys.argv[5]
    client = json.loads(client_path.read_text(encoding="utf-8"))
    remote = json.loads(remote_path.read_text(encoding="utf-8"))
    test_output = test_output_path.read_text(encoding="utf-8")
    control_status = control_path.read_text(encoding="utf-8").strip()

    require(client["configuration"]["instrumentation"] == "off", "client STP agent was not configured with instrumentation=off")
    require(client["bytecodeModified"] is False, "client STP agent reports bytecodeModified=true")
    runtime = client.get("runtimeEvents")
    require(isinstance(runtime, dict), "client STP output has no runtimeEvents object")
    tests = runtime.get("tests", [])
    by_id = {}
    by_method = defaultdict(list)
    for test in tests:
        identity = test.get("testId")
        require(identity, f"client test has no platformUniqueId: {test}")
        require(identity not in by_id, f"duplicate client platformUniqueId: {identity}")
        require("testClass" in test and "testMethod" in test and "testMethodParameterTypes" in test and "result" in test,
                f"client STP output lacks join metadata for {identity}")
        require(test["result"].get("status") == "SUCCESSFUL",
                f"client test did not pass: {identity} result={test['result']}")
        by_id[identity] = test
        by_method[test["testMethod"]].append(test)
        print("CLIENT testId={} testClass={} testMethod={} testMethodParameterTypes={} result={}".format(
            identity, test["testClass"], test["testMethod"], test["testMethodParameterTypes"],
            test["result"].get("status")))

    require(client.get("configuration", {}).get("output"), "client STP output path is missing")
    expected_methods = {"vetsRequest", "ownerRequest", "ownersParameterized", "noHttpTest"}
    require(expected_methods.issubset(by_method), f"missing client tests: {expected_methods - set(by_method)}")
    require(len(by_method["vetsRequest"]) == 1, "expected exactly one vetsRequest")
    require(len(by_method["ownerRequest"]) == 1, "expected exactly one ownerRequest")
    require(len(by_method["noHttpTest"]) == 1, "expected exactly one noHttpTest")
    parameterized = by_method["ownersParameterized"]
    require(len(parameterized) == 3, f"expected three ownersParameterized invocations, got {len(parameterized)}")
    parameterized_ids = {test["testId"] for test in parameterized}
    require(len(parameterized_ids) == 3, "parameterized invocations did not have three distinct platformUniqueId values")

    remote_tests = remote.get("requests", [])
    remote_by_key = {}
    for observation in remote_tests:
        identity = (observation.get("testSuiteId"), observation.get("testId"), observation.get("requestId"))
        require(all(identity) and identity not in remote_by_key, f"missing or duplicate remote request identity: {identity}")
        remote_by_key[identity] = observation
        print(f"REMOTE suiteId={identity[0]} testId={identity[1]} requestId={identity[2]} method count={len(observation.get('methods', []))}")

    correlations = {}
    for match in re.finditer(r"REQUEST_CORRELATION suiteId=(\S+) testId=(\S+) requestId=(\S+) path=(\S+)", test_output):
        suite_id, test_id, request_id, path = match.groups()
        key = (suite_id, test_id, request_id)
        require(key not in correlations, f"duplicate client request correlation: {key}")
        correlations[key] = path
        require(test_id in by_id, f"HTTP request uses unknown client TestID {test_id}")
    expected_http_tests = [by_method[name][0] for name in ("vetsRequest", "ownerRequest")]
    expected_http_tests.extend(parameterized)
    expected_ids = {test["testId"] for test in expected_http_tests}
    require(set(remote_by_key) == set(correlations),
            f"remote/client request join mismatch; missing remote={set(correlations) - set(remote_by_key)}, "
            f"unknown remote={set(remote_by_key) - set(correlations)}")
    require({key[1] for key in correlations} == expected_ids,
            f"HTTP correlation evidence does not match expected test IDs: {set(key[1] for key in correlations) ^ expected_ids}")
    require(by_method["noHttpTest"][0]["testId"] not in {key[1] for key in remote_by_key},
            "noHttpTest unexpectedly has a remote observation")
    require({key[1] for key in remote_by_key}.issubset(by_id), "remote observations have unknown client identities")
    by_test_observation = {key[1]: value for key, value in remote_by_key.items()}
    require(len(by_test_observation) == len(remote_by_key), "one client test produced multiple requests in this fixture unexpectedly")

    def methods_for(test):
        return by_test_observation[test["testId"]].get("methods", [])

    vet_test = by_method["vetsRequest"][0]
    vet_methods = methods_for(vet_test)
    require(any(method.startswith("org.springframework.samples.petclinic.vet.VetController#showResourcesVetList")
                for method in vet_methods), f"vetsRequest missed Vet path: {vet_methods}")
    require(not any("org.springframework.samples.petclinic.owner.OwnerController#" in method for method in vet_methods),
            f"Owner methods cross-attributed to vetsRequest: {vet_methods}")

    owner_tests = [by_method["ownerRequest"][0], *parameterized]
    for test in owner_tests:
        methods = methods_for(test)
        require(any("org.springframework.samples.petclinic.owner.OwnerController#showOwner" in method for method in methods),
                f"{test['testId']} missed Owner path: {methods}")
        require(not any("org.springframework.samples.petclinic.vet.VetController#" in method for method in methods),
                f"Vet methods cross-attributed to {test['testId']}: {methods}")

    require(control_status == "200", f"headerless control GET /vets returned {control_status}, expected 200")
    print(f"HEADERLESS_CONTROL status={control_status}; no STP Baggage identity was sent")
    print(f"JOIN PASS: {len(remote_by_key)} remote request tuples exactly match client-emitted SuiteID/TestID/RequestID values")
    print(f"CLIENT STP instrumentation={client['configuration']['instrumentation']}; bytecodeModified={str(client['bytecodeModified']).lower()}")

    if mode == "parallel":
        starts = {}
        ends = {}
        for match in re.finditer(r"HTTP_EVENT phase=(START|END) testId=(\S+) testMethod=(\S+) path=(\S+) nanos=(\d+)", test_output):
            phase, identity, method, _path, nanos = match.groups()
            target = starts if phase == "START" else ends
            target[(identity, method)] = int(nanos)
        vet_ids = {test["testId"] for test in by_method["vetsRequest"]}
        owner_ids = {test["testId"] for test in by_method["ownerRequest"]}
        overlaps = []
        for vet_id in vet_ids:
            for owner_id in owner_ids:
                vet_start = starts.get((vet_id, "vetsRequest"))
                vet_end = ends.get((vet_id, "vetsRequest"))
                owner_start = starts.get((owner_id, "ownerRequest"))
                owner_end = ends.get((owner_id, "ownerRequest"))
                if None not in (vet_start, vet_end, owner_start, owner_end) and max(vet_start, owner_start) < min(vet_end, owner_end):
                    overlaps.append((vet_id, owner_id, max(vet_start, owner_start), min(vet_end, owner_end)))
        barrier_methods = set(re.findall(r"PAIR_BARRIER_READY testMethod=(\S+)", test_output))
        if overlaps and {"vetsRequest", "ownerRequest"}.issubset(barrier_methods):
            vet_id, owner_id, overlap_start, overlap_end = overlaps[0]
            print(f"CONCURRENCY_PROVEN vetsRequest={vet_id} ownerRequest={owner_id} overlapNanos=[{overlap_start},{overlap_end}]")
        else:
            print("CONCURRENCY_NOT_PROVEN")

    print("PASS: existing STP identities correlate exactly to their PetClinic request-thread observations")


if __name__ == "__main__":
    main()
