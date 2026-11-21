# Historical validation: join-fragments-cli.md

This is retained validation evidence, not a current setup instruction. See the
[current contract](../join-fragments-cli.md).

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
