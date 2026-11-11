# STP artifact versioning

All modules in this Gradle build use the STP release version declared once as
`stpVersion` in the root `gradle.properties`. The current development version is
`0.3.0-SNAPSHOT`. To begin the next release line, change that one property and run
`./gradlew verifyStpVersionConsistency`; publication POMs and project dependencies
are derived from the shared project version.

The Jenkins plugin is a separate repository and deliberately has its own HPI
version. It copies the STP value into `stp.version` in
`config/runtime-components.properties` and its Maven `pom.xml`. The standard
Maven lifecycle builds/installs the Maven adapter first, then packages the HPI,
generates its runtime manifest and checks pinned coordinates and checksums. Python
is not part of the HPI build lifecycle.

Maven ASM mapping preparation reads its running version from Maven's injected
`PluginDescriptor`, backed by the generated plugin descriptor, and resolves the
shaded `com.sap.oss.smart-test-picker:stp-agent` JAR at exactly that version.
There is no fallback version or agent-version override. Resolution uses the
current Maven repository session and project repositories and obtains a file,
not a plugin-realm dependency. Missing metadata or a missing artifact fails
preparation before tests. See the [Maven attachment contract](../smart-test-picker-maven/README.md#prepared-asm-attachment).

Artifact releases are independent of coverage-map schema v2/v3, execution-plan
v1, inventory formats, adapter contract v1, and other wire formats. Third-party,
Java, Gradle, and Maven versions also remain independently maintained.
