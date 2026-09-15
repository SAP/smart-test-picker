# STP artifact versioning

All modules in this Gradle build use the STP release version declared once as
`stpVersion` in the root `gradle.properties`. The current development version is
`0.3.0-SNAPSHOT`. To begin the next release line, change that one property and run
`./gradlew verifyStpVersionConsistency`; publication POMs and project dependencies
are derived from the shared project version.

The Jenkins plugin is a separate repository and deliberately has its own HPI
version. It copies the STP value into the clearly named `stp.version` input in
`config/runtime-components.properties` and its Maven `pom.xml`. Its packaging
scripts reject a source checkout with a different `stpVersion`, and
`python3 verification/check_stp_versions.py` verifies bundled paths, coordinates,
adapter POMs, generated manifests, and the embedded repository.

Artifact releases are independent of coverage-map schema v2/v3, execution-plan
v1, inventory formats, adapter contract v1, and other wire formats. Third-party,
Java, Gradle, and Maven versions also remain independently maintained.
