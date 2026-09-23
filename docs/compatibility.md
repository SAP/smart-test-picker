# Current compatibility contracts

Artifact versions and wire versions are independent. All STP-owned modules currently use
`0.3.0-SNAPSHOT`.

| Contract | Version | Current use |
| --- | ---: | --- |
| Logical coverage map/fragment | 2 | Logical test identity without build target; retained Gradle/Maven/CLI flows |
| Executable coverage map/fragment | 3 | Canonical build target plus logical identity; multimodule/multitarget Jenkins mapping |
| Executable head inventory | 1 | Exact revision and target-qualified expected identities |
| Executable shard assignment | 1 | Exact revision, shard and target-qualified identities |
| Logical/executable execution evidence | 1 / 2 | Exact observed execution for schema-2/schema-3 mapping |
| Execution plan | 1 | `SELECT` or `RUN_ALL`, consumed by Gradle/Maven adapters |
| Jenkins adapter contract | 1 | Exact equality for HPI-bundled adapters |

Schema compatibility uses explicit equality, never numeric less-than comparison. Logical schema 2
cannot recover execution targets and is not silently upgraded to schema 3. The explicit selector may
conservatively project a validated executable map to logical impact analysis and then expand selected
logical identities to all exact matches in the executable head inventory; that is selection routing,
not map conversion.

The supported Java baseline is 17+. The maintained Jenkins POC is tested on Jenkins 2.516.2 with JDK
21 while its plugin and embedded STP bytecode target Java 17. Maven 3.9+ and the project-supported
Gradle wrapper are required by their corresponding integrations.
