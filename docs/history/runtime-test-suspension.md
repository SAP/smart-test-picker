# Historical validation: runtime-test-suspension.md

This is retained validation evidence, not a current setup instruction. See the
[current contract](../runtime-test-suspension.md).

## Local validation (2026-11-12)

The focused command passed all 24 tests (16 new suspension tests and eight
existing `MethodProbeTest` tests):

```sh
./gradlew :stp-runtime:test --tests '*RuntimeSuspensionTest' --tests '*MethodProbeTest' --offline
```

The first focused run exposed a missing simulated instrumentation marker in the
new ForkJoin test fixture. Adding the same marker used by the existing
`ForkJoinContextTest` made the test exercise captured ownership. No production
ForkJoin code was changed to accommodate the test.

The retained Maven research harnesses were run afresh against the artifacts
published by this branch's full rerun into the functional-test Maven repository.
Each gate used a separate local Maven repository, excluding old STP artifacts
from the dependency cache. Artifact provenance is retained with the raw output.

- **Reference Maven application:** revision `88e37c15cf6fc8490b01bc3e8e2c800cec1ac272`, ASM,
  the same 69-test H2 scope. The production codecs verified assignment=69,
  mapped=69, executable-unmapped=0, positive-non-execution=0. Fragment SHA-256:
  `0ecdbed9d086835971a9c69e3a49b6977ca24c42058ce2bf3618d8fc1820b8b9`.
  Raw occurrence counts remain 3,816 total and 3,079 leaf. Mapping completed
  successfully (21.59 seconds wall time; not a performance comparison).
- **Shared cached web-context fixture:** still emits
  `ASYNC_SETUP_UNSUPPORTED:ERROR` for work captured by the closed first JUnit
  container. `collectionCompleted=false`; mapping and aggregation both exit 1.
- **Parallel execution fixture:** still emits `ASYNC_SETUP_UNSUPPORTED:ERROR`
  for `ForkJoinTask: execution without observed submission`.
  `collectionCompleted=false`; mapping and aggregation both exit 1.
- In both rejection gates, the real assignment validator and joiner report
  `IllegalArgumentException: Fragment collection is not completed`. No external
  map publication was attempted.
- **Hot path:** source comparison against `05d5c3b` found the existing
  `RuntimeContextService` methods from `currentTest` onward byte-identical,
  including recording, propagation, setup and diagnostics. `MethodHitOwner`,
  `MethodProbe`, `RuntimeHooks` and `ForkJoinContexts` are also byte-identical.
  The new repeat-hit regression verifies the retained owner, occurrence count
  and absence of an aggregator-monitor acquisition after resume.

Raw evidence and isolated work directories are retained locally, not added to
the delivery branch. Validation reused the existing reference-application and
rejection-fixture harnesses. No external integration was executed.

The full suite was rerun, not reused:

```sh
./gradlew test --rerun-tasks --offline --continue
```

Result: `BUILD SUCCESSFUL in 8m 42s`, 69 tasks executed. Counts were read from
the fresh JUnit XML files; all modules have zero failures and zero errors.

| Module | Tests | Passed | Skipped |
| --- | ---: | ---: | ---: |
| smart-test-picker-common | 342 | 331 | 11 |
| smart-test-picker-core | 29 | 24 | 5 |
| smart-test-picker | 58 | 58 | 0 |
| smart-test-picker-maven | 161 | 161 | 0 |
| smart-test-picker-cli | 36 | 36 | 0 |
| stp-runtime | 83 | 83 | 0 |
| stp-junit-adapter | 21 | 21 | 0 |
| stp-agent | 52 | 51 | 1 |
| **Total** | **782** | **765** | **17** |

After replacing the test-only engine label with a generic value, the runtime
suite was rerun with `./gradlew :stp-runtime:test --rerun-tasks --offline`:
83 tests passed, zero failures, errors or skips. Production Java sources were
unchanged by that documentation/test-label amendment; the full-suite and
integration results above were not rerun or presented as new measurements.

The 16 additional tests increase the base's 766 tests to 782; no existing test
was removed. Console logs are retained at `build/runtime-suspend-full-suites.log`
and `verification/stp-runtime-suspend-01/full-suites.log`. The repository's
tracked-file size check and `git diff --check` also pass.

Delivery branch: `feature/runtime-suspend-resume`. The commit uses `[skip ci]`
to suppress the pull-request CI test workflow. Automatically triggered analysis
jobs are not part of the local validation results above.
