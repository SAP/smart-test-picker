# PetClinic readiness and cache experiment

## Revisions and environment

- STP: branch `research/asm-codex-remote`, commit `f80e73289baa68dadc942e629a7d7fcabac7ed5c`; worktree clean in both runs.
- PetClinic: origin `https://github.com/spring-projects/spring-petclinic.git`, commit `88e37c15cf6fc8490b01bc3e8e2c800cec1ac272`; worktree clean in both runs.
- Java: `openjdk version "21.0.12.1" 2026-08-18 LTS` (SapMachine).
- Port: `18080`.
- The experiment used a temporary PetClinic clone at `/tmp/stp-remote-petclinic-fixture`; its source checkout was not changed.

## Commands

Both runs used the same `run-two-jvm-poc.sh`, PetClinic revision, STP revision, port, and request scenario. Only the configured readiness path and output names/directories differed. From the STP checkout root, the commands were:

```sh
PETCLINIC_DIR=/tmp/stp-remote-petclinic-fixture \
PORT=18080 \
PROBE_PATH=/actuator/health \
OBSERVATIONS_NAME=remote-observations-health.json \
SERVER_LOG_NAME=petclinic-server-health.log \
OUTPUT_DIR=/tmp/stp-remote-cache-evidence/run-A \
./stp-remote-agent/petclinic-poc/run-two-jvm-poc.sh \
  > /tmp/stp-remote-cache-evidence/run-A/verifier-output.log 2>&1
```

```sh
PETCLINIC_DIR=/tmp/stp-remote-petclinic-fixture \
PORT=18080 \
PROBE_PATH=/vets \
OBSERVATIONS_NAME=remote-observations-vets-probe.json \
SERVER_LOG_NAME=petclinic-server-vets-probe.log \
OUTPUT_DIR=/tmp/stp-remote-cache-evidence/run-B \
./stp-remote-agent/petclinic-poc/run-two-jvm-poc.sh \
  > /tmp/stp-remote-cache-evidence/run-B/verifier-output.log 2>&1
```

Each run built the standalone agent and PetClinic, started the server JVM, made the configured readiness request without the STP header, ran the JVM B scenario (`/vets` with `vets-A`, `/owners/1` with `owner-B`, and a headerless `/vets`), stopped the server, then verified and saved observations.

## Measured results

Both runs passed the existing verifier. In each run `/vets` was attributed to the Vet route, `/owners/1` to the Owner route, there was no cross-attribution, and only `vets-A` and `owner-B` appeared as observations. The headerless readiness request and headerless scenario request created no attributed observation.

| Run | Probe | `vets-A` methods | `owner-B` methods | Verifier |
|---|---|---:|---:|---|
| A | `/actuator/health` | 16 | 21 | PASS |
| B | `/vets` | 11 | 21 | PASS |

The complete `vets-A` method-set comparison was:

**Only in Run A (`/actuator/health`):**

```text
org.springframework.samples.petclinic.model.BaseEntity#<init>()V
org.springframework.samples.petclinic.model.NamedEntity#<init>()V
org.springframework.samples.petclinic.model.Person#<init>()V
org.springframework.samples.petclinic.vet.Specialty#<init>()V
org.springframework.samples.petclinic.vet.Vet#<init>()V
```

**Only in Run B (`/vets`):** none.

**Common to both:** 11 methods. They are the `BaseEntity#getId` and `BaseEntity#isNew` methods; `NamedEntity#getName`; `Person#getFirstName` and `Person#getLastName`; `Vet#getNrOfSpecialties`, `Vet#getSpecialties`, and `Vet#getSpecialtiesInternal`; `VetController#showResourcesVetList`; and `Vets#<init>` and `Vets#getVetList`.

The PetClinic revision has `@Cacheable("vets")` on `VetRepository.findAll()` and `findAll(Pageable)`. The `/vets` controller calls `findAll()`.

## Conclusion

**CONFIRMED for this controlled setup.** The configured probe path was the only behavior-affecting run setting. With `/actuator/health`, the later `vets-A` observation includes five constructors listed above. With the headerless `/vets` probe first, those five constructors are absent; the other 11 methods are identical. This method-level difference, together with the cacheable `findAll()` path used by `/vets`, supports the hypothesis that the readiness request warms PetClinic's vet cache and changes the later observation. This result does not establish general server-side coverage; it measures request-thread method entries in these two runs.

## Preserved run artifacts

Raw artifacts are kept outside the repository under `/tmp/stp-remote-cache-evidence/`:

- Run A: `remote-observations-health.json`, `remote-observations-health-provenance.txt`, `petclinic-server-health.log`, and `verifier-output.log` in `run-A/`.
- Run B: `remote-observations-vets-probe.json`, `remote-observations-vets-probe-provenance.txt`, `petclinic-server-vets-probe.log`, and `verifier-output.log` in `run-B/`.
