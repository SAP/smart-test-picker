# STP artifact versioning

All modules in this Gradle build use the STP release version declared once as
`stpVersion` in the root `gradle.properties`. The current development version is
`0.3.0-SNAPSHOT`. Publication POMs and project dependencies derive from that value.
When changing it, update current documentation snippets in the same change and run
`./gradlew verifyStpVersionConsistency`. The check also extracts STP coordinates
and versioned commands from current tracked Markdown; historical records under
`docs/history/` keep their original versions. External integrations pin their own
dependencies and are not changed by this build.

This version check runs entirely in Gradle/Groovy and does not require Python,
including when invoked through the normal `check` or `build` lifecycle.

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

## Local publication verification

The publication set is the Gradle plugin and its marker, core, common, Maven
plugin, shaded agent and shaded CLI. Runtime and JUnit adapter code is bundled
where required, rather than separately published. Each Java publication includes
real sources and Javadoc, complete POM metadata and a Java 17 API/bytecode baseline.

Use a command-line test version for local verification without changing the source
version:

```sh
./gradlew -PstpVersion=0.0.0-readiness verifyReadinessArtifacts verifyReadinessJava17
./gradlew verifyReadinessDocumentation verifyReadinessJavadoc
```

The first command stages publications in `build/readiness/staging`, a file-only
repository. These opt-in readiness checks require Python 3 and a JDK; they are not
dependencies of the normal `check` or `build` lifecycle. `readinessConsumerSmoke` additionally
uses `STP_READINESS_GRADLE`, `STP_READINESS_MAVEN` and `STP_READINESS_JAVA17` to run
isolated Java 17 consumers against that repository and Maven Central.

`centralBundle` builds a local Maven-layout ZIP in `build/readiness/`. It refuses a
SNAPSHOT version and requires `STP_SIGNING_KEY` (an ASCII-armored private key) and,
for a protected key, `STP_SIGNING_PASSWORD`. Signing is in memory. The archive has
artifact signatures plus MD5, SHA-1, SHA-256 and SHA-512 digests. Do not commit keys.
Use only a disposable test key when checking readiness; a test-signed bundle is
not a release artifact.

`verifyReadinessBundle -PreadinessBundle=<zip>` checks a built archive. Set
`STP_READINESS_PUBLIC_KEYRING` to the disposable key's exported public keyring for
signature verification. `verifyReadinessLicenses` runs REUSE; `REUSE` can name its
executable. No task described here uploads anything or configures the Plugin Portal.
