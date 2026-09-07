# TASK 42 — Spring Core full schema-v2 end-to-end validation

## Environment and baseline

The repository guard passed on `research/asm-codex`: the clean starting HEAD was TASK 41 commit
`47931297bbc1ecd089deb1a3ce736b9248317d67`. A new disposable local clone at
`/private/tmp/task42-spring.oOYWCg/repo` was detached at Spring Framework
`99a366baf6640b275d08dde60f05da719139bb6a` and was clean before testing. The host was Darwin
25.5.0 arm64, the Spring wrapper was Gradle 8.14.2, the test JVM was OpenJDK 21.0.11, and Docker was
unavailable.

The exact vanilla command `./gradlew :spring-core:test`, without STP or extra filters/properties,
passed. Its Gradle XML contained 312 suites and 4,705 physical cases: 4,676 passed, 29 skipped, and
zero failed/errors.

## Configured target and inventory

Spring's build convention configures every `Test` with JUnit Platform, candidate includes
`**/*Tests.class` and `**/*Test.class`, five system properties, three `--add-opens`/`-Xshare` JVM
arguments, and CI-only retry behavior. `:spring-core:test` additionally replaces the normal main JAR
on its classpath with main class/resource directories and adds the BlockHound JVM flag. It has no
`TestFilter`, tag filter, engine filter, custom listener, or separate suite participating in this
target. Thus generic classpath-root discovery is equivalent only after auditing those candidate and
framework settings; it is not assumed equivalent in general.

Production `:spring-core:generateHeadTestInventory` discovered 3,643 exact schema-v2 logical
identities. Exact `MethodSource` reconciliation against the mapping execution plus positive Gradle XML
skip evidence produced the same 3,643-member configured target: 3,638 collector-reported identities
and five wholly skipped logical identities. The 29 physical skips include repeated parameterized
invocations and therefore are not 29 distinct logical tests. Earlier TASK 32 XML display-name
normalization reported 3,647 logical identities and four skip-only identities; authoritative
descriptor-aware `MethodSource` normalization corrects that display-level overcount to 3,643 and
five. No display-name, simple-name, class-file-suffix, or `UniqueId` guessing was used. Nested `$`
names, declared parameter lists/overloads, parameterized logical identities, and Kotlin/backtick names
are present in the inventory.

The five intentional non-executions are recorded in
`stp-spring-core-validation/task42/intentionally-non-executed.txt`. They are not synthesized from a
set subtraction: each has a positive skipped Gradle XML case and the collector confirms that every
physical invocation in the corresponding logical group aborted.

## ASM mapping and publication

The production `:spring-core:generateSmartTestCoverage` task ran the ASM collector with no second
agent. It passed all 4,705 physical cases with the vanilla 29 skips. After the regression fix described
below, the completed fragment contains 3,638 mapped tests, zero unsafe executable-unmapped tests, 112
bounded setup scopes, 45,694 direct class edges, and 171,917 descriptor-aware method edges. Together
with the five positively known non-executions, all 3,643 expected identities are accounted for;
missing, unexpected, and duplicate identities are all zero.

`Task42MapPublisher` constructs the publication through the common schema-v2 model, completeness
types, validator, and `CoverageMapCodec`; the map is not hand-edited. The resulting 2,355,124-byte map
has revision `99a366baf6640b275d08dde60f05da719139bb6a`, lifecycle `PUBLISHED`, complete shard/test
accounting, and a valid embedded checksum. Its serialized-file SHA-256 is
`85082d7b76ba1923d5e61211a5ae3768755eadcb9e1e94f91d62581f41530718`. Repeating canonical
publication from the same semantic fragment produced byte-identical output. This is a serialization
determinism check, not a claim that two independently ordered full Spring executions have identical
per-test attribution.

TASK 32 recorded 3,632 mapped plus six `SKIPPED` unmapped identities, 45,648 class edges, and 171,649
method edges. TASK 42's six-test mapped increase is the intended mixed-invocation fix. Edge totals also
include the coverage retained from their runnable parameterized invocations and normal
order-sensitive full-run attribution; no old exact edge count was forced.

Setup scopes comprise 88 `CONTAINER` and 24 `NESTED_CONTAINER` scopes. Every scope affects exactly
one concrete binary container; the largest covered-class set is 64. There is no run-wide Cartesian,
package/global expansion, or unrelated-container leakage. The controlled setup scenario selected all
seven tests in `InMemoryGeneratedFilesTests`; 12 additional direct-coverage selections were valid,
and no unrelated container was selected solely by setup.

## Selector and actual execution

All scenarios used the schema-v2 selector followed by a separate production `smartTest` invocation:

| Scenario | Decision | Mandatory selected | Gradle runtime result |
|---|---|---:|---|
| no change, `R == H` | `NONE` | 0 | 0 |
| direct `MissingRequiredPropertiesException` change | `SELECTED` | 1 | 24 passed after conservative class widening |
| changed `PropertySourcesPropertyResolverTests` source | `SELECTED` | 24 | 24 passed |
| new `Task42NewTests#discoveredAndExecuted` | `SELECTED` | 1 | new identity discovered and passed |
| missing map | `FULL_SUITE` | full target | 4,705 cases, 29 skipped, zero failed |
| malformed/corrupt map | `FULL_SUITE` | full target | 4,705 cases, 29 skipped, zero failed |
| checksum mismatch | `FULL_SUITE` | full target | fail-open decision confirmed |
| `gradle.properties` broad trigger | `FULL_SUITE` | full target | 4,705 cases, 29 skipped, zero failed |
| valid descendant/nonancestor revision | `FULL_SUITE` | full target | 4,705 cases, 29 skipped, zero failed |
| bounded setup-scope change | `SELECTED` | 19 | 32 passed across three widened classes |
| one controlled executable `UnmappedTest` | `SELECTED` | 1 | adapter attempted it; 24-class cases passed |

For every `SELECTED` case all mandatory identities appeared in Gradle XML or were covered by the
documented class-level widening; no mandatory identity silently disappeared. Widening produced extra
tests but never under-testing. The controlled unmapped fixture remained `SELECTED`, not `FULL_SUITE`,
proving `ALWAYS_SELECT`. Selection still uses class edges and setup scopes only. Method edges remain
stored evidence; no descriptor-less method/overload guessing or method-level execution selector was
introduced.

Malformed/missing inventory, unsupported identities, incomplete maps, checksum failures, moving
HEAD, nonancestor revisions, and internal inconsistency remain fail-open contracts covered by the
focused repository suites; the Spring runs directly exercised missing, corrupt, checksum, and
nonancestor paths. No restrictive fallback, 5d storage/orchestration, or 5e arbitrary-base behavior
was added.

## Bugs and fixes

Spring exposed two narrow bugs:

1. **5c collector projection:** an aborted invocation set a logical group's `aborted` bit even when
   sibling parameterized invocations completed. Six `DataBufferTests` methods lost valid coverage and
   became `UnmappedTest(SKIPPED)`, causing no-change selection to select six tests under the correct
   `ALWAYS_SELECT` policy. Projection now emits `SKIPPED` only when all physical invocations abort;
   mixed runnable/skipped groups retain their collected union. A focused regression covers this.
2. **Gradle adapter glue:** `smartTest` copied classpath/directories but not JVM arguments, heap,
   assertions, system properties, or candidate includes/excludes. Missing-map execution initially
   failed 13 Spring tests and reported only 4,702 cases. It now mirrors those standard-target settings,
   as the mapping task already did, and the unrestricted run exactly matches vanilla at 4,705/29/0.
   A focused adapter regression covers the mirrored properties.

These findings temporarily reopened 5c during validation; the fixes and full Spring regressions close
it again. No selector semantics, method-level selection, 5d, or 5e work was introduced.

## Readiness

Spring Core target inventory, automatic head inventory, ASM mapping, schema-v2 completeness and
publication, selection, and actual Gradle execution all pass. The core system is ready for 5d. The
remaining work is the deliberately unstarted 5d productization boundary: remote storage, registry,
retention, lookup, scheduling, and production orchestration.
