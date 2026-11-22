# Paired shard-directory join

Paired mode derives the inventory and shard plan from the test lists alongside the fragments.
The separate-input mode remains available when an authoritative head inventory and assignments
are supplied independently.

## Usage

```bash
smart-test-picker join-fragments \
  --dir run/shards \
  --expected-shards 36 \
  --output published/map.json.gz
```

Use the packaged CLI equivalently as
`java -jar smart-test-picker-cli-0.3.0-SNAPSHOT.jar join-fragments ...` for the published artifact.
The source build's shadow JAR retains its `-all` classifier.

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
- Observed `SKIPPED`: retain positive non-execution handling, not an absorbed unmapped entry.
- Other observed outcomes and all setup scopes: retained unchanged.

The unchanged writer validates the staged on-disk file through the production codec, full map
equality and `ExplicitPrSelectorFlow` loading before atomic rename. No non-atomic fallback was added;
failures preserve an existing output and remove staging files. Keep the output outside the shard
directory, since every direct file there must be a recognized input and inputs cannot be overwritten.

## Permanent regressions

`PairedShardJoinCommandTest` and `JoinFragmentsCommandTest` cover the paired and separate modes
in the CLI module's test source set.

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

Focused command:

```bash
./gradlew :smart-test-picker-cli:test --tests '*Join*CommandTest' \
  --tests '*JoinFragmentsCommandTest' :smart-test-picker-cli:shadowJar --offline
```

Historical run results are retained in [the history archive](history/join-fragments-paired-directory.md).
