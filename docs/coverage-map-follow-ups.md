# Coverage-map follow-up backlog

This document records implementation requirements established after coverage-map schema v1. Detailed
experimental evidence remains with the POC that produced it; this file contains only the consequences
for the corresponding backlog items.

## Task 5c — Coverage runtime and fragment production

Status: **NOT STARTED — blocking collector finding recorded**

The three-agent Spring PetClinic POC exposed an unsafe behavior in the current experimental collector.
Run-wide setup attribution can create false setup-to-test relations because it combines every setup
class observed in a test JVM with every test container observed in that JVM.

The PetClinic baseline produced three false setup edges that were absent from the distributed result:

- `CacheConfiguration -> ValidatorTests`
- `CrashController -> ValidatorTests`
- `WelcomeController -> ValidatorTests`

Task 5c must attribute setup coverage to the actual JUnit lifecycle/container scope. It must not derive
affected tests from a run-wide Cartesian product. The existing schema-v1 `SetupScope` model appears able
to represent the required narrower attribution; the POC did not establish a need for another schema.

Additional acceptance criterion:

```text
single-shard and multi-shard collection must produce
semantically identical setup coverage
```

## Task 5d — Fragment merge, publication, and Jenkins orchestration

Status: **NOT STARTED — POC proven**

The three-agent Spring PetClinic POC proved the mechanism for:

- independent expected inventory before collection
- deterministic sharding
- parallel execution across three isolated Jenkins agents
- sequential tests inside each collector context
- workspace-relative fragment production
- fragment transport using Jenkins stash/unstash
- fragment join on an explicit agent
- completeness validation against expected tests and shards
- exact revision consistency
- detection of missing, duplicate, and unexpected join input

This evidence does not complete Task 5d. Production storage, retention, lookup, scheduling, publication
policy, failure recovery, and CI-wide configuration remain unimplemented.

## Canonical POC conclusion

> Distributed mapping itself did not introduce coverage loss. The only semantic difference was caused
> by an existing run-wide setup attribution defect in the collector.

The evidence is documented in `stp-jenkins-plugin-poc/docs/coverage-mapping-poc.md`: both complete maps
contained the same 58-test inventory, 54 mapped tests, four explicitly skipped tests, 338 test-to-class
edges, and 1,045 test-to-method edges. Setup comparison alone differed: 304 baseline edges versus 301
distributed edges.
