# MAVEN-ASM-15 differential detection power and class order

## Result

**C for the full suspect population: no movers, but not every suspect edge was
on a detectable permuted boundary.** All six class-order mapping runs passed
69 tests and exact production accounting. ASM and JaCoCo leaf attribution and
setup scopes were invariant, despite genuinely different class sequences.

The new runs provide edge-specific changed-successor power for **52 of 74**
suspect edges, including all 30 on the single-method concurrency class. They do
not exercise the fixed `ownerDetails -> ownerList` or HTML-crash -> JSON-crash
boundaries. Thus the negative result is informative for the exercised edges,
but does not establish that all PetClinic HTTP controller work completes inside
its requesting test's window. No natural bleed was observed; the remaining
question and the measurement needed to close it are stated below.

## Measurement scope

STP source: `research/asm-codex` at
`e606140a09f1d92cd83da191b8c71a575c2b0ae9`. Subject: unchanged PetClinic
`88e37c15cf6fc8490b01bc3e8e2c800cec1ac272`, the ASM-10 H2 scope of 69 tests.
The existing measurement POM overlay excludes only the MySQL/PostgreSQL classes;
no test/production source, original POM, observer or latch was added or changed.

The [pre-run record](PRE-RUN.md) fixes the measurement and classification rules.
The [channel decision](CHANNEL-DECISION.md) records the experimentally established
class-order channel before the mapping permutations. This is local Maven, not
Jenkins, and does not change a collector, guard, evaluation harness or paper.

## Detection power of the retained ASM-14 runs

[Power.java](Power.java) reconstructs each sequential test sequence from the
top-level Surefire `Running` records and the testcase sequence in its XML report.
Nested cases remain in their enclosing XML report. It asserts 69 unique leaves,
checks the projection against ASM-14's `actualOrders`, and checks raw JaCoCo
SessionOracle method sets against the production fragment before measuring power.

The [full pair ledger](raw/asm14-power.json) lists every changed directed adjacent
pair, both identities, its positions and adjacency in each order, the exact
method-set symmetric difference, its size, and detectability. It also records
all five suspect tests' predecessors, successors, method sets and class sizes.
Counts below deduplicate suspect `(test, method)` edges across pair occurrences.

Two distinct notions of permutation are reported rather than conflated:

- **Relative reversal:** a pair is adjacent in at least one run and its two
  identities reverse relative order in another. This directly answers the swap
  question; it does not require them to remain adjacent after reversal.
- **Changed adjacency:** a directed boundary occurs in some but not all runs.
  This also covers replacing a predecessor/successor without reversing the
  relative order of the original two tests. It is useful for delayed-work bleed.

Pair-level detectability requires a nonempty symmetric difference. Edge-level
detectability additionally requires that the particular suspect method lies in
that difference and is present on its suspect owner. Otherwise another unrelated
method can make the pair detectable while the suspect edge remains invisible.
Incident-pair power includes either side of a boundary; changed-successor power
only includes the first test, because bleed into the next window is directional.

| ASM-14 boundary definition | Directed pairs | ASM detectable pairs | JaCoCo detectable pairs | JaCoCo suspect edges detectable at either endpoint | JaCoCo suspect edges detectable at changed successor |
| --- | ---: | ---: | ---: | ---: | ---: |
| Relative reversal | 111 | 90 | 92 | 8 / 74 | 8 / 74 |
| Changed adjacency, including replacement | 166 | 142 | 144 | 39 / 74 | 38 / 74 |

At the coarser pair-only level, **42 of 74 suspect edges sit on a detectable
relatively reversed pair**, all belonging to the two owner tests. Only eight of
those edges are individually distinguishable by that pair's symmetric difference.
Changed adjacencies touch 43 suspect edges at
either endpoint and 42 at outgoing boundaries. Those are **not** 43/42 individually
detectable suspect edges: the edge-specific totals are 39/38 above. All 74
suspect edges are JaCoCo-only in the original ledger; none is present on its
ASM leaf owner, so there is no corresponding observed ASM suspect edge to swap.
ASM pair detectability still describes its own observed coverage sets.

Thus ASM-14 had some power, but its null result was weak for the suspect population:
only eight suspect edges were individually distinguishable on reversed adjacent
pairs, and even
the broader changed-successor analysis left 36 of 74 unexercised.

### The two 21-edge owner tests are not identical

`PetClinicIntegrationTests#ownerDetails` and `#ownerList` each cover 21 JaCoCo
methods, but share only 17: their symmetric difference is **eight** methods.

Details-only methods (under `org.springframework.samples.petclinic`):

- `owner.OwnerController#showOwner(I)Lorg/springframework/web/servlet/ModelAndView;`
- `owner.Pet#getBirthDate()Ljava/time/LocalDate;`
- `owner.Pet#getType()Lorg/springframework/samples/petclinic/owner/PetType;`
- `owner.Pet#getVisits()Ljava/util/Collection;`

List-only methods:

- `model.Person#setLastName(Ljava/lang/String;)V`
- `owner.OwnerController#addPaginationModel(ILorg/springframework/ui/Model;Lorg/springframework/data/domain/Page;)Ljava/lang/String;`
- `owner.OwnerController#findPaginatedForOwnersLastName(ILjava/lang/String;)Lorg/springframework/data/domain/Page;`
- `owner.OwnerController#processFindForm(ILorg/springframework/samples/petclinic/owner/Owner;Lorg/springframework/validation/BindingResult;Lorg/springframework/ui/Model;)Ljava/lang/String;`

Both ASM leaf method sets are empty in these runs. This is a within-collector
power comparison, not a claim that the collectors should have identical edges.

### Which HTTP tests were actually permuted in ASM-14

| Declaring class and method | Methods in declaring class | Global leaf positions in name / random1 / random2 | Within-class order actually changed? |
| --- | ---: | --- | --- |
| `PetClinicConcurrencyTests#testDuplicatePetNameRaceConditionIsBlocked` | 1 | 1 / 1 / 1 | No; impossible within this single-method class |
| `PetClinicIntegrationTests#ownerDetails` | 3 | 3 / 3 / 4 | Yes |
| `PetClinicIntegrationTests#ownerList` | 3 | 4 / 4 / 3 | Yes |
| `CrashControllerIntegrationTests#triggerExceptionHtml` | 2 | 61 / 61 / 61 | No; neither chosen seed reversed this pair |
| `CrashControllerIntegrationTests#triggerExceptionJson` | 2 | 62 / 62 / 62 | No; neither chosen seed reversed this pair |

The unchanged concurrency successor was always `PetClinicIntegrationTests#findAll`.
The unchanged HTML successor was always `triggerExceptionJson`; the unchanged
JSON successor was always `CrashControllerTests#triggerException`. These are
concrete blind spots, not an inference from the number of methods alone.

## Effective class-order channel

Two configuration-only controls each passed all 69 tests without STP collection:

| Surefire runOrder | Jupiter class orderer | Observed top-level order |
| --- | --- | --- |
| `reversealphabetical` | `ClassOrderer$ClassName` | Alphabetical |
| `alphabetical` | `ClassOrderer$Random`, seed 20261104 | Random; starts PetValidatorTests, PetControllerTests, VisitControllerTests |

Commands, effective test properties and actual execution logs:
[reverse control](raw/channel-reverse/), [random control](raw/channel-probe/).
The exact provider's [bytecode](raw/surefire-provider-bytecode.txt) shows both
multi-selector and per-class launcher paths. In this configuration Jupiter's
ordering controls the final sequence, consistent with the multi-selector path.
This is not a universal claim about every Surefire forking configuration.

The mapping runs therefore hold Surefire alphabetical order fixed, and use
Jupiter ClassName, Random seed 8675309, and Random seed 20261104. MethodName stays
fixed throughout. The global class orderer also permutes nested classes; this is
class-order variation, not variation of methods within a declaring class.

## Classification rules and analysis controls

The three classifications remain exactly those of ASM-14:

- (a) Static initializers or positively demonstrated class-init-only code;
  constructors are not automatically in this category.
- (b) Positively demonstrated once-per-JVM/context bootstrap; running first is
  not sufficient evidence.
- (c) Varying ordinary application methods with ordinary callers, not established
  as (a) or (b). Causal bleed still needs distinguishing from cache/state effects.

[Differential.java](Differential.java) reuses ASM-14's edge/owner comparisons and
raw-session agreement checks. Only order-configuration checks, actual class
sequences and global positions are adapted. Every declaring class must retain
the same lexical method order across all six runs. Every collector pair must
execute the same sequence, and the three class sequences must genuinely differ.

An offline positive control swaps distinct method sets between two synthetic
identities with unchanged total counts: the reused set differential must detect
four changed identity-method edges. A negative control verifies that identical
sets have zero swap power. These controls do not alter PetClinic, raw evidence
or an STP execution and are not represented as natural bleed.

## Class-order results and power

| Collector | Alpha edges | Random 8675309 edges | Random 20261104 edges | Stable edges | Variable edges | Methods with changed owners |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| ASM | 1,343 | 1,343 | 1,343 | 1,343 | 0 | 0 |
| JaCoCo | 1,400 | 1,400 | 1,400 | 1,400 | 0 | 0 |

The full identity-method sets, empty mover ledgers, actual sequences and setup
comparisons are in [raw/differential.json](raw/differential.json).
[differential.log](differential.log) records the offline positive/negative control
and the retained-data checks. All 69 identities passed in each run, with no
missing, unexpected, duplicate, unmapped or positively non-executed identities.
The exact identities match the assignment and Surefire reports. Compiled
production class hashes are identical across all six runs.

Each JaCoCo run has 87 retained raw sessions: 69 leaf and 18 setup. The unchanged
SessionOracle's leaf method sets equal production fragments exactly; raw setup
method sets are also invariant. Neither collector's setup scopes changed. No
setup scope was expanded into invented per-test method edges. All mover
categories (a), (b), (c) contain zero methods and zero variable edges, and there
are no unclassified movers. No unsafe-selection example was observed to name.

All three method sequences within each declaring class are identical. The HTTP
tests moved to these actual global leaf positions:

| Test | Alpha | Random 8675309 | Random 20261104 | Detectable suspect edges at any changed cross-class endpoint | At changed successor |
| --- | ---: | ---: | ---: | ---: | ---: |
| concurrency race test | 1 | 5 | 42 | 30 / 30 | 30 / 30 |
| ownerDetails | 3 | 2 | 65 | 0 / 21 | 0 / 21 |
| ownerList | 4 | 3 | 66 | 21 / 21 | 21 / 21 |
| triggerExceptionHtml | 61 | 51 | 50 | 1 / 1 | 0 / 1 |
| triggerExceptionJson | 62 | 52 | 51 | 1 / 1 | 1 / 1 |
| **Distinct suspect edges** | | | | **53 / 74** | **52 / 74** |

For example, the concurrency test's successor changes from `findAll` to
`ClinicServiceTests#shouldAddNewVisitForPet` to
`ValidatorTests#shouldNotValidateWhenFirstNameEmpty`. `ownerList` is followed by
that Validator test, then `VetTests#serialization`, then
`CrashControllerTests#triggerException`. The JSON crash test's successors are
the direct CrashController test, `PetTypeFormatterTests#shouldParse`, and the
ClinicService visit test. These are changed boundaries with real differing
coverage, not class-order properties that failed to affect execution.

The [complete cross-class pair ledger](raw/class-order-power.json) names every
pair and its exact symmetric differences and positions; cross-class includes
different nested declaring classes. Its summary is:

| Definition | Directed cross-class pairs | ASM detectable | JaCoCo detectable | JaCoCo outgoing suspect-edge power |
| --- | ---: | ---: | ---: | ---: |
| Changed adjacency | 41 | 40 | 40 | 52 / 74 |
| Of those, relative order also reversed | 29 | 28 | 29 | 49 / 74 |

For this new changed-adjacency experiment, coarse pair-level and edge-level
suspect totals coincide (53 incident, 52 outgoing); that was not true in ASM-14.
The retained pair ledger is required to distinguish these definitions rather
than claiming one aggregate number measures every failure mechanism.

### What remains undecided

Moving `ownerDetails` from position 3 to 2 to 65 does not change its successor,
`ownerList`. Moving the HTML crash test likewise leaves the JSON crash test next.
These internal boundaries explain the 22 suspect edges not exercised by the
new changed-successor differential. A changed incoming boundary for HTML does
not prove anything about delayed work from HTML entering JSON's window.

Even [combining the old and new changed-successor observations](raw/combined-power.json)
covers only **71 of 74** suspect edges. The three remaining blind edges are:

- `PetClinicIntegrationTests#ownerDetails -> model.BaseEntity#<init>()V`
- `PetClinicIntegrationTests#ownerDetails -> model.Person#<init>()V`
- `CrashControllerIntegrationTests#triggerExceptionHtml -> system.CrashController#triggerException()Ljava/lang/String;`

Production names above have the prefix `org.springframework.samples.petclinic`.
The first two are shared across the relevant old neighboring sets. The two crash
tests have identical singleton JaCoCo method sets, so simply swapping them would
not identify ownership of that shared method. The union of experiments is a
coverage-of-boundaries calculation, not a new combined-factor runtime campaign.

To close this residual question, a separate measurement could correlate actual
server method entry/exit and originating HTTP request with the requesting test's
start/finish and JaCoCo snapshot boundaries, with a positive observation control.
That would distinguish identical-set masking and stable misattribution without
introducing a latch or guessing from method counts. It is not implemented here.
An order differential alone does not timestamp HTTP completion, so the broader
claim that all HTTP work finishes inside the requesting window remains open.

These are structural detection-power checks, not an estimated probability of
detecting a timing-dependent failure. One run per order does not exhaust timing
variation. No production change or conclusion about how many published numbers
need re-deriving follows from this null result.

## Reproduction and environment

The runner is [run.sh](run.sh); [Configure.java](Configure.java) replaces only
the generated measurement overlay's fixed runOrder with a measurement property.
The subject's original POM/source are untouched. The unchanged ASM-10 helper
performs production inventory/assignment/fragment/evidence/join validation; the
unchanged ASM-13 SessionOracle reads retained original execution data and classes.

Actual environment:

```sh
export PATH=/Users/D061177/Programs/apache-maven-3.9.15/bin:$PATH
export ASM15_WORK=/private/tmp/stp-asm15.jn5m01
export ASM15_M2=/private/tmp/stp-asm10.VvlrmI/m2
export ASM15_SUBJECT=/private/tmp/stp-asm10.VvlrmI/subject
export ASM15_GSON=/Users/D061177/.m2/repository/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar
# Run controls first, without collection.
for order in reverse probe; do
  ASM15_CHANNEL_ONLY=true bash verification/maven-asm-15/run.sh asm "$order" \
    > "verification/maven-asm-15/channel-$order-console.log" 2>&1 || exit $?
done
# Run only after establishing the effective channel.
for order in alpha random1 random2; do
  for collector in asm jacoco; do
    bash verification/maven-asm-15/run.sh "$collector" "$order" \
      > "verification/maven-asm-15/$collector-$order-console.log" 2>&1 || exit $?
  done
done
```

Each invocation uses a fresh clone and rejects reuse of its directory. Substitute
fresh disposable locations when reproducing; the paths above record this run.
The existing isolated functional-test STP repository and task-local Maven cache
are prerequisites, not a claim of a cold-cache build.

Maven 3.9.15, SapMachine JDK 21.0.13.0.3, macOS arm64; STP 0.3.0-SNAPSHOT.
JaCoCo agent **0.8.15**, STP production core/report **0.8.13**, unchanged from
ASM-14. The independent SessionOracle uses core 0.8.15 and must match the
production leaf fragment exactly. ASM-only runs disable JaCoCo attachment.
Expanded commands and actual tool/runtime properties are retained per run.
The resolved Spring Boot and Spring Data JPA/Commons versions are 4.1.0,
Surefire is 3.5.6 and JUnit Jupiter/Platform is 6.0.3, as in ASM-14.
[STP artifact checksums](raw/stp-artifact-checksums.txt) are unchanged from ASM-10;
[runtime artifact checksums](raw/runtime-artifact-checksums.txt) also record the
actual local-repository JARs, including the JaCoCo agent/core/report pair.

Recheck the retained evidence without another subject run:

```sh
ASM15_WORK=/private/tmp/stp-asm15.jn5m01 \
ASM15_GSON=/Users/D061177/.m2/repository/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar \
bash verification/maven-asm-15/analyze.sh
```

Raw mapping evidence: [ASM alpha](raw/asm-alpha/),
[ASM random1](raw/asm-random1/), [ASM random2](raw/asm-random2/),
[JaCoCo alpha](raw/jacoco-alpha/), [JaCoCo random1](raw/jacoco-random1/),
[JaCoCo random2](raw/jacoco-random2/). Each includes the command, exit code,
inventory, assignment, fragment, evidence, validated candidate map, reports,
original production classes and collection diagnostics/sessions. All files are
below 5 MiB. The two non-collection channel controls add 138 passing executions
to the 414 mapping test executions; they are not included in the differential.

Baseline suites are not rerun because no production code changes. ASM-14 suite
counts are not presented as new validation. No inference about the number of
affected published results is made, and no evaluation artifact is changed.
The unchanged PetClinic concurrency test still starts its own client threads;
JUnit test execution is sequential, not all application work.

## Local validation

The retained-data analysis rerun, Java helper compilation, shell syntax, report
relative links and task-local file-size checks pass. The measurement contains
no production diff. The repository-wide large-file check flags two pre-existing
historical files: `EI-10/artifacts/published-r0-map.json` (30,045,626 bytes) and
`EI-11/artifacts/deployed-smart-test-picker.hpi` (10,450,073 bytes). Neither is
changed by this task. All newly retained files are below the 5 MiB limit.
