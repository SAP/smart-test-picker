# EI-11 SonarJava Docker Jenkins validation

## Result

PASS. External Docker Jenkins executed the complete production Maven schema-v3 workflow on SonarJava. Job A created and published a fresh R0 map using three concurrent physical workers and a fourth join worker. Job B resolved that map, inventoried R1, selected through the public explicit PR API, and executed exactly the selected Maven identities. Job C passed the separate full R1 Maven scope.

## Revisions and toolchain

- STP: 15ab0a8728bcbd8c57fb63a807a05a3df7910e73 to 11e1cde.
- Jenkins plugin: a1319cd0041099452a371e55d943d34687799b19 to b446df5a355b92a1a893de66aae7e598ee0c8bcd.
- SonarJava R0: 9bbe6d04d2db75e5b864f3a424bb78bfa4f36b4c.
- SonarJava R1: eed6922c776e5d029266459d04ed5a69e9d6fc8f.
- SonarJava declares minimum JDK 21. Current test source requires Java 26, so agents used JDK 26.0.2.1.
- Maven 3.9.16 ran reactor goal test.

Integration-test projects under its/plugin/projects, ruling/vibebot system scenarios, and JUnit nested-container identities unsupported by executable inventory discovery were not map targets.

The executable contract is maven:<reactor-relative-module>::<test-class>#<test-method-or-signature>. The 4,161 identities are owned by 12 modules: check-list (15), docs/java-custom-rules-example (15), external-reports (26), java-checks (2,180), java-checks-aws (12), java-checks-common (45), java-checks-test-sources/test-classpath-reader (16), java-checks-testkit (229), java-frontend (1,509), java-jsp (18), java-surefire (44), and sonar-java-plugin (52).

## Packaged runtime

The deployed HPI is artifacts/deployed-smart-test-picker.hpi, SHA-256 8ad425451a2dae4bcc2221b9039a9fb66585219bb3a2992dcdb87c73a14c6972. Jenkins loaded 1.0-SNAPSHOT (private-b446df5a-d061177).

Its Maven adapter is 0.1.0, SHA-256 2da632ce9f5a33249ad1af74628592407d1e66fb74b1ce77d66ccc029716260a. Its selector bridge is 0.2.0+jenkins-bridge-v2, SHA-256 7a2b14f1cbaf34ab53efdb10b7702529e2189731d6a93016c76947e09d7933d6.

## Job A

Build 7 succeeded. Assignment sizes were 1,636, 1,279, and 1,246, an exact partition of 4,161.

- shard 0: stp-agent, container stp-plugin-agent (36789d8e8f7c), hostname jenkins-agent, 14:59:57Z–15:02:04Z, executed 1,636, positive non-execution 0.
- shard 1: stp-map-agent-1, container stp-plugin-agent-1 (b31f6ff79a93), hostname jenkins-agent-1, 14:59:57Z–15:02:01Z, executed 1,276, positive non-execution 3.
- shard 2: stp-map-agent-2, container stp-plugin-agent-2 (3508bde4b435), hostname jenkins-agent-2, 14:59:57Z–15:02:00Z, executed 1,245, positive non-execution 1.

All three intervals overlap. Fragment hashes are 876fc596...90bfc, 4a4066b2...c4c5ae, and fbd12d30...50a911. Evidence hashes are 87286f84...d4009a, 2faaa23c...38572e, and ae972195...be67e.

Join ran on stp-map-agent-3 in stp-plugin-agent-3 (8c22066b2b69), hostname jenkins-agent-3. The map has 4,157 mapped, zero unmapped, and four positively non-executed tests. File SHA-256 is 11b10ce13a93d8527666accd259a8e32098433c51300e7c2d2e2d37596037423; schema checksum is sha256:a72e2544cddc0564804bd322ba316d8094dc629f0bac926916fc86cb15e7ee20.

The FILE path is projects/SonarJava--fcbc46d617434bf8/branches/ei11-r0--79f9ffdb0b11413e/revisions/9bbe6d04d2db75e5b864f3a424bb78bfa4f36b4c--38a70dd72330b716/coverage-map-v2.json in volume ei11_map_storage. Publication read-back validated revision and checksum.

## Controlled change and independent selection

Seed 1111 was applied to 969 sorted safe candidates. Combination zero was accepted, so no earlier combination was rejected. Exactly these methods received the requested print:

- StringOffsetMethodsCheck#getMethodInvocationMatchers
- PseudoRandomCheck#findDeclarationScope
- SQLInjectionCheck#anyMatch

The patch is selection/R0-R1.patch. No SonarJava test, Maven, STP, or Jenkins file changed.

The independent expectation used documented class-level selector semantics; method coverage is corroboration, not the boundary. It yielded 14 executable identities and 14 logical tests, 0.3364575823% of inventory. Every identity has a class/direct coverage reason; setup contribution was zero.

## Job B and Job C

Job B build 3 succeeded on stp-map-agent-3. stpLookupCoverageMap resolved the FILE map, stpHeadInventory generated the R1 inventory, stpExplicitPrSelect selected 14 executable identities, and smartTestPicker dispatched them. Maven executed exactly 14 and skipped zero.

The machine comparison proves expected = selected = executed. All are the same 14 module-qualified identities. RUN_ALL was false, no fallback occurred, and outside-assignment execution is empty.

Job C build 1 succeeded on the fourth agent. Its supported canonical projection contains 4,157 executed identities and four conditional skips. All 14 selected identities are present and 14 is strictly smaller than 4,157.

Raw Surefire reports contain 4,179 unique logical identities because 22 nested JUnit identities are outside inventory discovery. These are preserved separately in selection/executable-identity-comparison.json and are not conflated with canonical identities. No test failed.

## Fixes and regressions

STP commits repaired skipped-test accounting, evidence metadata, and Maven ownership in explicit selection. Plugin commits corrected the adapter coordinate, installed Java 26, preserved execution evidence, and packaged the ownership-aware selector/adapter. Each production fix has a focused regression.

The complete STP Gradle suite passed (68 tasks), covering schema-v3, Maven ownership, selector, schema-v2, and shared Gradle regressions. The complete plugin Maven suite passed 90 tests, including preparation/dispatch, FILE publication/lookup, explicit selection, execution, and Gradle schema-v3 regressions. No flaky or pre-existing failures remained.

No forbidden shortcut was used.
