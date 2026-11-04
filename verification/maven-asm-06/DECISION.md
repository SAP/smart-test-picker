# Maven ASM 06 scope and evidence decision

Recorded before implementation on 2026-11-04. STP starting revision:
`4cd9b00b0374a84585fd5d7bdfb6900a69100dd4`, branch `research/asm-codex`.
The tracked worktree is clean; the pre-existing untracked examples and spikes
remain outside this task.

## Step 0 Production aggregation route

Inspected Jenkins plugin checkout `stp-jenkins-plugin-poc`, branch `main`, revision
`8a0b793c3229eeb862f4a006788cf7fb76ef0b1d`, clean worktree. Search performed:

```sh
rg -n 'aggregate-reactor-coverage-fragment|merge-executable-target-fragments' \
  /Users/D061177/work/moje/stp-jenkins-plugin-poc
rg -n 'STP_MAVEN_MERGE_GOAL|executionType|executionId|moduleFragmentOutput' \
  /Users/D061177/work/moje/stp-jenkins-plugin-poc/src/main \
  /Users/D061177/work/moje/stp-jenkins-plugin-poc/examples/canonical
```

`MavenExecutableMappingContract.MERGE_GOAL` names
`aggregate-reactor-coverage-fragment`. `StpCoverageMapStep` exports it as
`STP_MAVEN_MERGE_GOAL`, together with the final fragment/evidence paths and shard
assignment. `examples/canonical/Jenkinsfile.maven-mapping` invokes that goal after
`verify`. There is no plugin invocation of `merge-executable-target-fragments`,
nor enumeration of qualified per-target files. The Pipeline body runs Maven;
the plugin supplies the production command contract rather than launching Maven
implicitly.

Therefore the aggregator's unqualified-only module/path resolution is a gap in
the shipped aggregation contract for qualified inputs, not a test that used the
wrong merge goal. This task will resolve each assigned target against the active
reactor and use the same filename suffix as preparation. The plugin itself and
its enablement remain unchanged. Current canonical examples exercise unqualified
targets; this inspection does not claim a qualified Jenkins runtime validation.

## Step 1 Evidence model decision

Choose **C**, with a Maven-local evidence writer: retain the immutable ASM fragment,
but exclude `unmapped/SKIPPED` from executed coverage ownership. Generate evidence
v2 from positive observations in that fragment, never from assignment subtraction.

| Positive fragment observation | Evidence v2 |
| --- | --- |
| Mapped identity | EXECUTED |
| Unmapped FAILED | EXECUTED, with no partial coverage |
| Unmapped SKIPPED, including aborted-only | NON_EXECUTED |
| Incomplete collection or unsafe input | Reject; no accepted evidence |

The shared `ExecutableFragmentAssignmentValidator` already excludes SKIPPED from
reported owners when positive non-execution is supplied. The Maven aggregator's
additional owner comparison currently contradicts that rule. Align only Maven
ownership accounting, including its explicit target-merge entry point, with this
existing shared contract. Require all SKIPPED identities to have NON_EXECUTED
evidence; require disjoint evidence sets whose union exactly equals the assignment.
Keep exact target/revision/shard checks, duplicates and completeness rejection.

The JaCoCo claim is confirmed in source: `JacocoPerTestListener` writes
`.non-executed` for aborted results and does not write their `.identity` artifacts;
`GenerateCoverageFragmentMojo.reconcileSkippedReports` writes the same sidecars
for skipped XML entries. Its `collect` method creates FAILED or COLLECTION_FAILED
unmapped entries, never SKIPPED. Thus excluding only SKIPPED does not change its
coverage ownership. Existing JaCoCo regressions and new outcome assertions will
verify that behavior.

Reject A: changing the shared projector would alter Gradle's existing evidence
and join semantics unnecessarily. Reject B alone: deriving NON_EXECUTED while
leaving the existing aggregator owner comparison intact still rejects immutable
SKIPPED entries. Rewriting the fragment to remove them is not acceptable.
The shared projector, listeners, schemas and Gradle implementation stay untouched.

## Artifact contract selected before implementation

Under each module's configured build directory, in `stp/`:

| Artifact | Filename | Writer |
| --- | --- | --- |
| Immutable schema-v3 fragment | `coverage-fragment-v3<suffix>.json` | ASM agent |
| Agent diagnostic | `asm-agent-diagnostic<suffix>.json` | ASM agent |
| Execution evidence v2 | `execution-evidence-v2<suffix>.json` | Maven fragment goal |

The suffix remains empty for unqualified targets and uses preparation's existing
target-based suffix for qualified targets. `moduleFragmentOutput` and
`moduleEvidenceOutput` retain their meanings; `moduleDiagnosticOutput` identifies
diagnostics explicitly. Preparation invalidates its assigned targets' prior
outputs before execution. Fragment finalization validates existing fragment bytes
through the production codec, binding and assignment checks, writes evidence,
and never deletes or rewrites the agent fragment. Unassigned modules do not invent
empty successful agent fragments. JaCoCo fragment generation follows its existing
path. ASM does not scan JaCoCo execution data or XML reports.

Failed tests remain failures of Maven test execution. Tests may deliberately use
Maven's test-failure-ignore switch to reach aggregation and inspect FAILED evidence;
the product will not enable that switch. Separate finalization after a failed test
command can also inspect the collected artifacts without rerunning tests.

## Validation plan

Real fixture runs cover preparation, collection, fragment finalization and reactor
aggregation; pass/disabled/failure/aborted outcomes; two qualified providers for
the same logical identities; and cross-module coverage isolation. Negative tests
cover missing/corrupt/misbound/incomplete evidence, duplicate/unexpected identities
and EXECUTED/NON_EXECUTED overlap. All four requested suites will rerun, with logs
and exact identity assertions retained. Runtime results belong in REPORT.md after
execution, not in this pre-implementation decision record.
