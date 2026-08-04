<!--
SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
SPDX-License-Identifier: Apache-2.0
-->

# PetClinic runtime-dependency spike fixture

This directory contains only the reproducible experiment contract for the
PetClinic ASM spike. It is intentionally absent from `settings.gradle`, has no
build descriptor, and is not a publishable Smart Test Picker artifact.

- `experiment-manifest.yaml` is the machine-readable target and expectation lock.
- `baseline-results.yaml` records the agent-free run made on 2026-08-02.
- `metrics-template.yaml` is copied once per future measured configuration.

The current Spring Data acceptance remains a separate, unpublished external
reproduction. This command builds the required STP artifacts, clones PetClinic
when absent, checks out commit `88e37c15cf6fc8490b01bc3e8e2c800cec1ac272`,
runs Owner/Vet with both agent orders and disabled mode, normalizes the output,
and validates repository counts, one-execution cache semantics, advisor audit,
contamination, order stability, and JaCoCo coexistence:

```bash
ruby stp-petclinic-spike/spring-data-acceptance/run_acceptance.rb
```

PetClinic source and `pom.xml` are never modified. Raw outputs are written to
`stp-petclinic-spike/spring-data-acceptance/raw/` and are ignored; reviewed
normalized evidence remains beside the scripts.

The reproduction expects Java 17+, PetClinic/Spring Boot/Spring Data 4.1.0,
Spring Framework 7.0.8, Maven Wrapper 3.9.16, and JaCoCo 0.8.15. The STP JSON,
audit, inner-execution, and JaCoCo files are created per run in `raw/`; the
deterministic reviewed result is
`spring-data-acceptance/normalized-acceptance.json`. Environment details and
the exact six-run matrix are recorded in
`../docs/spikes/spring-data-petclinic-acceptance-results.md`.
