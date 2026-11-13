# Verified executable fragment join CLI

STP-JOIN-CLI-01 adds `join-fragments` on branch `feature/join-executable-fragments-cli`,
based on main at `05d5c3b`. It reuses the existing strict join and publication contracts.
It does not implement degraded joining, assignment generation, compression or selection policy changes.

STP-JOIN-CLI-02 adds an alternative [paired-directory input mode](join-fragments-paired-directory.md).
The separate-input contract and its historical validation results below remain unchanged; the
paired mode additionally accounts assigned-but-unreported tests as unmapped without changing the joiner.
Only paired `--dir` mode skips dot-prefixed files, logging each to stderr. The
separate-input options retain their existing strict file handling.

## Usage and input contract

```bash
java -jar smart-test-picker-cli/build/libs/smart-test-picker-cli-0.3.0-SNAPSHOT-all.jar \
  join-fragments \
  --fragments-dir run/fragments \
  --assignments-dir run/assignments \
  --head-inventory run/head-inventory.json \
  --output published/map.json
```

All options are required. Each directory must contain only direct input files: every file is decoded,
regardless of its extension; subdirectories and special entries are rejected. Parse failures name the
file. An output path that aliases any input is rejected. Keep the output outside the input directories.

The head inventory uses `ExecutableHeadTestInventoryCodec`; assignments and fragments use their
production executable codecs. Assignment files supply the shard plan directly, without redistributing
tests. All revisions must equal the head revision. All identity targets and setup-scope owners must
agree, with conflicting source files named on failure. Any single execution target is supported,
including `ant:sample` and `maven:.`; shard IDs are separate from that target.

The inventory must be nonempty because this command has no target option and an empty inventory
encodes no target. A matching empty shard assignment and empty fragment are permitted alongside a
nonempty inventory. Neither has an identity carrying a target; it is bound through the common revision
and shard plan, not through invented target metadata.

`ExecutableCoverageFragmentJoiner.join` and
`ExecutableCoverageMapPublication.alignExpectedInventory` are unchanged. Missing, unexpected or
duplicate shards, incomplete fragments, duplicate ownership and a nonpartitioning assignment still
fail. `FAILED` and `COLLECTION_FAILED` unmapped entries remain valid accounted identities.
`intentionallyNonExecutable` is the union of identities explicitly reported as `SKIPPED` in fragment
unmapped entries. These are positive runtime observations, not inference from absence. Each must
belong to the assignment of the reporting fragment's shard; otherwise the CLI fails and names that
fragment file. An unknown shard cannot supply positive non-execution evidence.

The same set is passed to both the unchanged joiner and publication alignment. A `SKIPPED` identity
reported again as mapped or unmapped by another shard still fails: with an exact shard partition,
that other report violates its assignment, and duplicate assignment ownership is also rejected.
Tests absent from every report are never added to the non-executable set and still fail as missing.

In the existing schema, positive non-execution is accounted in `completeness.reportedTests` without
appearing in either the map's mapped coverage or its unmapped entries. There is no separately
serialized `intentionallyNonExecutable` field. This change does not alter the schema or joiner.

## Publication and read-back verification

After joining and aligning the expected inventory, the CLI sets the map lifecycle to `PUBLISHED`.
It writes a temporary file in the destination directory, reads that file back from disk and requires:

1. Successful production-codec deserialization, including checksum and schema checks.
2. Successful published-map validation and full record equality with the map to be written. This checks
   identities, per-test class/method sets, unmapped reasons, setup-scope contents and all metadata, not
   merely counts.
3. Successful loading through `ExplicitPrSelectorFlow.selectionMap`, the same loader used by actual
   explicit selection. Only this existing method's visibility and javadoc changed; its body and the
   selector's policy are unchanged. No fabricated repository or revision preflight is needed.

Only then does an atomic replacement rename install the verified file. There is no non-atomic
fallback. A failed run removes the staging file and leaves an existing destination unchanged. If no
destination existed, none is installed. A corrupt staged file therefore cannot replace the previous
map. This ordering deliberately validates the on-disk staging file **before** the rename, rather than
destroying the previous map and discovering corruption afterwards. Unsupported atomic replacement
on a filesystem is an error, not permission to weaken that guarantee.

Success exits zero and prints a JSON summary. Processing failures exit nonzero with the underlying
joiner or validation message; input errors also name their source. A package-private staging hook
allows tests to corrupt disk contents before verification; it is not exposed as a CLI option.

## Provenance and summary fields

`GeneratorProvenance` records:

| Field | Value |
|---|---|
| `stpVersion` | Existing `StpVersionProvider` result; packaged CLI here: `0.3.0-SNAPSHOT` |
| `coverageRuntimeVersion` | `null`: the input fragments do not identify their runtime version |
| `jacocoVersion` | `null`: the command cannot infer a collector or its version |
| `jdkVersion` | `java.runtime.version` of the joining JVM |

The existing version provider's source-tree fallback remains `development`. No collector version is
guessed. `generatedAt` is the join's current UTC instant, so separate invocations need not have equal
map checksums even with identical coverage inputs.

The summary includes execution target, revision, completed shard count, mapped/unmapped test counts,
unmapped counts by reason, setup-scope count, distinct classes and methods, serialized byte size and
checksum. Distinct classes include setup scopes; distinct methods are descriptor-qualified leaf
coverage methods, since the setup-scope model contains classes rather than method sets. The printed
`sha256:` checksum is the codec's embedded canonical-payload checksum, not a hash of the entire file
including its checksum field.

## Permanent regressions

`JoinFragmentsCommandTest`, in the CLI module's production test source set, contains 35 cases:

- Three complete shards, exact summary and full read-back equality; root-command registration and a
  Maven target.
- Missing and duplicate fragments; unexpected shards; duplicate assignment files; duplicate ownership
  and a nonpartitioning plan; incomplete collection; no inferred non-execution evidence.
- Observed `SKIPPED` identities are accepted and accounted, including their union across fragments.
  Wrong-shard and unknown-shard `SKIPPED` entries fail with the source filename. A missing identity
  remains missing even alongside valid skipped evidence. Cross-shard skipped/mapped and
  skipped/unmapped overlaps still fail through the unchanged assignment validation.
- Target conflicts in fragments, assignments, inventory, unmapped identities and scope ownership;
  fragment/inventory and assignment/inventory revision conflicts.
- Malformed fragment, assignment and inventory files; directory entries; empty inventory; permitted
  matching empty shards; input/output alias protection.
- Post-write byte corruption with and without a previous map, same-count changed coverage and changed
  scopes with recomputed valid checksums, candidate lifecycle, unsupported schema and failed atomic
  replacement. Failed publication leaves prior output intact and cleans staging files.
- Accepted `FAILED`/`COLLECTION_FAILED` unmapped entries and successful production selector loading.

The original focused suite passed all 29 cases. STP-JOIN-CLI-01a replaces the former skipped-entry
rejection test with an acceptance regression and adds six cases; fresh amendment results follow below.

## Reference Maven join gate

This is a **fresh CLI join of retained collection evidence**, not a new collection run. The subject
revision is `88e37c15cf6fc8490b01bc3e8e2c800cec1ac272`, using the same 69-test H2 scope.
The retained input fragment has SHA-256:

```text
0ecdbed9d086835971a9c69e3a49b6977ca24c42058ce2bf3618d8fc1820b8b9
```

The new command exited zero, accepted the map through the production codec and selector loader, and
reported:

| Result | Measured value |
|---|---|
| Execution target / shard | `maven:.` / `local` |
| Mapped / unmapped tests | 69 / 0 |
| Setup scopes | 10 |
| Distinct classes / methods | 21 / 89 |
| Map size | 186,853 bytes |
| Embedded map checksum | `sha256:9923e5cfaad20397699939accd534519d8f42a782e963773efa7ee4d8c38b0c1` |
| CLI execution wall time | 0.3153175 s |
| Whole JVM process wall time | 0.45 s |
| Sampled peak used heap (1 ms sampling) | 17,549,840 bytes (16.74 MiB) |
| Sum of individual heap-pool peaks | 22,758,096 bytes (21.70 MiB) |

A disposable local wrapper invoked the real picocli command in a fresh JVM and sampled
`MemoryMXBean`; it reset and read each heap pool's peak counters around the command. The sampled peak
can miss sub-millisecond spikes. The sum of pool peaks is a conservative upper bound, **not** a
simultaneous peak and not RSS. CLI time excludes JVM startup; external `/usr/bin/time -p` measured the
whole process. This is one small local run, not a scalability claim. The full-suite rerun was active
on the same machine, so the timing is not an isolated benchmark.

The measurement used SapMachine `21.0.13.0.3+1-LTS`, G1, `-Xms32m -Xmx256m`, and the packaged
`0.3.0-SNAPSHOT` CLI. Local raw evidence is under `verification/stp-join-cli-01/`: input files,
`raw/map.json`, `raw/summary.json`, `raw/measurement.json`, `raw/process-time.txt` and the disposable
`JoinMeasurement.java`. Those measurement files remain local; they are not shipped as production
resources.

```bash
./gradlew :smart-test-picker-cli:test --tests '*JoinFragmentsCommandTest' \
  :smart-test-picker-cli:shadowJar --offline
javac -cp smart-test-picker-cli/build/libs/smart-test-picker-cli-0.3.0-SNAPSHOT-all.jar \
  -d verification/stp-join-cli-01/classes verification/stp-join-cli-01/JoinMeasurement.java
/usr/bin/time -p -o verification/stp-join-cli-01/raw/process-time.txt \
  java -Xms32m -Xmx256m \
  -cp verification/stp-join-cli-01/classes:smart-test-picker-cli/build/libs/smart-test-picker-cli-0.3.0-SNAPSHOT-all.jar \
  JoinMeasurement verification/stp-join-cli-01/raw/measurement.json \
  join-fragments \
  --fragments-dir verification/stp-join-cli-01/inputs/fragments \
  --assignments-dir verification/stp-join-cli-01/inputs/assignments \
  --head-inventory verification/stp-join-cli-01/inputs/head-inventory.json \
  --output verification/stp-join-cli-01/raw/map.json
```

## Original STP-JOIN-CLI-01 eight-module gate

Fresh command: `./gradlew test --rerun-tasks --offline --continue`.
Result: **BUILD SUCCESSFUL in 9m 3s**, 69 actionable tasks, all 69 executed.
Counts below were read from the eight modules' fresh JUnit XML reports, not from retained results.

| Module | Tests | Passed | Skipped | Failures/errors |
|---|---:|---:|---:|---:|
| smart-test-picker-common | 342 | 331 | 11 | 0 |
| smart-test-picker-core | 29 | 24 | 5 | 0 |
| smart-test-picker | 58 | 58 | 0 | 0 |
| smart-test-picker-maven | 161 | 161 | 0 | 0 |
| smart-test-picker-cli | 65 | 65 | 0 | 0 |
| stp-runtime | 67 | 67 | 0 | 0 |
| stp-junit-adapter | 21 | 21 | 0 | 0 |
| stp-agent | 52 | 51 | 1 | 0 |
| **Total** | **795** | **778** | **17** | **0** |

The CLI module adds 29 tests to the requested base's 766-test suite. Main subsequently merged a
separate runtime suspension change; these counts describe this branch based on `05d5c3b`, not a
merge with that later change. Local console output is retained at
`verification/stp-join-cli-01/raw/full-suites.log`. The tracked-file 5 MiB check and `git diff --check`
also passed.

No CI test workflow or external platform run is part of this validation.

## STP-JOIN-CLI-01a verification

This amendment treats observed `SKIPPED` entries as positive non-execution. The CLI constructs the
union while reading fragments, verifies each entry against that fragment's shard assignment (with
the source filename in rejection messages), and passes the same union to join and publication.
No absent identity is added. Joiner, publication, codecs and Maven code are unchanged.

The replaced regression is `skippedEntrySuppliesPositiveNonExecution`. It reads the published map,
asserts that the skipped test has neither mapped nor unmapped coverage, and verifies that completeness
accounts for it with no missing test. The existing schema represents that state in `reportedTests`,
not in a new non-execution field. Six additional permanent CLI tests prove:

- Positive evidence is the union across reporting fragments.
- A skipped identity assigned to another shard fails and names the source file.
- A skipped identity reported without a shard assignment fails and names the source file.
- A genuinely absent identity still fails, even when another identity has valid skipped evidence.
- A skipped identity also mapped by another shard fails the existing assignment validation.
- A skipped identity also unmapped by another shard fails the existing assignment validation.

All 35 focused CLI tests passed. The first focused attempt had one diagnostic assertion failure:
the message printed `ShardId[value=2]` rather than the shard's value `2`. The message was corrected
to use `value()`; the unchanged assertion and the full focused set then passed. Both logs are retained
locally as `verification/stp-join-cli-01a/raw/focused-initial.log` and `raw/focused.log`.

The fresh packaged-CLI reference join used the original retained inputs and these arguments:

```bash
java -jar smart-test-picker-cli/build/libs/smart-test-picker-cli-0.3.0-SNAPSHOT-all.jar \
  join-fragments \
  --fragments-dir verification/stp-join-cli-01/inputs/fragments \
  --assignments-dir verification/stp-join-cli-01/inputs/assignments \
  --head-inventory verification/stp-join-cli-01/inputs/head-inventory.json \
  --output verification/stp-join-cli-01a/raw/map.json
```

It exited zero with **69 mapped, 0 unmapped, 10 setup scopes**, 21 distinct classes, 89 distinct methods
and 186,853 bytes. A full decoded-JSON comparison against the original task's map found exactly two
changed fields: `generatedAt` and its dependent `checksum`. All coverage, unmapped entries, setup
scopes, completeness, statistics, lifecycle and provenance were identical. This is a fresh join of
retained evidence, not a new collection run or a new performance measurement.

New embedded map checksum:
`sha256:6bcb5e682d74a8b3c8b8c87e52cf1ad8b06a1297b9c2488846be2e57aee4c2ec`.
Input fragment SHA-256 remains
`0ecdbed9d086835971a9c69e3a49b6977ca24c42058ce2bf3618d8fc1820b8b9`.

Fresh amendment command: `./gradlew test --rerun-tasks --offline --continue`.
**BUILD SUCCESSFUL in 10m 23s**, all 69 actionable tasks executed. Counts read from the fresh JUnit XML:

| Module | Tests | Passed | Skipped | Failures/errors |
|---|---:|---:|---:|---:|
| smart-test-picker-common | 342 | 331 | 11 | 0 |
| smart-test-picker-core | 29 | 24 | 5 | 0 |
| smart-test-picker | 58 | 58 | 0 | 0 |
| smart-test-picker-maven | 161 | 161 | 0 | 0 |
| smart-test-picker-cli | 71 | 71 | 0 | 0 |
| stp-runtime | 67 | 67 | 0 | 0 |
| stp-junit-adapter | 21 | 21 | 0 | 0 |
| stp-agent | 52 | 51 | 1 | 0 |
| **Total** | **801** | **784** | **17** | **0** |

The new total adds six tests to the original task's 795; none were removed. Console evidence is
retained locally at `verification/stp-join-cli-01a/raw/full-suites.log`. `git diff --check` and the
tracked-file 5 MiB check also passed. No CI or external platform run was performed. This amendment
is a new commit above `3714702`, not a history rewrite.
