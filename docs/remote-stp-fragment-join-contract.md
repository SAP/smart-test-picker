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
