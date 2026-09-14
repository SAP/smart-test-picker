# EI-13 acceptance report and runbook

## Result

EI-13 passes against the complete Apache Commons Statistics 1.3 functional reactor. This demonstrates the tested generic Maven behavior only; it does not establish compatibility with untested SAP Maven configurations.

R0 is `2937eb2e711483d8ea9dc216af45c16fd0066b77`; R1 is `04e9e5d9d66da5dcbaa9a635674926ae71b4ea77`. The accepted command is `mvn --show-version --batch-mode --no-transfer-progress -Pexamples clean verify`. Docker used Maven 3.9.16 and Eclipse Adoptium Java 21.0.12.1. The controller had zero executors. Workers `worker-0`, `worker-1`, and `worker-2` mapped and selected; container `join` joined and published.

## Baseline and identity model

`EI-13-R0-Baseline #1` passed from a clean workspace and isolated Maven repository in 173.813 seconds: 55,879 Surefire invocations, 48 skipped, zero failed. The authoritative inventory contains 1,993 declared executable identities. Mapping #11 assigned 676/678/639 identities; evidence records 1,908 executed/mapped and 85 explicit `NON_EXECUTED` identities. Thus no skipped or disabled identity is presented as mapped coverage.

Inventory and maps retain `maven:<module>@surefire@default-test@examples::class#declaredSignature`. Surefire XML contains dynamic display names, parameter values, nested tests, and repetitions. For executable plans, Surefire can enforce class plus Java method name but not the declared parameter list. The independent and production plan boundary therefore collapse parameter lists and overloads to `target::class#baseMethod`; XML invocations are normalized identically. Raw parameterized/repeated invocations remain counted separately. Job B #13 reconciles 123 selected declared signatures to 122 enforceable identities and 572 raw invocations: expected = plan = assignments = executed, with no duplicate, missing, unexpected, skipped, or failed execution.

## Controlled R1

Seed `13032026` was applied to production Java files in distinct test-bearing library modules, excluding generated/package/module descriptors and requiring an executable non-constructor method. The committed harmless marker expressions are:

| module | class and method | marker | R1 line | R1 coverage |
|---|---|---|---:|---:|
| interval | `ArgumentUtils.checkErrorRate(double)` | `EI-13 change 1` | `commons-statistics-interval/src/main/java/org/apache/commons/statistics/interval/ArgumentUtils.java` | 6 declared tests |
| inference | `Arguments.checkStrictlyPositive(int)` | `EI-13 change 2` | `commons-statistics-inference/src/main/java/org/apache/commons/statistics/inference/Arguments.java` | 103 declared tests |
| ranking | `NaturalRanking.IntList.shuffle(IntUnaryOperator)` | `EI-13 change 3` | `commons-statistics-ranking/src/main/java/org/apache/commons/statistics/ranking/NaturalRanking.java` | 14 declared tests |

The R0 map supplied the same 123 direct class-coverage relationships used to freeze expected selection before Job B. R1 full coverage confirms all 123 at R1, and Job B includes all of them (122 executable identities after the one overload collapse). No additional selection cause was present. Coverage collection is module-owned and exposed no cross-module test-to-production edge for these changes, so cross-module coverage is **not present**, not manufactured. Reactor prerequisite modules built while the adapter suppressed unassigned tests.

## Final Jenkins evidence

| job | build | result | duration | decisive evidence |
|---|---:|---|---:|---|
| `EI-13-R0-Baseline` | 1 | SUCCESS | 173.813 s | full uninstrumented R0 |
| `EI-13-Job-A-R0-Mapping` | 11 | SUCCESS | 84.646 s | 3 shards, fourth-agent join, durable RAW_HTTP publish |
| `EI-13-Job-B-R1-Selection` | 13 | SUCCESS | 53.783 s | 42/40/40 plans; 122/122/122 equality |
| `EI-13-Job-C-R1-Control` | 7 | SUCCESS | 159.431 s | full uninstrumented R1 |
| `EI-13-R1-Full-Coverage` | 4 | SUCCESS | 86.429 s | full R1 production collection |
| `EI-13-Runtime-Negatives` | 4 | SUCCESS | 42.495 s | empty, incompatible map, wrong revision/shard, malformed/missing assignment |

Mapping workers ran concurrently from 09:58:14Z through at least 09:59:07Z (53 seconds of common overlap). Selected workers ran concurrently from 10:01:13Z through at least 10:01:41Z. Separate workspaces and `stash`/`unstash` transported assignments/fragments; no shared workspace transported them.

The immutable R0 map is in Nexus RAW storage under project `apache-commons-statistics`, branch `ei13-r0-surefire-final`, revision R0; local evidence checksum is `d5f276821b751b41cad421bd8a676119365358a3fd6c3f0d97a8fa631e32ec14`. R1 full-map checksum is `ed078f7b649dc18f8d6ccfefca9c7367f12358271d9c74ac2b7b14f13813272b`. The expected canonical set checksum is `3abeff42f3365497416de1e0cc08f99de76df7597fe8acf1a93f44645bfb6374` (JSON artifact checksum `a3536f839c0aa6e0bd1651d16fa987481d8e45dacd73713bbcf1cabe5cef5078`).

## Negative behavior and fixes

Runtime #4 proves a valid empty Maven plan produces zero XML reports/tests, incompatible revision lookup fails closed with `STORAGE_INTEGRITY_VIOLATION`, and wrong revision, wrong shard, malformed, and missing assignments reject before the body. Production join regression tests cover unknown target, duplicate fragment, missing expected fragment, and incomplete publication; Job A only publishes after all three fragment/evidence pairs validate. An intentional repeat publication (#10) was rejected with `IMMUTABILITY_CONFLICT`, proving immutable storage rather than overwriting the prior map.

Defects found and fixed generically:

1. Empty Maven fragments were rejected: accept validated empty target fragments.
2. Reactor prerequisites ran unassigned tests: target/module ownership and empty-module suppression were added.
3. Explicit Surefire includes were additive in this project: empty assignments now add an exclude-all file.
4. Merged evidence lost target identity: evidence is bound to its Maven target.
5. Jenkins automatic selection lacked Maven inventory/distribution: production Maven inventory and three-agent plan sharding were added.
6. Descriptor-qualified methods were silently ignored by Surefire: plans now state enforceable method-name granularity, overloads deduplicate, and the adapter normalizes defensively.

Regression gates passed: STP common and Maven Gradle tests; Jenkins plugin 95/95 tests; focused sharder/packaging 5/5; Docker runtime acceptance above. No tests/assertions in Commons Statistics were changed or disabled.

## Reproduction

Use the checked-in Docker/Jenkins files in the isolated plugin worktree, build STP Java 21 artifacts, build the HPI with `mvn -s .mvn/settings.xml test hpi:hpi`, deploy it to the controller, verify the loaded plugin, configure RAW_HTTP credentials outside source control, then install the four Jenkinsfiles plus the baseline job. Run baseline, Job A, freeze `independent_expected_selection.py`, run Job B with its `SCMRevisionAction`, run Job C and full R1 coverage, then negatives. Run `reconcile_selected_execution.py` against Job B's archived `build/stp` directory. Exact raw consoles and archived artifacts are under `evidence/`.

No credentials, Jenkins home, Maven repository, or disposable container state are committed.
