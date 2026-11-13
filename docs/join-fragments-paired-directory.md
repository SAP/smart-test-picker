# Paired shard-directory join

STP-JOIN-CLI-02, branch `feature/paired-shard-join-cli`, based on main `8bd4a69` after PR #35 merged.
The existing separate-input mode remains supported. No agent, upload integration, joiner or
publication behavior is changed.

## Usage

```bash
smart-test-picker join-fragments \
  --dir run/shards \
  --expected-shards 36 \
  --output published/map.json.gz
```

Use the packaged CLI equivalently as
`java -jar smart-test-picker-cli-0.3.0-SNAPSHOT-all.jar join-fragments ...`.

The directory contains exactly paired direct files:

```text
shards/
  fragment-first.json
  tests-first.json
  fragment-second.json
  tests-second.json
```

`fragment-<name>.json` is a schema-v3 fragment. `tests-<name>.json` is an
`ExecutableShardAssignment` v1 document with `version`, `revision`, `shardId` and executable `tests`.
The nonempty name, including any hyphens, binds the pair; the CLI never substitutes matching shard IDs
for missing filename partners. A filename name need not equal the internal shard ID.

`--dir` requires a positive integer `--expected-shards`. Every pair counts once, and its count must
equal that number. Missing partners name the present and expected files; count failures report found
and expected numbers. Direct files whose names start with `.` (such as `.DS_Store`)
are skipped, with one diagnostic line per file on stderr; they do not affect the
pair count or JSON summary. Any other non-matching file is an error, as are subdirectories.
Malformed JSON is rejected through the existing production codecs and names its file.

Within each pair, revision and shard ID must match. Across pairs, all revisions and execution targets
must agree. Targets are checked on assigned, mapped and unmapped identities and setup-scope owners.
Duplicate shard IDs under different filenames and duplicate ownership of an identity fail. The head
inventory is the union of the lists, and the lists themselves are the plan assignments; the existing
joiner checks their exact partition. No separate head inventory or assignment directory is required.

The union must contain an execution target: an entirely empty set of test lists is rejected, just as
the separate-input mode rejects an empty authoritative inventory. An empty shard can participate
alongside other nonempty shards. The count is not an independent inventory oracle: a test omitted
from both the lists and fragments is not discoverable by this command, nor does a count alone prove
the intended shard names. Test-list production remains the caller's responsibility.

`--dir` cannot be mixed with any of `--fragments-dir`, `--assignments-dir` or `--head-inventory`.
Conversely, `--expected-shards` without `--dir` is rejected. The existing three input options plus
`--output` continue to work, with their existing strict missing-identity behavior.

## Assigned but not reported

Only in paired mode, the CLI compares each list with the mapped and unmapped identities in its
existing fragment. Every missing identity receives `UnmappedReason.ASSIGNED_NOT_REPORTED` in an
**in-memory copy** of that fragment. This reason distinguishes absence of a report from a failing
test (`FAILED`), observed non-execution (`SKIPPED`), or a reported collector failure
(`COLLECTION_FAILED`). The original files are never rewritten.

The map records each affected identity and reason; its JSON summary counts them under
`unmappedByReason.ASSIGNED_NOT_REPORTED`. Existing unmapped selection makes these tests mandatory.
Consumers of maps containing the new enum value must recognize it; no schema version or identity
encoding changes were made.

This adaptation does not manufacture a missing fragment or clear a fragment's failure state:

- Missing fragment: fail, naming its list and missing partner.
- Existing fragment with `collectionCompleted=false`: still rejected by the unchanged joiner.
- Unexpected identity, including an unexpected `SKIPPED` entry: fail, naming the pair.
- Observed `SKIPPED`: retain positive non-execution handling from PR #35, not an absorbed unmapped entry.
- Other observed outcomes and all setup scopes: retained unchanged.

The unchanged writer validates the staged on-disk file through the production codec, full map
equality and `ExplicitPrSelectorFlow` loading before atomic rename. No non-atomic fallback was added;
failures preserve an existing output and remove staging files. Keep the output outside the shard
directory, since every direct file there must be a recognized input and inputs cannot be overwritten.

## Permanent regressions

`PairedShardJoinCommandTest` adds 24 tests in the CLI module's production test source set. All 24
passed alongside all 35 existing `JoinFragmentsCommandTest` cases (59 focused tests total).

Coverage includes:

- Three valid pairs through the registered root command, published lifecycle and production-codec validation.
- Either missing partner, previous-output preservation and no newly installed map on failure.
- Too few and too many pairs; stray file; empty name; filename pairing rather than shard-ID fallback.
- In-pair shard/revision mismatch, cross-pair revision mismatch, target mismatch, duplicate shard IDs
  and duplicate test ownership.
- Unexpected mapped and unmapped identities, including skipped identities outside their assignment.
- Accepted positive `SKIPPED` evidence without absorption; rejection of an incomplete fragment even
  when its missing identities would otherwise be absorbed.
- Malformed files, mutually exclusive modes, required positive pair count, and corrupt staged output
  preserving a previous map and cleaning its staging file.

The mandatory-selection regression uses a real temporary Git repository, writes and re-reads the
published map, and calls **`ExplicitPrSelectorFlow.select`**, not just its loader. With an empty
source-code diff and a head inventory of three tests, exactly the assigned-but-unreported test is
selected, with `ASSIGNED_NOT_REPORTED` in the selection output. The two healthy tests are not selected.
The original fragment's bytes are also asserted unchanged.

The first focused attempt had one test-expectation failure: after renaming a list, two filename
partners were missing, and deterministic lexical order reported `tests-other.json` first rather than
the test's expected original fragment. The test now checks the actual first unmatched pair. No
pairing behavior was changed to satisfy it. Both initial and successful logs are retained locally
under `verification/stp-join-cli-02/raw/`.

Focused command:

```bash
./gradlew :smart-test-picker-cli:test --tests '*Join*CommandTest' \
  --tests '*JoinFragmentsCommandTest' :smart-test-picker-cli:shadowJar --offline
```

## Existing-option reference gate

A fresh packaged-CLI join of the retained reference Maven fragment at
`88e37c15cf6fc8490b01bc3e8e2c800cec1ac272` used the existing options:

```bash
java -jar smart-test-picker-cli/build/libs/smart-test-picker-cli-0.3.0-SNAPSHOT-all.jar \
  join-fragments \
  --fragments-dir verification/stp-join-cli-01/inputs/fragments \
  --assignments-dir verification/stp-join-cli-01/inputs/assignments \
  --head-inventory verification/stp-join-cli-01/inputs/head-inventory.json \
  --output verification/stp-join-cli-02/raw/map.json
```

Result: exit zero, **69 mapped, 0 unmapped, 10 setup scopes**, 21 distinct classes, 89 distinct methods,
186,853 bytes. Decoded JSON compared against the STP-JOIN-CLI-01a map differs only in `generatedAt`
and its dependent checksum. Coverage, unmapped entries, setup scopes, completeness, statistics,
lifecycle and provenance are identical. This is a fresh join of retained collection evidence, not
a new collection or performance run.

- Unchanged input fragment SHA-256:
  `0ecdbed9d086835971a9c69e3a49b6977ca24c42058ce2bf3618d8fc1820b8b9`.
- New embedded map checksum:
  `sha256:9250cf96cb124cd00ff21c8f5cbfd6e1eb19148e531d957d6a845db200fd55e7`.

As an additional packaged-CLI smoke check, the exact retained fragment and assignment bytes were
copied to `verification/stp-join-cli-02/paired-input/fragment-local.json` and `tests-local.json`.
No inventory was regenerated and no new test-list discovery was performed.

```bash
java -jar smart-test-picker-cli/build/libs/smart-test-picker-cli-0.3.0-SNAPSHOT-all.jar \
  join-fragments --dir verification/stp-join-cli-02/paired-input --expected-shards 1 \
  --output verification/stp-join-cli-02/raw/paired-map.json
```

This also produced **69 mapped, 0 unmapped, 10 setup scopes**, and its decoded map is identical to
the existing-option output except for `generatedAt` and checksum. Paired-mode checksum:
`sha256:b19276a21b8916c4bc39918653199a7e238130635aefcc3633e2fe236187fcba`.
This verifies both CLI entry paths on retained evidence; it is not an external platform run.

## Eight-module gate

Fresh command: `./gradlew test --rerun-tasks --offline --continue`.
**BUILD SUCCESSFUL in 9m 57s**, all 69 actionable tasks executed. Fresh JUnit XML counts:

| Module | Tests | Passed | Skipped | Failures/errors |
|---|---:|---:|---:|---:|
| smart-test-picker-common | 342 | 331 | 11 | 0 |
| smart-test-picker-core | 29 | 24 | 5 | 0 |
| smart-test-picker | 58 | 58 | 0 | 0 |
| smart-test-picker-maven | 161 | 161 | 0 | 0 |
| smart-test-picker-cli | 95 | 95 | 0 | 0 |
| stp-runtime | 83 | 83 | 0 | 0 |
| stp-junit-adapter | 21 | 21 | 0 | 0 |
| stp-agent | 52 | 51 | 1 | 0 |
| **Total** | **841** | **824** | **17** | **0** |

This is 24 new tests above the merged main base's 817. The runtime count is 83 because that base
already includes the earlier suspension API change; no runtime code changed here. Console evidence
is retained locally at `verification/stp-join-cli-02/raw/full-suites.log`. `git diff --check` and the
tracked-file 5 MiB check passed. The joiner, publication, Maven and agent sources are unchanged.

No CI or external platform run is part of this task. The new PR is to be opened, not merged.
