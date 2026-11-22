# Historical validation: join-fragments-paired-directory.md

This is retained validation evidence, not a current setup instruction. See the
[current contract](../join-fragments-paired-directory.md).

## Existing-option reference gate

A fresh packaged-CLI join of the retained reference Maven fragment at
`88e37c15cf6fc8490b01bc3e8e2c800cec1ac272` used the existing options:

```bash
java -jar smart-test-picker-cli/build/libs/smart-test-picker-cli-0.3.0-SNAPSHOT-all.jar \
  join-fragments \
  --fragments-dir verification/stp-join-cli-01/inputs/fragments \
  --assignments-dir verification/stp-join-cli-01/inputs/assignments \
  --head-inventory verification/stp-join-cli-01/inputs/head-inventory.json \
  --output verification/stp-join-cli-02/raw/map.json
```

Result: exit zero, **69 mapped, 0 unmapped, 10 setup scopes**, 21 distinct classes, 89 distinct methods,
186,853 bytes. Decoded JSON compared against the STP-JOIN-CLI-01a map differs only in `generatedAt`
and its dependent checksum. Coverage, unmapped entries, setup scopes, completeness, statistics,
lifecycle and provenance are identical. This is a fresh join of retained collection evidence, not
a new collection or performance run.

- Unchanged input fragment SHA-256:
  `0ecdbed9d086835971a9c69e3a49b6977ca24c42058ce2bf3618d8fc1820b8b9`.
- New embedded map checksum:
  `sha256:9250cf96cb124cd00ff21c8f5cbfd6e1eb19148e531d957d6a845db200fd55e7`.

As an additional packaged-CLI smoke check, the exact retained fragment and assignment bytes were
copied to `verification/stp-join-cli-02/paired-input/fragment-local.json` and `tests-local.json`.
No inventory was regenerated and no new test-list discovery was performed.

```bash
java -jar smart-test-picker-cli/build/libs/smart-test-picker-cli-0.3.0-SNAPSHOT-all.jar \
  join-fragments --dir verification/stp-join-cli-02/paired-input --expected-shards 1 \
  --output verification/stp-join-cli-02/raw/paired-map.json
```

This also produced **69 mapped, 0 unmapped, 10 setup scopes**, and its decoded map is identical to
the existing-option output except for `generatedAt` and checksum. Paired-mode checksum:
`sha256:b19276a21b8916c4bc39918653199a7e238130635aefcc3633e2fe236187fcba`.
This verifies both CLI entry paths on retained evidence; it is not an external platform run.

## Eight-module gate

Fresh command: `./gradlew test --rerun-tasks --offline --continue`.
**BUILD SUCCESSFUL in 9m 57s**, all 69 actionable tasks executed. Fresh JUnit XML counts:

| Module | Tests | Passed | Skipped | Failures/errors |
|---|---:|---:|---:|---:|
| smart-test-picker-common | 342 | 331 | 11 | 0 |
| smart-test-picker-core | 29 | 24 | 5 | 0 |
| smart-test-picker | 58 | 58 | 0 | 0 |
| smart-test-picker-maven | 161 | 161 | 0 | 0 |
| smart-test-picker-cli | 95 | 95 | 0 | 0 |
| stp-runtime | 83 | 83 | 0 | 0 |
| stp-junit-adapter | 21 | 21 | 0 | 0 |
| stp-agent | 52 | 51 | 1 | 0 |
| **Total** | **841** | **824** | **17** | **0** |

This is 24 new tests above the merged main base's 817. The runtime count is 83 because that base
already includes the earlier suspension API change; no runtime code changed here. Console evidence
is retained locally at `verification/stp-join-cli-02/raw/full-suites.log`. `git diff --check` and the
tracked-file 5 MiB check passed. The joiner, publication, Maven and agent sources are unchanged.

No CI or external platform run is part of this task. The new PR is to be opened, not merged.
