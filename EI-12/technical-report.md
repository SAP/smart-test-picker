# EI-12 full SonarJava production validation

Status: **BLOCKED**.

## Blocking capability

The official SonarJava `plugin-qa` and `ruling-qa` targets require SonarSource-only infrastructure. The workflow obtains an Artifactory `private-reader` role and a Vault `licenses-ro` token. The test implementation selects `Edition.ENTERPRISE_LW` and invokes `activateLicense()`.

Exact failing production workflow:

```text
Job: EI-12-Job-A #6
Target: maven:its/plugin/tests@surefire@default-test@it-plugin
Command: mvn -s .ei12-settings.xml -B -ntp -f its/plugin/pom.xml -Pit-plugin package ...
Failure: Fail to request versions at
https://repox.jfrog.io/artifactory/api/search/versions?g=com.sonarsource.sonarqube&a=sonarqube-enterprise-lw&remote=1&repos=sonarsource-releases&v=*
```

The endpoint returned HTTP 401 from a physical Jenkins agent and `GITHUB_TOKEN` was absent. No Enterprise LW distribution or activated license exists in the local Maven caches, Docker images, or Orchestrator cache. A Community SonarQube container is not equivalent: it changes the hard-coded edition and bypasses `activateLicense()`. Changing SonarJava to do so is expressly forbidden by EI-12.

## Work completed

- Accounted for all 27 reactor projects and seven additional CI/profile targets.
- Implemented executable target ownership, complete reactor/profile discovery, nested JUnit discovery, JUnit 4 discovery, Surefire/Failsafe routing, complete-inventory merging, exact sharding, and fail-closed reconciliation.
- Closed the EI-11 nested discrepancy: the complete unit inventory is 4,183, including all 22 QuickFix nested identities.
- Generated seven production inventories and merged them to 4,261 canonical executable occurrences.
- Generated a production schema-v3 manifest and exact three-way partition: 1,641 + 1,341 + 1,279 = 4,261.
- Ran three physically distinct Docker agents concurrently. Unit-scope fragments were produced before the mandatory plugin target failed.
- Reproduced and fixed module-owned JUnit runtime isolation exposed by `its/plugin` inventory discovery.
- Passed the complete Smart Test Picker Gradle suite.
- Passed the complete Jenkins plugin suite: 90 tests, zero failures/errors/skips.
- Built and deployed the corrected HPI and verified the active plugin before the runtime attempt.
- Preserved Job A #5 and #6 logs, production inventory, assignments, manifest, and per-target inventories.

## Why later acceptance jobs were not fabricated

Job A did not publish a complete immutable map because mandatory CI targets could not execute. Therefore the prerequisites for deterministic R1 selection, independent A/B/C comparison, Job B, and Job C do not exist. Creating a partial map, R1, or selector result would violate the explicit no-shortcut rules. No SonarJava source, tests, POM, or CI configuration was changed.

## Checksums

```text
smart-test-picker-common-0.2.0.jar 7652b76f75f02c1a367f48d5f27fe7b27de64b08a7e481465eafbff8d9049c44
smart-test-picker-core-0.1.0.jar   574269138467b5242422d7dde1fa44ddbbab79f894f30721fe9a1b622c444dac
smart-test-picker-maven-0.1.0.jar 9e089d8c6b01837bfbbaa3e62349ade4326d237bbbe6c54803453d09c61c251a
smart-test-picker.hpi             923318f7aab3aa726abed3ab4b44eb288e499cd2028bbd7f9b266997ad5d0ae7
```

## Cleanup

The controller and four physical agents were stopped. Jenkins state and map-storage volumes were preserved. No EI-12 supporting-service container was started. SonarJava remains clean at R0, `ei-11-r1` remains at `eed6922c776e5d029266459d04ed5a69e9d6fc8f`, and nothing was pushed.
