# Remote STP observation output contract

## Pre-change behavior audited on `research/asm-codex-remote` at `459a4a18c283e499e39030583b98f69f7086e595`

Before the first output hardening, `output=` was required and parsed as a path, converted to an absolute normalized path relative to the JVM working directory. The agent did not create the file during startup. Method observations accumulated in concurrent in-memory maps and were serialized by a JVM shutdown hook. The hook created missing parent directories, built schema-v2 JSON in memory, wrote a sibling `<name>.tmp`, and then replaced the destination with an atomic move when supported or a regular replace move otherwise. Thus an existing destination was overwritten at shutdown, not appended to. If a process crashed before shutdown, buffered observations were lost; a partially written `.tmp` could remain. Write errors were caught and logged using only the exception class, without the configured path, and did not fail the shutdown hook. The schema already had explicit `schemaVersion: 2` and separate suite/test/request identity fields.

## Contract

At the start of the crash-safety change, the prior hardening already normalized `output=`, created its parent and an empty destination reservation during startup, rejected any existing destination, kept observations in memory, and wrote a unique sibling temp before replacing the reservation at clean shutdown. It had no durable marker distinguishing an interrupted reservation/temp from a completed output. Consequently, a hard crash could leave the empty reservation and/or a temp that made the next startup fail as “already exists”; observations collected only in memory were lost. The recovery rules below address that gap.

`output=<path>` names the single Remote STP observation file owned by this application JVM. It may be absolute or relative; relative paths resolve against the JVM working directory, and the resulting path is absolute and normalized.

The agent creates missing parent directories. It uses two sibling lifecycle paths:

- `<output>.lock` is a stable, empty coordination file. The agent holds an OS file lock on it for the run. The file remains after shutdown; the OS releases the lock after clean exit or process death.
- `<output>.inprogress` is an empty run-state marker. It exists only from startup reservation until successful finalization.

At startup the agent obtains the exclusive OS lock, then examines the output, marker, and only the temp files whose names carry the hash of this normalized output path. A completed valid output is never overwritten. A stale marker proves that any empty or malformed regular output and matching temp files belong to an interrupted run and can be removed before starting a fresh run. A marker next to a valid schema-v2 output means the process crashed after publishing the final file but before marker cleanup: the final file is preserved, matching temps and marker are removed, and startup fails as a completed run. For migration from the immediately previous contract, an empty output reservation or the exact sibling `<output>.tmp` is recognized as an interrupted run even without a marker. Malformed markers, non-empty invalid output without a marker, special-file targets, or orphan hash-prefixed temps without a marker fail clearly and are preserved for inspection.

## File-state lifecycle

| Point of termination | Files that can remain | Next startup behavior |
|---|---|---|
| Before the agent claims the path | none | normal startup |
| After acquiring the lock but before creating the marker | `.lock` | lock is reacquired; normal startup |
| Legacy interrupted run from the previous contract | empty output reservation and/or exact `<output>.tmp` | remove only those recognized legacy artifacts and start fresh |
| After marker/reservation, before serialization or during in-memory serialization | `.lock`, `.inprogress`, empty reserved output | marker proves interruption; remove reservation and start fresh |
| During temp-file write, before move | `.lock`, `.inprogress`, empty reserved output, possibly partial path-hashed temp | remove reservation and only matching temps; start fresh |
| After temp write, before move | `.lock`, `.inprogress`, empty reserved output, complete path-hashed temp | remove reservation and only matching temps; start fresh |
| During atomic move | `.lock`, `.inprogress`, and either empty output plus temp or valid output | recover the incomplete side, or preserve valid output and fail as completed |
| During non-atomic fallback move | `.lock`, `.inprogress`, and empty/invalid/valid output, possibly temp | remove invalid output and matching temps, or preserve valid output and fail as completed |
| After final move but before marker removal | `.lock`, `.inprogress`, valid output | preserve output, remove marker/matching temps, fail as completed |
| After clean shutdown | `.lock`, valid output; no marker or temp | fail on restart because completed output exists |

The observation payload remains memory-only until shutdown. A hard crash loses those observations; restart recovery repairs file state but does not recover unpersisted data. The `.lock` file is intentionally retained so concurrent JVM starts cannot race while creating/removing a marker. It is not a run-state marker and may be reused for later attempts after the OS releases its lock.

## Finalization and schema

At shutdown, the recorder stops accepting hits and takes a synchronized snapshot. It serializes the complete schema-v2 document in memory, writes and syncs a unique same-directory temp file, moves that file over the empty reservation atomically where supported, and removes the `.inprogress` marker last. If atomic move is unavailable, the complete temp is moved with a same-directory replace fallback; JSON is never streamed into the final file. Temporary cleanup matches only `.stp-remote-<sha256-of-normalized-output-path>-*.tmp`. Unrelated `.tmp` files are never removed.

The output schema remains version 2:

```json
{
  "schemaVersion": 2,
  "requests": [
    {
      "testSuiteId": "suite-A",
      "testId": "test-1",
      "requestId": "request-1",
      "methods": ["com.example.Controller#handle()V"]
    }
  ]
}
```

Each request tuple is persisted independently. Method hits are deduplicated only inside one tuple. Output records and methods are sorted for stable output. A final file is considered completed only when it parses as JSON schema v2 and each request has valid structured identity fields and a method array.

Invalid, existing, non-directory-parent, active-lock, malformed-marker, and unwritable states fail with the normalized configured output path in the error. A serialization or write failure is reported from the shutdown hook with the path and cause; the marker remains for recovery. No partial JSON is published when serialization fails.

## Verification

`RemoteAgentConfigurationTest` covers normalized relative paths, empty/invalid paths, parent creation, existing-file preservation, directory targets, parent paths that are files, and unwritable parents. `RemoteRecorderTest` checks concurrent snapshots and failed-write cleanup. Child-JVM tests use forced termination to cover startup reservation, loss of in-memory observations, stale temp cleanup, active lock rejection, valid-output preservation, malformed states, legacy-artifact recovery, and restart recovery. The PetClinic/Karate/OpenTelemetry E2E validates persisted schema-v2 files with Python's JSON parser and exact client/server request joins.
