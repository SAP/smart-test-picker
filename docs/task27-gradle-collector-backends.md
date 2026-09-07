# TASK 27 Gradle collector backends

## Configuration API

The existing `smartTestPicker` extension now accepts:

```groovy
smartTestPicker {
    coverageCollector = 'ASM' // default; alternative: 'JACOCO'
    revision = providers.environmentVariable('GIT_COMMIT')
    shardId = 'ci-shard-1' // optional
    coverageIncludes = ['com.acme.']
    coverageExcludes = ['com.acme.generated.']
}
```

Only `ASM` and `JACOCO` are accepted, case-insensitively. An unknown value fails configuration with
the supported values in the message. There is no simultaneous STP collector mode. Independent
project JaCoCo reporting is not an STP `BOTH` mode and remains allowed.

## Backend architecture and capabilities

`CoverageCollectorBackend` is the single Gradle-facing setup boundary. It configures the dedicated
`generateSmartTestCoverage` `Test` task; selector and orchestration logic are absent from backends.

| Semantic capability | ASM | JACOCO |
|---|---:|---:|
| Class coverage | yes | yes |
| Exact method coverage | yes | no |
| Descriptor-aware methods | yes | no |
| Setup scopes | yes, for the bounded supported model | no; legacy attribution only |
| Direct schema-v2 fragment | yes | no |

JaCoCo method strings are preserved as-is. No JVM descriptors are guessed. The backends therefore
currently terminate at different safe artifact stages: ASM at a schema-v2 fragment and JaCoCo at the
legacy exec/XML/map pipeline.

## ASM wiring and artifact resolution

The ASM backend resolves exactly one artifact through the internal resolvable `stpAgent`
configuration. In this multi-project build it consumes `:stp-agent`'s `stpAgentElements`; published
use defaults to `com.sap.oss.smart-test-picker:stp-agent:<plugin-version>`. The shaded agent bundles
runtime, the JUnit adapter, and relocated ASM. Nothing downloads from a test JVM and no local path is
hard-coded.

The plugin supplies `-javaagent` only to `generateSmartTestCoverage`. Normal `test` and selection-time
`smartTest` remain unaffected. Mapping uses one fork to avoid multiple JVMs writing one shard file.
Each task/shard writes:

```text
build/stp/coverage/<sanitized-task-path>/<sanitized-shard-id>/fragment.json
build/stp/coverage/<sanitized-task-path>/<sanitized-shard-id>/agent-diagnostic.json
```

Neither persisted schema-v2 fragment identities nor edges contain absolute paths. Distinct task and
shard identities do not overwrite each other. The fragment receives the unsanitized explicit shard
identity. The default is `gradle:<taskPath>`; `shardId` or `-Dstp.shardId` supports orchestration.

`revision` falls back through `-Dstp.revision`, `GIT_COMMIT`, then `UNKNOWN`. The agent performs no Git
discovery. CI should provide the exact revision. Run ID is deterministic from task and shard for
diagnostics; JVM identity still isolates concurrent processes, and run ID never becomes logical test
identity in the fragment. Collector provenance remains diagnostic/out-of-contract: the agent shell
records `collector: ASM`. Adding fragment provenance would change the schema-v2 contract, so TASK 27
does not introduce another schema bump. Legacy JaCoCo metadata remains unchanged and its
`jacocoVersion` is not overloaded as generic collector identity.

## JaCoCo fallback and coexistence

`coverageCollector = 'JACOCO'` configures the existing `stp.exec.dir`, listener/extension, per-test
exec, XML conversion, `CoverageMapperJaxb`, and legacy map stages. It does not attach the STP ASM
agent. This is the compatibility switch for users whose old implicit collector was JaCoCo.

When a project independently applies Gradle's JaCoCo plugin, its normal agent may coexist in the ASM
mapping JVM. A bounded TestKit fixture verifies JVM startup, bytecode verification, ASM fragment
retention, and JaCoCo exec retention.

## Configuration cache and reproducibility

Collector, revision, shard, and agent configuration version are explicit mapping-task inputs. The
agent artifact is a classpath input and its argument provider resolves lazily at execution. Fragment
and diagnostic paths are declared outputs. A TestKit fixture reuses the Gradle configuration cache
while rerunning the ASM mapping task. Runtime diagnostic IDs are not cache inputs.

## Known limitations

- ASM produces a fragment, not the final joined/published coverage map; 5d owns merge, inventory,
  completeness, publication, and Jenkins orchestration.
- The default `UNKNOWN` revision is diagnostic convenience for local runs, not a CI publication
  revision.
- A mapping task is intentionally single-fork per shard. External parallelism must supply distinct
  shard IDs.
- JaCoCo retains its legacy semantics and artifact stage.
- Maven integration, selector semantics, and Maven/Jenkins behavior are unchanged.
