# STP and Jenkins release checklist

- [ ] Freeze STP, selector-source, Jenkins, adapter, Gradle bundle, and fixture revisions; record compatibility versions and supported upgrade/downgrade paths.
- [ ] Build from clean checkouts with initially empty isolated Maven and Gradle caches; record bootstrap order and repository inputs. Reject machine-global unpublished artifacts.
- [ ] Verify every artifact coordinate, embedded resource path, source provenance, SHA-256, nested HPI content, and generated manifest.
- [ ] Verify Java 17 bytecode for STP/HPI, Java 8 bytecode for the Maven execution adapter, and the tested Jenkins/JDK runtime.
- [ ] Inspect published POM/Gradle dependency surfaces; confirm Maven/Jenkins provided dependencies do not leak and ASM/Jackson are relocated where required.
- [ ] Exercise JUnit service discovery and host engine/launcher compatibility through packaged artifacts; do not relocate service APIs without this test.
- [ ] Inspect root LICENSE/NOTICE, shaded-JAR notices, and HPI `WEB-INF/licenses.xml`; resolve omissions before release.
- [ ] Run STP full unit/functional tests, Jenkins 96-test suite, standalone mapping-runtime tests, adapter reproducibility check, HPI packaging inspection, and maintained packaged smoke.
- [ ] Run Docker Jenkins only when deployment/remoting/storage behavior changed; identify retained external evidence otherwise.
- [ ] Reconcile expected plans/assignments with raw Gradle JUnit and Maven Surefire/Failsafe reports; explicitly cover NONE, FULL_SUITE, and stale-filter transitions.
- [ ] Verify incompatible schemas/protocols, revision/shard/target mismatches, missing/duplicate/corrupt artifacts, incomplete joins, and invalid-publication absence.
- [ ] Verify immutable object/conflict/stale-writer/pointer-integrity/cache-invalidation behavior for every supported backend; do not call RAW_HTTP atomic.
- [ ] Repeat promised determinism checks and document intentionally variable metadata.
- [ ] Review current state, usage, examples, configuration, troubleshooting, known limitations, and historical notices for consistency.
- [ ] Scan maintained tracked files and generated evidence for credentials, tokens, authorization headers, and signed URLs; rotate any exposed credential separately.
- [ ] Confirm no disposable evidence/runtime file is a build input and all task-owned containers/worktrees are clean or explicitly accounted.
- [ ] Record artifact checksums, complete logs, test counts, Docker/external-Jenkins use, remaining blockers, and no-push status in the final verification report.

