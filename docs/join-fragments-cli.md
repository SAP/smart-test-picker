# Verified executable fragment join CLI

STP-JOIN-CLI-01 adds `join-fragments` on branch `feature/join-executable-fragments-cli`,
based on main at `05d5c3b`. It reuses the existing strict join and publication contracts.
It does not implement degraded joining, assignment generation or selection policy changes.
Output now uses the [compact streaming gzip v3 map format](v3-map-format.md); input fragment and
assignment formats are unchanged. The validation results below describe their historical revisions.

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
  --output published/map.json.gz
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
   explicit selection. Loading now streams the staged gzip map; selector policy is unchanged.
   No fabricated repository or revision preflight is needed.

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

Historical run results are retained in [the history archive](history/join-fragments-cli.md).
