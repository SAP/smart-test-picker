# Verified executable fragment join CLI

STP-JOIN-CLI-01 adds `join-fragments` on branch `feature/join-executable-fragments-cli`,
based on main at `05d5c3b`. It reuses the existing strict join and publication contracts.
It does not implement degraded joining, assignment generation, compression or selection policy changes.

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
fail. `FAILED` and `COLLECTION_FAILED` unmapped entries remain valid accounted identities. These
inputs contain no separate positive non-execution evidence, so `intentionallyNonExecutable` is empty.
The command never infers it by subtraction; a `SKIPPED` entry that requires such evidence still fails
the existing join contract.

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

`JoinFragmentsCommandTest`, in the CLI module's production test source set, contains 29 cases:

- Three complete shards, exact summary and full read-back equality; root-command registration and a
  Maven target.
- Missing and duplicate fragments; unexpected shards; duplicate assignment files; duplicate ownership
  and a nonpartitioning plan; incomplete collection; no inferred non-execution evidence.
- Target conflicts in fragments, assignments, inventory, unmapped identities and scope ownership;
  fragment/inventory and assignment/inventory revision conflicts.
- Malformed fragment, assignment and inventory files; directory entries; empty inventory; permitted
  matching empty shards; input/output alias protection.
- Post-write byte corruption with and without a previous map, same-count changed coverage and changed
  scopes with recomputed valid checksums, candidate lifecycle, unsupported schema and failed atomic
  replacement. Failed publication leaves prior output intact and cleans staging files.
- Accepted `FAILED`/`COLLECTION_FAILED` unmapped entries and successful production selector loading.

The focused suite passed all 29 cases. Fresh full-suite results are recorded below.

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

## Eight-module gate

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
