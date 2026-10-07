# Remote STP observation output contract

## Pre-change behavior audited on `research/asm-codex-remote` at `459a4a18c283e499e39030583b98f69f7086e595`

Before the first output hardening, `output=` was required and parsed as a path, converted to an absolute normalized path relative to the JVM working directory. The agent did not create the file during startup. Method observations accumulated in concurrent in-memory maps and were serialized by a JVM shutdown hook. The hook created missing parent directories, built schema-v2 JSON in memory, wrote a sibling `<name>.tmp`, and then replaced the destination with an atomic move when supported or a regular replace move otherwise. Thus an existing destination was overwritten at shutdown, not appended to. If a process crashed before shutdown, buffered observations were lost; a partially written `.tmp` could remain. Write errors were caught and logged using only the exception class, without the configured path, and did not fail the shutdown hook. The schema already had explicit `schemaVersion: 2` and separate suite/test/request identity fields.

## Contract

At the start of the crash-safety change, the prior hardening already normalized `output=`, created its parent and an empty destination reservation during startup, rejected any existing destination, kept observations in memory, and wrote a unique sibling temp before replacing the reservation at clean shutdown. It had no durable marker distinguishing an interrupted reservation/temp from a completed output. Consequently, a hard crash could leave the empty reservation and/or a temp that made the next startup fail as “already exists”; observations collected only in memory were lost. The crash recovery rules below address that gap. Periodic checkpoints are now enabled by default; see the checkpoint section below.

`output=<path>` names the single Remote STP observation file owned by this application JVM. It may be absolute or relative; relative paths resolve against the JVM working directory, and the resulting path is absolute and normalized.

The agent creates missing parent directories. It uses two sibling lifecycle paths:

- `<output>.lock` is a stable, empty coordination file. The agent holds an OS file lock on it for the run. The file remains after shutdown; the OS releases the lock after clean exit or process death.
- `<output>.inprogress` is an empty run-state marker. It exists only from startup reservation until successful finalization.

At startup the agent obtains the exclusive OS lock, then examines the output, marker, and only the temp files whose names carry the hash of this normalized output path. A completed valid output is never overwritten. A stale marker proves that any empty or malformed regular output and matching temp files belong to an interrupted run and can be removed before starting a fresh run. A marker next to a valid schema-v2 output means a successful checkpoint exists from a run that did not finalize cleanly: the checkpoint is preserved, matching temps and marker are removed, and startup fails so the operator can archive or inspect the previous run. For migration from the immediately previous contract, an empty output reservation or the exact sibling `<output>.tmp` is recognized as an interrupted run even without a marker. Malformed markers, non-empty invalid output without a marker, special-file targets, or orphan hash-prefixed temps without a marker fail clearly and are preserved for inspection.

## File-state lifecycle

| Point of termination | Files that can remain | Next startup behavior |
|---|---|---|
| Before the agent claims the path | none | normal startup |
| After acquiring the lock but before creating the marker | `.lock` | lock is reacquired; normal startup |
| Legacy interrupted run from the previous contract | empty output reservation and/or exact `<output>.tmp` | remove only those recognized legacy artifacts and start fresh |
| After marker/reservation, before first checkpoint | `.lock`, `.inprogress`, empty reserved output | marker proves interruption; remove reservation and start fresh |
| During snapshot serialization or temp-file write | `.lock`, `.inprogress`, and previous valid checkpoint (or empty reservation), plus possibly partial path-hashed temp | preserve a prior valid checkpoint and fail as interrupted; otherwise remove invalid reservation/temp and start fresh |
| After temp write, before move | `.lock`, `.inprogress`, previous valid checkpoint (or empty reservation), complete path-hashed temp | preserve prior valid checkpoint and fail as interrupted; otherwise remove reservation/temp and start fresh |
| During atomic move | `.lock`, `.inprogress`, and either previous valid checkpoint or new valid checkpoint | preserve valid file, remove marker/matching temps, fail as interrupted |
| During non-atomic fallback move | `.lock`, `.inprogress`, previous checkpoint backup in an output-specific temp and possibly empty/invalid/new output | attempt to restore the previous checkpoint on replacement failure; startup preserves any valid checkpoint |
| After a successful periodic checkpoint | `.lock`, `.inprogress`, valid schema-v2 checkpoint | preserve the checkpoint, remove marker/matching temps, and fail as interrupted |
| After final move but before marker removal | `.lock`, `.inprogress`, valid output | preserve output, remove marker/matching temps, fail as interrupted |
| After clean shutdown | `.lock`, valid output; no marker or temp | fail on restart because completed output exists |

The observation payload is held in memory until the next successful checkpoint. A hard crash loses only hits recorded after the last successful checkpoint; restart recovery preserves a valid checkpoint but does not resume or merge into that output. The `.lock` file is intentionally retained so concurrent JVM starts cannot race while creating/removing a marker. It is not a run-state marker and may be reused for later attempts after the OS releases its lock.

## Finalization and schema

`flushIntervalSeconds=<N>` controls periodic checkpoints and defaults to `60`; values below `1` are rejected. A single daemon scheduler triggers checkpoints. Each checkpoint snapshots pending in-memory hits, unions them with the last persisted schema-v2 data by the structured suite/test/request tuple, serializes the complete result, and writes/syncs a unique same-directory temp. The temp replaces the output atomically where supported; a fallback keeps an output-specific backup and attempts rollback if replacement fails. Only after successful replacement are checkpointed methods merged into the persisted set and removed from pending memory. Recording continues during disk I/O; hits arriving after the snapshot remain pending unless their methods were also included in the checkpoint.

At clean shutdown, the scheduler stops, new hits are rejected, and a final checkpoint persists remaining pending methods before the `.inprogress` marker is removed. If a checkpoint fails, pending memory is retained and the prior valid checkpoint is left as the recovery target. Temporary cleanup matches only `.stp-remote-<sha256-of-normalized-output-path>-*.tmp`. Unrelated `.tmp` files are never removed.

The output schema remains version 2:

```json
{
  "schemaVersion": 2,
  "source": {
    "serviceId": "pricing-service",
    "instanceId": "instance-123",
    "revision": "abc123"
  },
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

New agent output requires the producer `source` object. Older schema-v2 files without `source` remain recognizable as completed outputs so a newer agent will not overwrite them; distributed joining rejects such legacy fragments because their producer cannot be established.

Each request tuple is persisted independently. Method hits are deduplicated only inside one tuple. Output records and methods are sorted for stable output. A final file is considered completed only when it parses as JSON schema v2 and each request has valid structured identity fields and a method array.

Invalid, existing, non-directory-parent, active-lock, malformed-marker, and unwritable states fail with the normalized configured output path in the error. A serialization or write failure is reported from the shutdown hook with the path and cause; the marker remains for recovery. No partial JSON is published when serialization fails.

## Verification

`RemoteAgentConfigurationTest` covers normalized relative paths, empty/invalid paths, parent creation, existing-file preservation, directory targets, parent paths that are files, and unwritable parents. `RemoteRecorderTest` checks concurrent recording during checkpoint, checkpoint merge/compaction, failure retention, periodic execution, and final shutdown checkpoint. Child-JVM tests use forced termination before and after checkpoints to cover reservation cleanup, loss of only uncheckpointed observations, stale temp cleanup, active lock rejection, valid-checkpoint preservation, malformed states, legacy-artifact recovery, and restart recovery. The PetClinic/Karate/OpenTelemetry E2E validates persisted schema-v2 files with Python's JSON parser and exact client/server request joins.
