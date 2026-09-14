# EI-12 authoritative SonarJava test-execution matrix

Source revision: `9bbe6d04d2db75e5b864f3a424bb78bfa4f36b4c`.

The root effective POM contains 27 reactor projects. `prod` and `test` are Java-source counts at R0. `gen` is generated Java test sources present before execution. Every reactor jar inherits Surefire `default-test`; `skipTests=true` means the effective default reactor deliberately does not invoke it. Report directory is `<path>/target/surefire-reports` unless stated otherwise. No SonarJava-owned effective POM configures Failsafe at R0. Failsafe configurations in `its/sources` belong to fixture projects analyzed by ruling and are not launched as SonarJava JUnit identities.

| module/target | path | packaging | prod | test | gen | Surefire / Failsafe / custom | profile/service | canonical STP target | R0 inventory | status/reason |
|---|---|---:|---:|---:|---:|---|---|---|---:|---|
| java | . | pom | 0 | 0 | 0 | none / none / build orchestration | none | none | 0 | root aggregator |
| java-checks-test-sources | java-checks-test-sources | pom | 0 | 0 | 0 | none / none / dependency preparation | none | none | 0 | aggregator |
| aws test sources | java-checks-test-sources/aws | jar | 9 | 0 | 0 | default-test skipped / none / none | default skip | none | 0 | production fixture sources, no tests |
| default test sources | java-checks-test-sources/default | jar | 1062 | 115 | 0 | default-test skipped / none / none | default skip | none | 0 | compiled fixtures; not a CI test execution |
| Java 17 test sources | java-checks-test-sources/java-17 | jar | 1 | 1 | 0 | default-test skipped / none / none | default skip | none | 0 | compiled fixtures; not a CI test execution |
| Spring 3.2 test sources | java-checks-test-sources/spring-3.2 | jar | 7 | 0 | 0 | default-test skipped / none / none | default skip | none | 0 | production fixture sources, no tests |
| Spring Web 4.0 test sources | java-checks-test-sources/spring-web-4.0 | jar | 1 | 0 | 0 | default-test skipped / none / none | default skip | none | 0 | production fixture sources, no tests |
| Quarkus ArC test sources | java-checks-test-sources/quarkus-arc-3.15 | jar | 1 | 0 | 0 | default-test skipped / none / none | default skip | none | 0 | production fixture sources, no tests |
| test-classpath-reader | java-checks-test-sources/test-classpath-reader | jar | 2 | 1 | 0 | default-test / none / none | default | `maven:java-checks-test-sources/test-classpath-reader@surefire@default-test` | 16 | executable |
| java-frontend | java-frontend | jar | 360 | 149 | 0 | default-test / none / unpack test projects | default | `maven:java-frontend@surefire@default-test` | 1509 | executable |
| java-checks-testkit | java-checks-testkit | jar | 23 | 19 | 0 | default-test / none / none | default | `maven:java-checks-testkit@surefire@default-test` | 229 | executable |
| java-checks-common | java-checks-common | jar | 9 | 8 | 0 | default-test / none / none | default | `maven:java-checks-common@surefire@default-test` | 45 | executable |
| java-checks | java-checks | jar | 821 | 775 | 0 | default-test / none / none | default | `maven:java-checks@surefire@default-test` | 2202 | executable, includes 22 corrected QuickFix identities |
| java-checks-aws | java-checks-aws | jar | 12 | 8 | 0 | default-test / none / none | default | `maven:java-checks-aws@surefire@default-test` | 12 | executable |
| check-list | check-list | jar | 2 | 1 | 0 | default-test / none / generated test resource root | default | `maven:check-list@surefire@default-test` | 15 | executable |
| external-reports | external-reports | jar | 9 | 6 | 0 | default-test / none / none | default | `maven:external-reports@surefire@default-test` | 26 | executable |
| java-surefire | java-surefire | jar | 12 | 8 | 0 | default-test / none / fixture report parsing | default | `maven:java-surefire@surefire@default-test` | 44 | executable |
| java-jsp | java-jsp | jar | 3 | 2 | 0 | default-test / none / copied fixtures | default | `maven:java-jsp@surefire@default-test` | 18 | executable |
| sonar-java-plugin | sonar-java-plugin | sonar-plugin | 11 | 13 | 0 | default-test / none / plugin packaging | default | `maven:sonar-java-plugin@surefire@default-test` | 52 | executable; sanity methods require separate service target |
| java-its | its | pom | 0 | 0 | 0 | none / none / profile parent | tests skipped by default | none | 0 | aggregator |
| it-java-plugin | its/plugin | pom | 0 | 0 | 0 | none / none / Orchestrator | `it-plugin`; Docker SonarQube | none | 0 | aggregator |
| it-java-plugin-plugins | its/plugin/plugins | pom | 0 | 0 | 0 | none / none / plugin packaging | tests skipped | none | 0 | aggregator |
| java-extension-plugin | its/plugin/plugins/java-extension-plugin | sonar-plugin | 9 | 1 | 0 | default-test skipped / none / plugin packaging | tests skipped | none | 0 | support plugin, no CI test invocation |
| docs | docs | pom | 0 | 0 | 0 | none / none / documentation | none | none | 0 | aggregator |
| java-custom-rules-example | docs/java-custom-rules-example | sonar-plugin | 18 | 14 | 0 | default-test / none / alternate-POM CI build | default | `maven:docs/java-custom-rules-example@surefire@default-test` | 15 | executable |
| it-java-plugin-tests | its/plugin/tests | jar | 0 | 18 | 0 | default-test skipped / none / Orchestrator | `it-plugin`; Docker SonarQube | `maven:its/plugin/tests@surefire@default-test@it-plugin` | 52 | executable profile target |
| it-java-ruling | its/ruling | jar | 0 | 3 | 0 | default-test skipped / none / Orchestrator and fixture analysis | default skip | none | 0 | split into qualified CI targets below |

## Additional CI/profile targets

| target | source workflow | invocation | service | reports | canonical STP target | inventory | status |
|---|---|---|---|---|---|---:|---|
| Plugin QA | `build.yml:plugin-qa` | `its/plugin`, `package -Pit-plugin` | SonarQube LATEST_RELEASE and nightly DEV | `its/plugin/tests/target/surefire-reports` | `maven:its/plugin/tests@surefire@default-test@it-plugin` | 52 | production inventory proven |
| Ruling without SonarQube project | `build.yml:ruling-qa` | `its/ruling`, `package -Pit-ruling,without-sonarqube-project` | SonarQube LATEST_RELEASE; ruling source submodules | `its/ruling/target/surefire-reports` | `maven:its/ruling@surefire@default-test@without-sonarqube-project` | 7 | production inventory proven |
| Ruling SonarQube project only | `build.yml:ruling-qa` | `its/ruling`, `package -Pit-ruling,only-sonarqube-project` | SonarQube LATEST_RELEASE; ruling source submodules | `its/ruling/target/surefire-reports` | `maven:its/ruling@surefire@default-test@only-sonarqube-project` | 1 | production inventory proven |
| Sanity | `build.yml:sanity` | `sonar-java-plugin/pom.xml`, `verify -Psanity -Dtest=SanityTest` | externally configured SonarQube in official CI; locally replaceable Docker SonarQube | `sonar-java-plugin/target/surefire-reports` | `maven:sonar-java-plugin@surefire@default-test@sanity` | 2 | production filtered inventory proven |
| Custom rules alternate POM | `build.yml:custom-rules-license-check` | `docs/java-custom-rules-example/pom_SQ_10_6_LATEST.xml`, `clean package` | none | alternate project target reports | `maven:docs/java-custom-rules-example@surefire@default-test@sq-10.6-latest` | 15 | production alternate-POM inventory proven |
| Vibebot ruling scenario | explicitly excluded by `without-sonarqube-project`; not selected by `only-sonarqube-project`; no standalone workflow | `JavaRulingTest#vibebot` | SonarQube plus `its/vibebot` fixture | `its/ruling/target/surefire-reports` | `maven:its/ruling@surefire@default-test@vibebot` | 1 | explicit production filtered inventory proven; execution remains required by EI-12 |

The `qa-os-win` and `test-analyze` jobs repeat the default reactor test execution on another OS or with analysis; they do not define a distinct Maven test execution identity. The ruling matrix repeats the same two profile-qualified identities on Linux and Windows. OS is execution evidence, not canonical test ownership.

Inventory and execution counts are provisional until Jobs A and C reconcile reports. Mapping and selection status remains pending until those production jobs complete.
