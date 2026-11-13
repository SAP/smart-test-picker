# STP-MAP-FORMAT-01 validation

Base: `d36a33d4e4afd8b8f0708264bbcc8a0217e25c8f`.
Branch: `feature/compact-streaming-v3-map`.
Validation is local; no CI or external subject execution was requested or run.

## Scope and representation decision

All 26 retained fragments were decoded one at a time with the base-revision codec.
Of **28,490 mapped tests**, **28,490** had a class set exactly equal to the owners of their
descriptor-qualified methods; **0 differed**. There are no differing examples to list.
The actual alltests map therefore uses `classMode: "derived"` and stores no per-test classes.
The codec also supports explicit class-index deltas when a different, valid input map contains
class-only coverage. A permanent regression proves that those classes are not lost.

The map uses sorted class/method dictionaries and per-test method-index deltas, without coverage-set
deduplication. Setup scopes use the same class dictionary. The scope model has no method-reference
field. Unmapped entries reference identities, not production classes or methods.

Only the v3 map representation changes. `ExecutableCoverageJson`,
`ExecutableCoverageFragmentCodec`, and `ExecutableShardAssignmentCodec` are byte-for-byte unchanged
from the base revision. No collector, agent, join policy, publication policy, or v1/v2 codec changed.
The v3 map model now orders setup scopes by owner/id to make serialization deterministic even when
two targets use the same scope id; ownership and coverage are unchanged.

## Streaming and consumer audit

| Site | Change or disposition |
|---|---|
| `ExecutableCoverageMapCodec` | Path/stream write and verified Path/stream read replace whole-map byte-array APIs. Gzip, canonical SHA-256, long uncompressed-byte accounting. |
| `JoinFragmentsCommand.writeVerified` | Writes staging, streams read-back, validates, compares full records, loads through selection, then atomically installs. No whole-map `readAllBytes`. |
| `JoinCoveragePool` | Shares class strings and method identities across retained fragments, after each unchanged fragment decoder finishes. |
| `ExplicitPrSelectorFlow.select` / `selectionMap` | Path-based v3 load using the streaming codec. Logical projection and selection policy unchanged. |
| `ExplicitPrSelectorFlow.schemaVersion` | Streaming schema inspection with gzip-header detection. |
| `SelectTestsCommand.selectExplicitPr` | Uses the shared streaming schema inspection instead of constructing a JSON DOM. |
| `ExecutableCoverageMapPublication` / joiner | In-memory model consumers, no file serialization; unchanged. |
| Test callers in common, CLI, Maven, Gradle and runtime | Migrated v3 map fixtures to Path APIs; fragment fixture serialization unchanged. |

There are no other production references to the v3 map codec. Legacy `CoverageMapCodec` and
`SchemaV2SelectionAnalyzer` retain their v2 byte-array paths. The generic remote-store push/pull
commands/tasks and `RemoteStoreClient` transport opaque file bytes and still buffer their payloads;
they do not decode the v3 map and were not migrated or qualified in this map-codec task. This report
does not claim streaming remote transfer. Their legacy behavior was left unchanged.

The reader retains one dictionary object per distinct class/method and reuses it across test sets.
The CLI also interns these values across decoded fragments. It does **not** eliminate the in-memory
sets of references: memory is proportional to reference count, and fragment parsing still uses the
existing fragment codec. No whole-map JSON string, byte array, or JSON DOM exists in the new v3 map
write/read/selection-load path.

## Permanent regressions

`MapIndexDeltasTest` checks sorted delta encoding and rejects invalid/overflowing/out-of-range indices.
`ExecutableCoverageMapStreamingTest` checks round trips, dictionary object sharing, derived and
explicit class coverage, deterministic output, independent SHA-256 calculation over canonical bytes,
valid-gzip checksum tampering, truncated gzip trailers, and recompression at different levels.

Its large-map test actually writes and reads **2,160,138,124 uncompressed bytes** (more than
`Integer.MAX_VALUE`), compressed to **2,220,075 bytes**, with **900 tests**. A shared long feature name
keeps the domain model small; its occurrences in tests and completeness make the real stream exceed
2 GiB. No full JSON is assembled for this test, and it runs in the normal common suite with a
512 MiB test heap. This is a measured stream round trip, not an extrapolated size or mocked writer.

`JoinCoveragePoolTest` verifies object sharing across fragments while preserving their full values.
The existing CLI corruption tests now corrupt decompressed content and regenerate a valid gzip
trailer, proving the canonical checksum fails and a previous output survives. Existing staged-map
content/lifecycle/metadata mismatch and failed-publication regressions remain active.

## Real inputs and accounting

The 52 input JSON files in `~/Downloads/mapa` matched the retained initial audit's SHA-256 manifest
both before and after joining: **52 unchanged, 0 mismatches, 0 extra entries**. The alltests result contains:

| Metric | Result |
|---|---:|
| Shards | 26 |
| Mapped tests | 28,490 |
| Unmapped: FAILED | 27 |
| Unmapped: ASSIGNED_NOT_REPORTED | 16 |
| Unmapped: COLLECTION_FAILED | 0 |
| Unmapped: SKIPPED | 0 |
| Total unmapped | 43 |
| Distinct production classes | 19,631 |
| Distinct descriptor-qualified methods | 138,266 |
| Setup scopes | 4,181 |

All 16 `ASSIGNED_NOT_REPORTED` identities exactly match the absent-identity set from
STP-CI-VERIFY-02: symmetric difference **0**. The map's revision remains the input value `unknown`;
this task does not invent or repair revision provenance.

## Resource measurements

Final isolated-JAR join, JDK **21.0.13.0.3**, `-Xmx12g`, `/usr/bin/time -l`:

| Metric | Result |
|---|---:|
| Exit code | 0 |
| Wall time | 283.19 s |
| Peak RSS | 8,908,111,872 bytes |
| Gzip map size | 8,416,837 bytes |
| Actual uncompressed stream | 115,645,463 bytes |

Canonical uncompressed map checksum:
`sha256:61b41e39fa7ded383beba591b583125b5b17c6ab8843b42ac8fd20307a83ca36`.
Gzip file SHA-256 (a different quantity):
`b53cbb15dedced023c0e4c9b4510b01d8e81cbdb70bf65dd797b105872e50983`.

The final resource run used an immutable CLI copy with no concurrent build. Wall time includes
reading all original fragments, joining, gzip writing, production-codec read-back, full model
comparison, the explicit-selection loader, summary construction, and atomic publication.
The first-run measurement problems described below are excluded from these numbers.

The verified output was also copied, without replacing any existing file, to
`~/Downloads/cxmc-alltests-map-compact-v3.json.gz`, outside the input directory.
After the final join, all 52 input hashes still match and the final map's 16 absent identities
again have a zero symmetric difference against the retained audit.

A separate JVM loaded that exact final file through the production
`ExplicitPrSelectorFlow.selectionMap(Path)` entry point, using `-Xmx4g`:

| Metric | Result |
|---|---:|
| Exit code | 0 |
| Wall time (including JVM startup) | 48.03 s |
| Loader call time | 47.950897458 s |
| Peak RSS | 1,023,082,496 bytes |
| Loaded mapped / unmapped / setup scopes | 28,490 / 43 / 4,181 |

The disposable `VerifyMap` helper only calls the production loader and prints its counts; it does
not reimplement selection or perform revision preflight. The command was:

```bash
/usr/bin/time -l java -Xmx4g \
  -cp /private/tmp/stp-map-format-01.n31LWS/validated-cli.jar:/private/tmp/stp-map-format-01.n31LWS \
  VerifyMap selector /private/tmp/stp-map-format-01.n31LWS/cxmc-alltests-final.json.gz
```

## Reference join

The packaged CLI freshly joined the retained PetClinic inputs using the existing separate-directory
options: **69 mapped, 0 unmapped, 10 setup scopes**, 21 distinct classes, 89 distinct methods.
The input fragment SHA-256 is unchanged:

```text
0ecdbed9d086835971a9c69e3a49b6977ca24c42058ce2bf3618d8fc1820b8b9
```

The output is 3,563 gzip bytes / 40,777 uncompressed bytes. Embedded checksum:
`sha256:80f22aa9dce1dd518f456bd9936ea9208fad3267727fa5e2435cd86d083d7bd3`.
This is a fresh join, not a fresh collection run. The format change necessarily changes the map's
bytes and checksum; the fragment hash and decoded coverage/accounting remain unchanged.

## Commands and retained evidence

```bash
./gradlew test --rerun-tasks --offline --max-workers=2
./gradlew :smart-test-picker-cli:shadowJar --offline
./gradlew :smart-test-picker-cli:test :smart-test-picker-cli:shadowJar --rerun-tasks --offline --max-workers=1
cp smart-test-picker-cli/build/libs/smart-test-picker-cli-0.3.0-SNAPSHOT-all.jar \
  /private/tmp/stp-map-format-01.n31LWS/validated-cli.jar
/usr/bin/time -l java -Xmx12g -jar /private/tmp/stp-map-format-01.n31LWS/validated-cli.jar \
  join-fragments --dir "$HOME/Downloads/mapa" --expected-shards 26 \
  --output /private/tmp/stp-map-format-01.n31LWS/cxmc-alltests-final.json.gz
java -jar smart-test-picker-cli/build/libs/smart-test-picker-cli-0.3.0-SNAPSHOT-all.jar join-fragments \
  --fragments-dir verification/stp-join-cli-01/inputs/fragments \
  --assignments-dir verification/stp-join-cli-01/inputs/assignments \
  --head-inventory verification/stp-join-cli-01/inputs/head-inventory.json \
  --output /private/tmp/stp-map-format-01.n31LWS/petclinic-map.json.gz
```

Local raw evidence and disposable measurement helpers are retained under
`/private/tmp/stp-map-format-01.n31LWS/`. The first complete join succeeded but its `/usr/bin/time`
wrapper could not access `kern.clockrate` in the sandbox, returned 1, and omitted RSS. It was not
counted as a valid resource measurement; the join was repeated with permission to read that metric.
Two initial focused CLI tests failed because their corruption hook expected plain JSON; the hook
was updated for gzip without weakening the checksum assertion. Initial logs are retained.

The first permitted resource-measurement repeat was invalidated by rebuilding the same JAR while
its JVM was still loading classes: it failed later with `ClassNotFoundException: UnmappedTest`.
The final measurement therefore uses a separate, immutable copy of the fully rebuilt CLI JAR,
SHA-256 `a8f5a0e1f0fc159bbf60747b12a3dd730fa3421591810cbbddc77b88335431d5`.
The failed measurement log is retained as `real-join-measured.log`; it is not a successful gate.

## Full suites

The fresh eight-module run finished successfully in **9m 51s**, with all **69 actionable tasks
executed**. The full CLI suite was then rerun to include the last object-sharing regression:
**31s**, all **7 actionable tasks executed**. Counts below come from the resulting JUnit XML,
not retained results from another task.

| Module | Tests | Passed | Skipped | Failures/errors |
|---|---:|---:|---:|---:|
| smart-test-picker-common | 370 | 359 | 11 | 0 |
| smart-test-picker-core | 46 | 41 | 5 | 0 |
| smart-test-picker | 64 | 64 | 0 | 0 |
| smart-test-picker-maven | 169 | 169 | 0 | 0 |
| smart-test-picker-cli | 97 | 97 | 0 | 0 |
| stp-runtime | 84 | 84 | 0 | 0 |
| stp-junit-adapter | 21 | 21 | 0 | 0 |
| stp-agent | 52 | 51 | 1 | 0 |
| **Total** | **903** | **886** | **17** | **0** |

The base had 894 tests; nine were added (two delta-index tests, six streaming tests, one
cross-fragment sharing test). No tests were removed and the 17 existing skips remain.
