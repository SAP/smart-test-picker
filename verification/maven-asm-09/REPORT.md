# MAVEN-ASM-09 Jenkins Maven ASM integration

## Initial state and decisions

STP starts at `772d51d13cfe92f8126d8103a9af55da0081afdd` on
`research/asm-codex`. The Jenkins plugin starts at `8a0b793` on `main`,
with a clean worktree. STP's pre-existing untracked `examples/`,
`verification/maven-asm-shapes/`, and `verification/maven-asm-spike/` are
unrelated and are preserved. This task uses only the schema-v3 reactor fixture.

### Rejection and transport audit

| Site | Existing behavior | Decision |
| --- | --- | --- |
| `CoverageMappingManifest` schema-v2 constructor | Rejects Maven ASM | Retain: the prepared ASM contract is schema-v3 only |
| `CoverageMappingManifest` schema-v3 constructor | Allows only JACOCO for Maven | Allow ASM and JACOCO, retain invalid-collector rejection |
| `StpCoverageMapStep.start` | Rejects every Maven ASM invocation | Allow schema-v3 prepared mapping only |
| `StpCoverageMapStep.start` manifest consumption | Silently overwrites explicitly supplied collector | Reject an explicit mismatch; omission inherits the frozen manifest |
| `StpPrepareCoverageMappingStep` | Freezes collector in manifest | Reuse, no new parameter |
| `MappingEnvironment` Maven branch | Exposes collector as environment only | Pass frozen collector through the Maven property contract |
| `MavenExecutableMappingContract` | Invokes reactor aggregation, not target merge | Retain; ASM06 implemented qualified filename resolution in Maven |
| `StpCoverageMapStep.Callback` | Stashes aggregated fragment/evidence and optional shard diagnostic | Retain; no per-module filename enumeration |
| `StpPublishCoverageMapStep` | Unstashes shard outputs, joins and validates, stores on controller | Retain; collector-independent completeness/storage |
| Canonical Maven mapping example | Explicit JACOCO and project-owned mapping goals | Keep JaCoCo example; document schema-v3 ASM configuration alongside it |

Additional inspected documentation sites: README, configuration, usage,
installation, troubleshooting, development, architecture/system-overview,
reference/pipeline-steps, reference/component-ownership,
reference/support-validation-matrix, canonical README, and the PetClinic
tutorial. Current blanket Maven-ASM rejection claims are corrected with the
fixture-only scope. The historical `docs/ei-7a-executable-assignment-transport.md`
and `docs/documentation-audit.md` retain their recorded JaCoCo-only observations.
The PetClinic Jenkinsfile remains unchanged and uses JaCoCo; its tutorial now
distinguishes that proof from the new reactor-fixture enablement. No Gradle
Pipeline or collector behavior is changed.

The Maven CLI/POM can override `MAVEN_OPTS`. A small preparation check therefore
compares the effective collector of every active module with the read-only
`STP_MAPPING_COLLECTOR` environment binding before guard/attachment. This closes
the bypass without adding a field to the assignment schema or changing selection.

`asm-production-sources<suffix>.txt` is written by preparation and read by
the agent in the same Maven build. `asm-agent-diagnostic<suffix>.json` is
written by that fork and read by `generate-coverage-fragment` on the same
node. Neither is a cross-node input. Qualified fragment/evidence files are
consumed by `aggregate-reactor-coverage-fragment` before Jenkins stashes the
aggregate; the Jenkins boundary does not strip or reconstruct their suffixes.

### Agent resolution

The existing `build/functional-test-maven-repository` contains the shaded
`com.sap.oss.smart-test-picker:stp-agent:0.3.0-SNAPSHOT` publication, including
its POM and snapshot metadata. Use this existing repository in the disposable
Docker agent's Maven settings, with a separate initially empty local repository.
Normal third-party downloads remain available from Maven Central. No new
artifact service, agent bundling, version fallback, or plugin-realm dependency
is required. Maven preparation owns collection activation and derives scope
from the compiled reactor; Jenkins supplies the frozen collector selection.

## Runtime environment and reproduction

Real Docker Jenkins uses the repository's Compose controller and one inbound
agent, `stp-plugin-controller` and `stp-plugin-agent` (`stp-agent` label).
The controller has zero executors; all Maven commands run on the agent.
The existing controller volume and unrelated jobs/maps are preserved.

- Jenkins core: 2.516.2; controller Temurin 21.0.8+9.
- Agent: Linux aarch64, Temurin 21.0.12.1, Maven 3.9.15.
- Fixture Surefire/Failsafe: 3.5.3; JaCoCo: 0.8.13.
- STP components: 0.3.0-SNAPSHOT, source base above plus this task's Maven guard.
- Plugin source base: `8a0b793c3229eeb862f4a006788cf7fb76ef0b1d` plus this task's changes.
- Final installed HPI SHA-256:
  `dfa8993d33d70f4ea7e6da5935f015821c644f54ed23f0f097dde24fa48e7864`.
- HPI: `/Users/D061177/work/moje/stp-jenkins-plugin-poc/target/smart-test-picker.hpi`.
- Final deterministic fixture commit: `0e3218bce297672d173e2dce6ce747c42a5d2b8b`.

The HPI was built from the task working tree before its delivery commits.
`plugin-build.log` contains all 102 passing Jenkins tests. The final package
refresh includes the current Maven guard (`plugin-adapter-final.log` and
`plugin-package-final.log`). `-Dspotbugs.skip=true` is the existing POC build
workaround, not a claim that the full SpotBugs gate passed.

The existing controller Dockerfile needed `jenkins-plugin-cli --latest=false`:
unbounded transitive plugin upgrades required a newer Jenkins core. No pinned
core/plugin declaration was upgraded. Both failed and successful image-build
logs are retained.

The unchanged fixture is copied from
`smart-test-picker-maven/src/test/fixtures/schema-v3-reactor`. Each job deletes
its own workspace, uses a workspace-local empty Maven repository, and performs
`install -DskipTests -Djacoco.skip=true` to prepare exact-head artifacts.
The existing inventory mojo's `-pl .,module-b` and
`-DsmartTestPicker.testFilter=b.BTests#b2,shared.SharedTest#same` discover the
two-test assignment used in ASM06/08, excluding the fixture's deliberately
failing outside-assignment sentinels. No inventory/assignment JSON is fabricated.

The checked-in [Jenkinsfile](Jenkinsfile) invokes the canonical public workflow.
`settings.xml` exposes the generated functional-test repository at
`file:///opt/asm09/repository`; Maven Central supplies third-party dependencies.
The agent is not attached manually. The mapping command is:

```sh
mvn -B -ntp -s /opt/asm09/settings.xml \
  "$STP_MAVEN_MAPPING_GOAL" verify "$STP_MAVEN_MERGE_GOAL" \
  -Djacoco.skip="$FIXTURE_JACOCO_SKIP" $FIXTURE_QUALIFIER $FIXTURE_EXTRA
```

`FIXTURE_JACOCO_SKIP` is true for ASM and false for the JaCoCo control.
The qualified case supplies `-DsmartTestPicker.executionType=surefire
-DsmartTestPicker.executionId=default-test` to both discovery and mapping.
Negative cases supply only their declared faulty setting. See
[run-jenkins.sh](run-jenkins.sh) and [configure.groovy](configure.groovy) for job
provisioning, polling, and evidence download. Only the exact local validation
script is approved; no global sandbox/signature relaxation is used.

## Positive runtime evidence

| Docker Jenkins job | Build | Result |
| --- | --- | --- |
| `maven-asm-09-asm` | 5 | SUCCESS |
| `maven-asm-09-jacoco` | 1 | SUCCESS |
| `maven-asm-09-qualified` | 1 | SUCCESS |

Jobs are available at `http://localhost:8082/job/<job>/<build>/`.
Downloaded logs, build metadata, and artifacts are under `raw/<scenario>-<build>/`.
`VerifyEvidence.java` decodes the actual inventory, assignment, fragment,
execution evidence v2, and published map using production contracts. It asserts
exact identities, outcomes, edges, revision binding, an exact disjoint evidence
partition, and complete PUBLISHED state.

```text
maven:module-b::b.BTests#b2
  classes: a.AValue, b.BValue
  methods: a.AValue#<init>()V, a.AValue#value()I,
           b.BValue#<init>()V, b.BValue#value()I
maven:module-b::shared.SharedTest#same
  classes: []
  methods: []
EXECUTED = both assigned identities
NON_EXECUTED = []
```

The agent diagnostic records derived includes `a.,b.`, production-only code
sources, and two transformed classes. The qualified case retains
`maven:module-b@surefire@default-test` and the
`-module-b_surefire_default-test` suffix through module finalization and reactor
aggregation. Jenkins stashes exactly two aggregated artifacts.

`raw/fixture-map-comparison.log` checks that both collectors produce the same
map for this two-test fixture, which has no attribution-sensitive lifecycle,
exception, proxy, or shared-state constructs. Collector equivalence is not
claimed; prior evidence contradicts it. [TASK 21](../../docs/task21-full-spring-core-asm-vs-evaluation.md)
measured 25,283 differing per-test method edges on Spring Core, predominantly
attribution redistribution, not general execution loss. No inference about
PetClinic or other projects follows from this fixture comparison.

The earlier raw comparison is retained as `raw/collector-parity.log`; its
unqualified console label must be read with the restriction above, not as an
equivalence claim. The fixture controller map bytes also have the
same SHA-256, `586372c18a395e4be5f68cfffddd47f17b64deade0bf5e84c4f704963867a2c3`.
`raw/controller-map-checksums.txt` records actual controller files.
The legacy storage filename `coverage-map-v2.json` contains schema-v3 data;
this task does not change storage naming.

`raw/resolved-artifacts.txt` records the runtime JAR hashes and Maven Resolver's
`stp-functional` provenance. The resolved shaded agent SHA-256 is
`e918ed457ddd9da265dc714768467ee25560b70b7b7684eeffbb7f43c962e01e`.

## Attempts before the final proof

ASM builds #1 and #2 stopped before Pipeline execution because the validation
script had not been approved. The first provisioning correction used an
incorrect GroovyLanguage import; the corrected configuration approves only
the exact validation script. These are harness/provisioning failures.

ASM #3 succeeded with an exploratory synthetic fixture revision. #4 used the
final deterministic fixture history under the same project/branch key and
failed with `POINTER_CONFLICT` during publication, after collection and join
succeeded. This is an existing storage-policy rejection, not an ASM collection
failure. The immutable map had already been stored before the latest-pointer
update failed; no atomic rollback is claimed. #5 uses a separate stable project
key and succeeds. Earlier maps and logs were not deleted or rewritten.

The first missing/incomplete-fragment runs both stopped in the negative-test
harness with `NotSerializableException: groovy.json.JsonSlurperClassic`, before
publication. They do not prove join rejection. JSON parsing for those fault
injections was moved into a non-CPS helper and the two jobs rerun.

## Full regression rerun

`stp-suites-final.log`: **BUILD SUCCESSFUL**, 13m 29s, all 60 tasks executed.

```sh
./gradlew --no-daemon \
  :smart-test-picker-common:publishToMavenLocal \
  :smart-test-picker-core:publishToMavenLocal \
  :smart-test-picker-maven:publishToMavenLocal \
  :smart-test-picker-common:test :smart-test-picker-core:test \
  :smart-test-picker-maven:test :smart-test-picker:test :stp-agent:test \
  --rerun-tasks
```

| Suite | Tests | Failures/errors | Skipped |
| --- | ---: | ---: | ---: |
| common | 333 | 0 | 11 |
| core | 29 | 0 | 5 |
| Maven | 143 | 0 | 0 |
| Gradle integration | 46 | 0 | 0 |
| agent | 45 | 0 | 1 |

All requested baselines are retained; Maven adds one permanent test checking
both frozen/effective collector mismatch directions before agent resolution.
The initial rerun (`stp-suites.log`) failed three Vintage inventory cases on a
Maven Central DNS error resolving Failsafe, before discovery. Its log is kept;
the fresh-daemon complete rerun above passed, not just a targeted retry.
The separate plugin suite passed 102 tests with no failures or skips in
`plugin-build.log`. No Gradle production code was changed.

## Negative runtime matrix

Five failure categories, eight actual Docker Jenkins builds. Every listed job
has the `maven-asm-09-` prefix. All expected failures were checked for the
specific diagnostic and stopping point, not merely a red Jenkins result.

| Category | Job suffix / build | Observed rejection |
| --- | --- | --- |
| Explicit step collector mismatch | `mismatch-asm` #1, `mismatch-jacoco` #1 | `mapping collector mismatch`, both directions, before body entry |
| Effective Maven collector override | `cli-mismatch-asm` #1, `cli-mismatch-jacoco` #1 | `Mapping collector mismatch: manifest=ASM, effective=JACOCO` and reverse, before attachment/test execution |
| Unsafe forks | `forks` #1 | `unsupported forkCount=2`, before agent resolution and test JVM |
| Empty effective scope | `zero-scope` #1 | `classesTransformed=0`, effective includes printed; no finalized fragment or aggregate |
| Missing/incomplete join input | `missing-fragment` #2, `incomplete-fragment` #2 | `FRAGMENT_MISSING` and `JOIN_INVALID`, respectively; both `MAPPING_INCOMPLETE / FAIL_BUILD` |

The two CLI override runs exercise the new read-only environment contract in
real Maven executions through Jenkins, in addition to the permanent Maven unit
regression. A separate POM-override Docker scenario was not run; the guard uses
the same production effective-collector resolver that applies project properties.

The join-negative #2 runs first complete real collection, then inject only the
declared transport fault into disposable shard outputs/stashes. Neither publishes
a map. `raw/controller-final-map-list.txt` confirms no map exists for any of the
eight negative project keys. Earlier #1 harness failures remain as evidence but
are explicitly excluded from this acceptance matrix.

```sh
bash verification/maven-asm-09/check-results.sh \
  asm-5 jacoco-1 qualified-1 mismatch-asm-1 mismatch-jacoco-1 \
  cli-mismatch-asm-1 cli-mismatch-jacoco-1 forks-1 zero-scope-1 \
  missing-fragment-2 incomplete-fragment-2
```

All eleven expected-result checks pass; see `raw/expected-results.log`.

## Result and boundaries

**MAVEN-ASM-09: PASS for the existing reactor fixture.** Real Docker controller:
YES. Separate inbound agent: YES. Both collectors, qualified Surefire mapping,
controller publication, and all five negative categories were exercised through
public Jenkins steps. Full STP and Jenkins regression suites pass.

No broader collector equivalence is claimed. No PetClinic, AE, distributed
multi-shard, Jenkins Failsafe, selection, or S7 ClassSource-only Vintage runtime
claim is made. No Gradle behavior, selection/publication policy, schema, or
component version was changed. Per-fork merging remains unsupported.

The existing pointer-conflict behavior observed in ASM #4 is recorded, not
redesigned. The full SpotBugs gate was not run/passed; packaging used the existing
POC skip flag. No remaining blocker for this task's bounded acceptance.

Delivery checks: changed documentation relative file links and shell syntax
checks pass. Every file added by this task is below 5 MiB. The repository-wide
large-file script still reports pre-existing `EI-10/artifacts/published-r0-map.json`
and `EI-11/artifacts/deployed-smart-test-picker.hpi`; neither is changed here.
Source/report whitespace checks pass. Raw console logs retain their original
trailing spaces and terminal carriage returns rather than rewriting evidence.
