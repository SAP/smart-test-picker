# TASK70 — cross-build/cross-agent remote-storage E2E

## Goal

TASK70 validates authenticated `RAW_HTTP` storage through the public Jenkins steps across independent builds, agents, workspaces, and genuine concurrent races. Baselines were main STP `72b691c1d9fb272e72c86d25f37d7aac69ffffa4` and Jenkins plugin `1ced0f59439c8fa0727f8b065dc65dc770cca5d9`.

## Starting state

TASK68 already proved the neutral RAW transport, real Nexus, immutability, structured pointers, exact/latest lookup, stale/divergent protection, integrity failures, and Nexus restart persistence. TASK69 already proved public configuration, Jenkins Credentials API resolution, authenticated public publish/lookup, controller-side execution and single-controller coordination. TASK70 adds physical build/agent/workspace isolation and real concurrent public builds. Nexus restart was not repeated; FILE received the bounded existing automated regression.

## Topology and authentication

The isolated Compose topology was `stp-plugin-controller`, `stp-plugin-agent-1` (`stp-map-agent-1`), `stp-plugin-agent-2` (`stp-map-agent-2`), and `stp-plugin-nexus` 3.96.0. Nexus hosted RAW repository `stp-coverage-maps` disabled anonymous access. Jenkins persisted only `credentialId=task70-nexus-storage`; generated passwords existed only during provisioning/rotation commands.

All primary operations used `stpPublishCoverageMap` or `stpLookupCoverageMap`. Backend/API access was used only to provision Nexus and inspect final object paths/digests.

## Production correctness defect and fix

The first R1→R2 inspection exposed a genuine defect: controller-side `RawHttpStorageBridge` launched `git merge-base` in the controller working directory, although the authoritative Git graph exists in the agent workspace. TASK69's single-revision flow did not invoke ancestry and therefore could not expose it.

The minimal fix supplies the runtime with an ancestry callback that executes `git merge-base --is-ancestor` through the publishing build's `Launcher` in its `FilePath` workspace. Evaluation still occurs while holding the same controller lock. A focused runtime test proves the supplied order is used. Opt-in validation diagnostics record a SHA-256 of the lock key, build ID, operation, event, sequence, and timestamp. A disabled-by-default before-pointer hook created the timing/failure windows without weakening normal publication.

## Fixture graph

The full public isolation proof used the existing tiny Gradle fixture. R1 was `02f1f90fa405a24ac00c63533d43267d02423e1a`; R2 was its child `454dd0416d76b62ea26f1f3304cea090d7d33ab8`.

Race builds used validator-approved small complete schema-v2 `PUBLISHED` fixtures and this deterministic graph:

```text
R1 abfe95ce8eeaa18abff0239c0687b700b25369f6
 |
R2 18f366c5995071569c716c95ac26d3975656c1c7

R1 -- D1 26d73fde4b53214d5777dde1d2dd922c245ece44
  `-- D2 09dca36b5fecc05d19680a4e7bd193948942a8d2
```

Every scenario used a unique project name. The conflicting R4 fixture used two separately valid complete maps for the same revision solely to exercise storage immutability.

## Cross-build independence and R1 → R2

Producer `stp-task70-publish-a1#3` ran on agent 1 at `/home/jenkins/agent/workspace/stp-task70-publish-a1`, published R1 with SHA-256 `ef126495faacaee701476bf67cd7e9cb45500c2ce0abdcf0b8aeda911fe8f541`, and deleted its workspace. Consumer `stp-task70-lookup-a2#1` ran on agent 2 at `/home/jenkins/agent/workspace/stp-task70-lookup-a2`, independently resolved the credential, and materialized exactly those bytes. Its log explicitly records `storageType=RAW_HTTP` and `localFileStoreUsed=false`.

Publisher `stp-task70-publish-a2#1` then published R2 on agent 2. Post-rotation and post-restart lookups returned R2 with SHA-256 `974085a56025bd38d9517adca67fb3a986410df4d43225a2db3d9e83926dd750`. Nexus inspection found exactly one canonical object for each revision; R1 remained unchanged and available.

## Concurrency results

| Scenario | Build A | Build B | Different agents | Real overlap | Expected/final pointer | Observed outcome |
| --- | --- | --- | --- | --- | --- | --- |
| cross-build lookup | publish-a1#3 R1 | lookup-a2#1 | YES | NO | R1 | FOUND |
| late old | fast-a1#2 R1 | fast-a2#1 R2 | YES | YES | R2 | `POINTER_UPDATED`, then `STALE_POINTER_UPDATE` |
| same revision identical | fast-a1#3 R3/X | fast-a2#2 R3/X | YES | YES | R3 | `STORED`; `ALREADY_EXISTS_IDENTICAL` / `POINTER_ALREADY_CURRENT` |
| same revision conflict | fast-a1#4 R4/X | fast-a2#3 R4/Y | YES | YES | winner X | loser `IMMUTABILITY_CONFLICT` |
| ancestor race 1 | fast-a1#5 R5 | fast-a2#4 R6 | YES | YES | R6 | updated/stale |
| ancestor race 2 | fast-a1#6 R5 | fast-a2#5 R6 | YES | YES | R6 | updated/updated |
| ancestor race 3 | fast-a1#7 R5 | fast-a2#6 R6 | YES | YES | R6 | updated/stale |
| ancestor race 4 | fast-a1#8 R5 | fast-a2#7 R6 | YES | YES | R6 | updated/updated |
| ancestor race 5 | fast-a1#9 R5 | fast-a2#8 R6 | YES | YES | R6 | updated/stale |
| divergent revision | fast-a1#11 D1 | fast-a2#9 D2 | YES | YES/controlled | D1 | `POINTER_ALREADY_CURRENT`; `POINTER_CONFLICT` |
| consumer during publish | lookup-a2#3 | fast-a1#16 R8 | YES | YES | R8 | verified R8, never partial |

The late OLD build began first, NEW published first, and OLD then produced `STALE_POINTER_UPDATE`. Public pointer statuses are successful storage facts printed by the controller-side runtime; `IMMUTABILITY_CONFLICT` is surfaced as a failing Pipeline step containing the typed status.

## Publication failure and coordination

For project `task70-failure-window`, fast-a1#14 established R8. Fast-a2#10 stored R9 and the controlled hook failed before pointer update. Nexus contained R9's immutable object, while lookup-a1#1 still returned R8. The prior pointer was not advanced or damaged.

Lock traces for overlapping builds use the same controller process and key digest. In the identical race, build a2 logged `waiting` while a1 was `entered`, and a2 entered only after a1's `released`. The delayed consumer likewise waited from 05:09:14.578Z until publisher release at 05:09:16.663Z. No same-key critical-section intervals overlap. This proves the supported one-controller topology only; lock state intentionally resets on restart, and neither multi-controller support nor remote CAS is claimed.

## Credential lifetime, restart, and secret safety

Producer and consumer logs independently record `credentialResolved=true` with the same credential ID. After Nexus password rotation, lookup-a2#4 with the old Jenkins secret failed as `AUTHENTICATION_FAILED`; after updating the Jenkins credential, lookup-a2#5 succeeded. Thus resolved secret material was not cached across builds.

After a clean Jenkins restart, lookup-a2#6 succeeded with the persisted credential ID and remote R2. A clean shutdown leaves no surviving build critical section; crash-safe distributed locking is not claimed. Nexus restart persistence is already proven by TASK68 and was not repeated.

A final runtime-generated marker scan covered controller logs, both agent logs, and repository files and reported `secretLeakDetected=false`. Evidence contains no password or marker.

## Production-code assessment and validation

Production code changed only for the ancestry correctness fix and its bounded disabled-by-default validation observability. No storage architecture, item-8 policy, cloud backend, SCM/PR behavior, or multi-controller CAS was added.

- Mapping runtime: 30 tests, 3 opt-in Nexus tests skipped, PASS.
- Jenkins plugin: 63 tests, PASS.
- Packaging and adapter integrity: PASS.
- FILE regression: existing focused automated tests in the full suites, PASS.

## Remaining item-7 work

Remote correctness E2E is DONE. Item 7 remains IN PROGRESS only for TASK71, the final storage-and-credentials closure audit. Item 8 remains NOT STARTED.
