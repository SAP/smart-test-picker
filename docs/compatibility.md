# Current compatibility contracts

Artifact versions and wire versions are independent. All STP-owned modules currently use
`0.3.0-SNAPSHOT`.

| Contract | Version | Current use |
| --- | ---: | --- |
| Logical coverage map/fragment | 2 | Logical test identity without build target; retained Gradle/Maven/CLI flows |
| Executable coverage map/fragment | 3 | Canonical build target plus logical identity; multimodule/multitarget Jenkins mapping |
| Executable head inventory | 1 | Exact revision and target-qualified expected identities |
| Executable shard assignment | 1 | Exact revision, shard and target-qualified identities |
| Logical/executable execution evidence | 1 / 2 | Identity accounting for schema-2/schema-3 mapping; absorption audits distinguish unobserved owners |
| Execution plan | 1 | `SELECT` or `RUN_ALL`, consumed by Gradle/Maven adapters |
| Jenkins adapter contract | 1 | Exact equality for HPI-bundled adapters |

Schema compatibility uses explicit equality, never numeric less-than comparison. Logical schema 2
cannot recover execution targets and is not silently upgraded to schema 3. The explicit selector may
conservatively project a validated executable map to logical impact analysis and then expand selected
logical identities to all exact matches in the executable head inventory; that is selection routing,
not map conversion.

The supported Java baseline is 17; all modules use the Java 17 API boundary even when built on a newer JDK.
Gradle inventory/mapping accepts 8.0 to below 10.0, with differentials on 8.14, 9.5.1, 9.7.1 and 9.9.1.
Maven schema-v3 inventory and preparation require Maven 3.9 or later; qualification uses 3.9.15 and
Maven 4 remains untested. Surefire/Failsafe 3.2.5, 3.5.6 and 3.6.1 are qualified; 3.0.0 and 3.1.2 have
defaults-only probes, and 2.22.2 is rejected. A supported version does not remove the topology and
provider limitations in the module READMEs.

Both tools default to ASM for schema-v3; Maven schema-v1/v2 defaults to JACOCO. Both tools apply effective provider/task filters to their
v3 inventories, reject widening assignments and partial overload assignments, and share completed-ASM
assignment absorption. JaCoCo mapping isolates build-tool-managed destinations and skips project
coverage consumers; ASM coexistence leaves qualified project JaCoCo results intact. Schema-v2 paths
do not inherit all schema-v3 guarantees.

This repository does not qualify a current Jenkins integration runtime. Its separate repository owns
its installation and compatibility requirements.
