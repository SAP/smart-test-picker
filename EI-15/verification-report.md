# EI-15 verification report

## Baseline and scope

- STP initial HEAD: `7bdb53c1ed71cf19817f95a2656c5e338eea5b55` on `research/asm-codex`; only unrelated untracked `EI-9/` existed.
- Recorded STP checkpoint `7bdb53c09cd5e9d6a1a80c1b39bf8a4b8ca52306` was not present in the object database. It was not treated as a reset instruction.
- Jenkins initial HEAD: `955b12d0c395d305f73172706d35c3e3ce97343d` on `ei13/maven-distributed-selection`, clean.
- EI-13 and EI-14 reports, artifacts and complete logs were retained unchanged.

## Fixes and focused verification

1. Legacy CLI ingress accepted any JSON and interpreted executable schema v3 as an empty legacy map. STP `a4c4353` rejects declared schema versions at the unversioned legacy reader. `CoverageMapReaderTest` passes, and the rebuilt CLI returns 1 with an explicit incompatibility message for the retained EI-14 map.
2. The checked-in Maven execution adapter was not reproducible across packaging JDKs because `Build-Jdk-Spec` varied. Jenkins `a49b4e8` pins that manifest field to the adapter's Java 8 target and regenerates the binary (`fd82a0eadfb7ea109c7c01d5dd83ba50d2bd81e2d517cc7dc4723398660f43d2`). The adapter verifier passes on Java 21.
3. Adapter verification used one persistent hard-coded `/tmp` Maven repository. Jenkins `a6b0fb4` uses the clean HPI target by default and permits an explicit isolated repository. The clean HPI gate exercised the target-local repository.
4. Four Jenkins mapping tests embedded one developer's absolute JDK/Gradle cache paths. Jenkins `874def4` uses the checked-in fixture wrapper resolved from the checkout; all 14 focused mapping tests pass.
5. The Jenkins README/developer guide incorrectly said multi-module Maven had not been validated. `874def4` now states the exact EI-13 reactor evidence without generalizing it.

## Clean build and package evidence

Bootstrap order:

1. Create initially empty `/tmp/ei15-gradle-cache` and `/tmp/ei15-maven-cache`.
2. Run Java 17 `./gradlew clean test` with the isolated Gradle cache: PASS, 77/77 tasks executed in 2m51s.
3. Publish the just-built `smart-test-picker-common:0.2.0` into the isolated Maven cache. Installed and build JAR SHA-256 both `014cc89625b08957c508fd4ff78d2d98eccf13900562cbefa8ee2d6fad48d915`.
4. Run Java 21 `mvn ... -Dmaven.repo.local=/tmp/ei15-maven-cache clean package`: PASS in 6m15s; 96/96 Jenkins tests; adapter rebuild/checksum PASS; HPI license index and HPI generated.
5. Run standalone mapping runtime tests: PASS, 41 tests total, 38 executed and 3 conditional external-Nexus tests skipped.

External inputs were Gradle 8.14 from the wrapper distribution, Maven Central, and the Jenkins public repository. No machine-global unpublished STP artifact was an input.

## Lifecycle, execution, publication and evidence reuse

The full STP suite covers current JUnit listener/runtime behavior. EI-14 remains the production proof for the corrected aborted-invocation behavior: 55,879 raw invocations, 1,905 mapped executable identities, 88 evidenced non-executed declarations, and no mapping/report mismatch. EI-13 remains applicable to unchanged Maven discovery, plan sharding, exact selected/executed reconciliation, NONE zero execution, full-suite control, and immutable publication conflict.

Mapping runtime tests cover missing/mismatched inventory, evidence and fragments, incomplete/duplicate join, revision mismatch, FILE and RAW_HTTP object/pointer behavior, and invalid publication. EI-14 external negative namespaces remained without a map or pointer. EI-15 did not change these production paths, so repeating the large Commons Statistics mapping was neither necessary nor validly substituted with a smaller claim.

## Determinism and resources

The largest retained representative artifact is `EI-14/evidence/r0-publication-1/build/stp/coverage-map-ei14.json`: 4,158,128 bytes, 1,905 mapped identities and 1,993 expected identities.

- `MapResourceProbe` on Java 17, `-Xmx512m`: decode 213.879 ms, approximate post-GC heap delta 74,863,600 bytes, max heap 536,870,912 bytes.
- Packaged mapping runtime join on the three retained fragments/evidence files: 0.37 s. Repeating under the same Java/runtime inputs produced byte-identical SHA-256 `0c6eb2037106b06b171d47ae0bcc60ee384e2bd8e568106eb9bb8111e8a4322c`.
- The retained EI-14 JDK21 map has different bytes because `generator.jdkVersion` is intentionally recorded (`21.0.12.1` versus the probe's `17.0.19`). This is documented variable provenance, not a byte-determinism failure.
- Packaged selector explicit R0→R1 run: 0.47 s under `-Xmx512m`; status `SELECTED`, independently expected 123 target-qualified plan entries, plan size 28,472 bytes.

No repeated-decode resource failure or excessive Remoting payload was demonstrated, so streaming/lazy-loading work is optional rather than an EI-15 fix.

## Security and evidence organization

Tracked-file pattern scans for common access tokens, AWS signed URLs/credentials, Authorization headers, and credential-in-URL forms found no maintained secret. The sole authorization-pattern file is a RAW_HTTP test that uses fake credentials and asserts non-disclosure. Historical evidence was not rewritten. Build outputs remain ignored; `EI-9/` was preserved.

## Packaged smoke

Maintained entrypoint: `JAVA_HOME=<JDK21> STP_SOURCE_ROOT=<STP checkout> python3 verification/verify.py` in the Jenkins repository. It builds/inspects the HPI, launches the task-owned controller/agent, and runs a maintained Maven multimodule R0→R1 scenario through all six production steps. Final mapping #1 (`12ab336e0c249e9540bed2ade8222c65091abc93`) and selection #1 (`eb2ffb556675c80ea7a396c2513ff22880f61bda`) produced expected=selected=executed `com.example.modulea.FooTest#foo` and excluded unaffected `FooTest#notSelected`. The oracle was fixed before scheduling; reconciliation normalizes the selector declared-signature and target-qualified plan to Maven's enforceable class/base-method identity, as required by the contract. A controlled `SCMRevisionAction` was used; GitHub webhook and PR discovery are outside this proof. Final HPI SHA-256: `38c8955ab870202d69b13af9865ebf69e5c5c2c083f95c84374dafd28e1b24c0`.

The entrypoint retains packaged supplied-plan, NONE, RUN_ALL, checksum/contract failure, cache corruption/upgrade/restart, and direct-selector-without-inventory checks. Direct invocation without authoritative inventory must produce safe `RUN_ALL` and is not selective success.

The final Java-21 Docker run passed all acceptance checks: Gradle selected 2/13, NONE executed 0, and direct no-inventory fallback executed 13/13; Maven selected 2/12, NONE executed 0, Failsafe executed the exact one integration test, and direct no-inventory fallback executed 12/12. Contract/checksum failures stopped before body execution, corrupt caches were replaced, valid caches were reused across controller/agent restarts, and a fresh agent cache was repopulated. The task-owned containers and volumes were removed after the run.

## Final result

EI-15: **PASS**

- Repositories: STP initial `7bdb53c1`, final `a4c4353`; Jenkins initial `955b12d`, final `608b3df`.
- STP commits: `a4c4353` (EI15-02). Jenkins commits: `a49b4e8` (EI15-04), `874def4` (EI15-11/12/17), `a6b0fb4` (EI15-05), `6428f9d`, `8e9b57a`, `608b3df` (EI15-18/21/22).
- Schema and identity boundaries: PASS. Artifact compatibility and clean packaging: PASS for the documented scope.
- Ingress/failure policies, lifecycle/setup attribution, selector-to-execution correctness, Maven/Gradle supported edges, publication lifecycle, promised determinism, and context propagation: PASS for the documented scope and exact evidence named above.
- Representative resources: PASS; 4,158,128-byte map, 1,905 mapped/1,993 inventory identities, decode 214 ms/~74.9 MiB, join 370 ms, select 470 ms under the recorded limits.
- Current documentation/matrices: PASS. Packaged smoke: PASS. Required regressions: PASS.
- EI-13/EI-14 evidence retained: yes; EI-14 replaces aborted-invocation accounting and unknown-target evidence only. No large unchanged run was repeated.
- Docker used: YES. External Jenkins used: YES (local Docker controller/agent deployment). Push performed: NO.
- Release readiness: **NOT READY** for publication because signing/distribution and the Jenkins source-repository LICENSE/NOTICE owner/legal disposition remain external release prerequisites. The reviewed documented runtime scope is validated.
- Complete logs: `EI-15/evidence/complete-execution-logs/`; final packaged run: `10-packaged-docker-smoke-java21.log`. Earlier numbered logs are retained failed-attempt evidence.
