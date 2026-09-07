# TASK 39 — authoritative JUnit head inventory generation

`JUnitHeadTestInventoryGenerator` is the shared, build-tool-neutral producer. It creates a JUnit
Platform `LauncherDiscoveryRequest`, calls `Launcher.discover`, walks the resulting `TestPlan`, and
never calls `execute`. Exact schema-v2 identities come only from `MethodSource.getClassName()`,
`getMethodName()`, and `getMethodParameterTypes()`. Display names, class files, paths, simple names,
and `UniqueId` parsing are not identity sources.

Every descriptor carrying a `MethodSource` contributes its declared logical identity. This matters
for parameterized, repeated, and template descriptors, which may still be containers during
discovery. Invocation children collapse through exact-set deduplication; overload parameter lists
remain distinct and nested classes retain JUnit's binary `$` name. An executable leaf without its
own or an ancestor's usable `MethodSource` aborts generation. Adapters remove stale output and let
selection return `FULL_SUITE` after such a failure.

`HeadTestInventoryCodec` writes a UTF-8 JSON array, natural identity order, compact JSON, and one LF.
It rejects duplicate, null, malformed, and non-array input.

Gradle registers `generateHeadTestInventory`, mirrors the standard `test` task's class directories
and runtime classpath, writes `build/head-test-inventory.json`, and runs after `testClasses`.
`selectTests` depends on it. The inventory task is not a `Test` task and receives no coverage agent,
test JVM hook, or collector configuration.

Maven exposes `generate-head-test-inventory`. `select-tests`, `root-select-tests`, and `smart-test`
also invoke the same helper internally before selection. Aggregator flows discover each non-POM
reactor module from its test output and test runtime classpath, then reject an exact identity collision
between modules as unsafe.

Disabled and conditional declarations remain in inventory because execution conditions are not an
authoritative discovery-universe filter. On pinned PetClinic, discovery therefore produces 73 exact
logical identities: the 69 runnable mapping baseline plus four disabled DB-profile identities (two
MySQL and two PostgreSQL). This difference is fully explained; a publication made from the older
69-entry execution-only inventory initially treats the four identities as new under the unchanged
selector policy. Once the published logical inventory includes those declarations, no-change selection
can return `NONE` again.

The CLI deliberately retains explicit `--head-inventory`; it cannot infer a build-tool-owned runtime
universe. No selector, coverage, schema-v2, 5d, or 5e policy changed. Because the locked policy treats
those four previously unpublished disabled identities as new, the requested PetClinic `NONE` case is
not yet attainable with the preserved map; Task 5b therefore remains in progress.
