# Coverage-map follow-up backlog

This document records current implementation requirements established after the original coverage-map contract. Detailed
experimental evidence remains with the POC that produced it; this file contains only the consequences
for the corresponding backlog items.

## Task 5c — Coverage runtime and fragment production

Status: **IN PROGRESS — ASM collector and schema-v2 fragment production foundation implemented**

TASK 24 established direct ASM observation projection and TASK 25 made schema v2 authoritative.
The current foundation proves ASM direct collection, descriptor-aware production method identity,
distinct overloaded test identity, JUnit invocation collapsing, explicit revision/shard binding,
deterministic fragment production, collector integrity gating, bounded setup ownership,
single-versus-multi collector setup equivalence, parallel container ownership, and a real
javaagent-to-schema-v2-fragment end-to-end path.

The three-agent Spring PetClinic POC exposed an unsafe behavior in the current experimental collector.
Run-wide setup attribution can create false setup-to-test relations because it combines every setup
class observed in a test JVM with every test container observed in that JVM.

The PetClinic baseline produced three false setup edges that were absent from the distributed result:

- `CacheConfiguration -> ValidatorTests`
- `CrashController -> ValidatorTests`
- `WelcomeController -> ValidatorTests`

Task 5c attributes setup coverage to the actual JUnit lifecycle/container scope and does not derive
affected tests from a run-wide Cartesian product. Schema-v2 `CONTAINER` and `NESTED_CONTAINER` scopes
represent this bounded attribution. `AfterAll` cannot leak to the last leaf.

Additional acceptance criterion:

```text
single-shard and multi-shard collection must produce
semantically identical setup coverage
```

Remaining 5c work is framework-specific inherited/shared/async setup detection, integration with
Gradle and Maven, and inventory-discovery boundaries where owned by 5c. Fragment merge,
publication, and global completeness remain 5d; selector fallback policy remains 5b. Task 5c is not
DONE, and neither 5b nor 5d status changes here.

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
