# Current development state

- Authoritative development branch: `research/asm-codex`
- Recommended local checkout: `/Users/d061177/work/asm-poc/codex-worktree`
- Unified STP artifact version: `0.3.0-SNAPSHOT`
- Jenkins integration repository: `https://github.com/ljubisap/stp-jenkins-plugin-poc`, branch `ei13/maven-distributed-selection`
- EI-19 packaging source: STP `5db62c6294c8e3f12f9cfb274a25044ce2f73b7b`; Jenkins plugin `4bc3a6b033451bd4aa706ba855f5caebd62efd64`
- Exact validated HPI: `/Users/d061177/work/stp-jenkins-plugin-poc/verification/ei19/smart-test-picker-0.3.0-SNAPSHOT.hpi`
- HPI SHA-256: `879f7d75733092fcd32655931b38bb70289f3ef7d3bc0addb9d90e6da003e9e1`

Verify the shared STP version and generated publication POMs with:

```console
./gradlew verifyStpVersionConsistency
```

The EI-19 HPI above was installed byte-for-byte in Docker Jenkins and passed the
maintained production Maven workflow plus the Maven and Gradle fixture suites.
Its full component inventory, resolved third-party dependency tree, build IDs,
and checksum evidence are in the Jenkins repository under `verification/ei19/`.
Third-party versions retain their declared upstream versions; schema versions
2/3, execution-plan version 1, and adapter contract version 1 remain independent
of the unified STP artifact version.

See `docs/artifact-versioning.md` for the version ownership and update process.
