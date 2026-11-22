# TASK 36 — schema-v2 selector ingress and change analysis

TASK 36 introduces the shared, build-tool-neutral safety boundary for Task 5b. It does not implement
the selection union owned by TASK 37 and does not provision inventory through Gradle, Maven, CLI, or CI.

`SchemaV2SelectionAnalyzer` reads the candidate bytes once with `CoverageMapCodec`, then admits only
the resulting semantic `coverage.model.CoverageMap`. Exact schema v2, checksum integrity, a non-empty
revision, `PUBLISHED` lifecycle, affirmative `Completeness`, and a clean `CoverageMapValidator` result
are mandatory. Legacy schema-v1 maps are neither trusted nor converted. Every unsafe ingress result is
semantic RUN_ALL, exposed with the compatible public spelling `FULL_SUITE`.

Git analysis resolves one immutable head commit `H`. The map's `CoverageMapRevision.value` is the only
normal base `R`; `R` must resolve to a commit and be an ancestor of `H`. Staleness is the commit count
for `R..H`. Changed paths are the union of committed `R..H`, staged changes against `H`, unstaged changes,
and untracked files. Configured full-suite triggers see that complete union. Git failure, malformed
output, an invalid trigger, unsafe source identity, or a changed HEAD fails open.

Production selection input is deliberately class-level. Recognized Java production source paths map
to exact source/binary top-level class names; modifications, additions, and deletions retain that class,
while a proven rename contributes both old and new identities. Cross-boundary or malformed production
moves are unsafe. The new path does not call method-hunk parsing and does not mutate `.gitattributes`.

`HeadTestInventory` is the only new inventory boundary. Its exact schema-v2 `TestIdentity` values are
compared with mapped plus unmapped map identities to produce new and deleted tests. A changed test source
container conservatively marks every matching head identity as changed, retaining overloads, nested binary
names, and declared parameter signatures. Missing or inconsistent inventory fails open. Obtaining this
inventory remains adapter/orchestration work; no shards, storage, Jenkins, publication, or scheduling are
introduced here.

Successful analysis returns `SelectionContext`: the single decoded map, `R`, fixed `H`, changed paths and
classes, exact head inventory, and new/deleted/changed test classifications. TASK 37 will consume it to
apply direct coverage, setup-scope, and always-select-unmapped policy and to normalize `SELECTED`/`NONE`.

Task 5b is **IN PROGRESS** after this boundary; 5c remains **DONE**, and 5d remains
**NOT STARTED — POC proven**.
