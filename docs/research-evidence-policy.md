<!-- SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors -->
<!-- SPDX-License-Identifier: Apache-2.0 -->

# Research evidence policy

Git stores research claims, compact evidence, and reproducibility instructions; it does not serve as bulk experimental artifact storage.

## Keep in Git

Keep analysis and reproduction scripts, experiment configuration, small manifests, target lists, compact summaries, causal classifications, presence vectors, bounded targeted traces, final reports, canonical inventories, checksums, and small representative evidence required to audit a claim.

Artifacts up to 1 MiB are generally acceptable. Artifacts from 1 to 5 MiB require an individual review and must provide unique canonical or causal value. For example, the bounded ROUND 10 per-hit trace and ROUND 13 targeted runtime trace are retained because they contain direct observations behind causal conclusions and are not full-suite execution maps.

## Exclude from Git

Do not commit full raw agent maps, duplicated test-result directories, Gradle binary test results, generated build reports, full JFR recordings unless uniquely indispensable, temporary runtime logs without unique evidence value, duplicated snapshots, temporary subject checkouts, `build/`, or `.gradle/` output.

Files over 5 MiB are excluded by default. An exception requires an explicit allowlist entry and justification. Files over 20 MiB must not be retained unless they are indispensable and no compact or reproducible substitute exists.

Removed artifact provenance belongs in `docs/removed-research-artifacts.md`. Before adding evidence, run:

```sh
scripts/check-large-files.sh
```

The check rejects tracked regular files larger than 5 MiB unless explicitly allowlisted in the script.
