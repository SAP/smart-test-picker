# Current development state

- Authoritative development branch: `research/asm-codex`
- Recommended local checkout: `/Users/d061177/work/asm-poc/codex-worktree`
- Unified STP artifact version: `0.3.0-SNAPSHOT`
- Jenkins integration repository: `https://github.com/ljubisap/stp-jenkins-plugin-poc`, branch `ei13/maven-distributed-selection`
- EI-17 integration checkpoint: STP `24b598a6587b0a7d42f48ce2b25b500be23df9b4`; Jenkins plugin `b18cd2632c6a232256c9578352d0f30b3ef3fb4e`

Verify the shared STP version and generated publication POMs with:

```console
./gradlew verifyStpVersionConsistency
```

The latest maintained production runtime evidence is recorded in
`EI-16/verification-report.md` and `EI-16/evidence/complete-execution-logs/`.
It validates the EI-16 packaged runtime. The later EI-17 version-only rebuild
was not independently exercised by that runtime smoke, so its HPI must not be
described as the smoke-tested binary.

See `docs/artifact-versioning.md` for the version ownership and update process.
