# Remote STP fragment join contract

## Purpose

A fragment is one schema-v2 output produced by one monitored application JVM. Joining is a local, deterministic projection over exactly the fragments supplied. It does not claim that all tests, services, or application instances have been collected.

## Input

Every input must be valid JSON with `schemaVersion: 2`, a `source` object, and a `requests` array. A source has nonblank `serviceId`, `instanceId`, and `revision` strings, each at most 256 characters and without ISO control characters. Every request has nonblank `testSuiteId`, `testId`, and `requestId` strings under the same limits, plus a `methods` array of method identity strings. Legacy schema-v2 files without `source` are not joinable because their producer cannot be identified.

Reject the whole join when any input is unreadable, malformed, has an unsupported schema version, lacks required fields, or contains invalid identities. Diagnostics identify the input path and the invalid field/reason. Do not silently skip bad fragments.

## Combined output schema

The join result uses schema version 1 and retains one observation row for each distinct test/request/source tuple:

```json
{
  "schemaVersion": 1,
  "observations": [
    {
      "testSuiteId": "checkout-suite",
      "testId": "scenario-17",
      "requestId": "request-abc",
      "source": {
        "serviceId": "pricing-service",
        "instanceId": "pod-2",
        "revision": "abc123"
      },
      "methods": [
        "com.example.CartController#add()V",
        "com.example.CartService#add()V"
      ]
    }
  ]
}
```

Each observation contains the complete test identity, concrete request identity, producer service/instance/revision, and methods. Keeping source metadata on each observation makes the relation `test → request → producer fragment → methods` explicit and supports later reverse lookup from a changed method to its tests. This result is not a new Remote STP agent fragment and must not be fed back as schema v2.

## Join and deduplication

The logical observation key is the six-field tuple:

```text
(testSuiteId, testId, requestId, serviceId, instanceId, revision)
```

For rows with the same key, union and deduplicate their method sets. Different source tuples always remain separate rows, including different revisions of the same service instance. Different RequestIDs remain separate. A duplicate input file therefore has no effect on the logical result.

The join does not union observations across RequestIDs and does not infer relationships based on method names, test names, host addresses, or file names.

## Deterministic ordering

Serialize UTF-8 JSON with stable formatting. Sort observations lexicographically by the six key fields in the order shown above, using each string's natural Unicode code-point order. Sort methods lexicographically by the same rule. Input file order and duplicate input paths do not affect the parsed logical result or serialized ordering. A CLI may choose a stable whitespace/escaping convention, but must produce byte-identical output for the same logical input set.

## Completeness and ownership

The joiner combines exactly the files supplied. It does not check whether every pod or service participated, whether a suite completed, whether files represent the same run, or whether the result is globally complete. Retrieval and completeness policy belong to the caller.

## Select one client run

`join-remote` can additionally read one Karate execution manifest (schema v1):

```bash
java -jar smart-test-picker-cli-0.3.0-SNAPSHOT-all.jar join-remote \
  --input observations.json \
  --client-manifest stp-karate-suite-run.json \
  --output joined-map.json \
  --report correlation-report.json
```

`--input` remains server schema-v2 fragments only. The manifest supplies `source.suiteId`,
`source.runId`, and request metadata. Selection uses exact equality of
`(source.suiteId, requests[].testId, requests[].requestId)` with the remote tuple.
RunID is client provenance, not a remote join key. Server observations absent from
this manifest are excluded, including requests from older executions of the same suite/test.
All remote input records are validated, even those later excluded.

The joined map retains its existing schema v1. Source boundaries and method union
rules above are unchanged. Identical duplicate client records are accepted once;
conflicting metadata or reuse of a RequestID by another TestID in a manifest fails.
Retries and multiple calls with different RequestIDs remain distinct requests.
The CLI cannot infer the successful retry, reconstruct missing earlier manifests,
or deduplicate by URL/TestID without losing concrete request evidence.

The optional `--report` writes a separate correlation diagnostic JSON (schemaVersion 1),
with `clientSource` (suiteId/runId), raw/unique/duplicate client counts, matched/unmatched
client counts, excluded unique remote requests, and selected observation count. Its
`requests` array retains the validated client scenario/HTTP metadata, adds `testSuiteId`,
`status` (`MATCHED` or `UNMATCHED`) and `observationCount`. A request observed by multiple
sources counts as one matched client request and multiple source observations.
The report is diagnostic evidence, not a new observation-map format. Treat request
URIs and feature metadata in it with the same confidentiality as the input manifest.

Unmatched requests are reported, never synthesized as empty server observations.
They may target an uninstrumented external service, but the CLI does not assume a cause
or silently declare global completeness. Default exit 0 means the join succeeded;
missing matches also print a warning. Use `--require-all-matched` to return exit 2
when any client request is unmatched; the partial map and requested report are still
written for inspection. Invalid input/write errors return 1. Without `--report`,
unmatched correlation keys are printed to stderr. `--report` and
`--require-all-matched` require `--client-manifest`.

Output/report paths must differ from every input and from each other (including
existing hard-link/symlink aliases). Each output is published using temp + move;
the map and report are not a transactional pair. No input is modified.
