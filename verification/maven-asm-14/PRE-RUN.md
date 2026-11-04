# MAVEN-ASM-14 measurement contract and classification rules

Source HEAD: 9faae9b52432db44c8d3d72fc7b91fa8fad6d36d, research/asm-codex.
Subject: unchanged PetClinic 88e37c15cf6fc8490b01bc3e8e2c800cec1ac272,
the ASM-10 H2 scope of 69 tests. No new subject classes, agent observer, latch,
test edits or evaluation-harness changes.

Orders configured using built-in JUnit MethodOrderers: MethodName, Random with
seed 8675309, Random with seed 20261104. No reverse custom orderer is introduced.
Surefire class order is alphabetical; nested class order is ClassName. One reused
fork; Jupiter parallel execution explicitly disabled. Testcase order emitted in
each Surefire XML report establishes the measured per-class positions, compared
across collectors; those positions are not mislabeled as a global callback trace.

Use the same agent version 0.8.15 and STP core/report 0.8.13 as ASM-10/13. ASM-only
runs disable JaCoCo attachment. The independent ASM-13 SessionOracle uses core
0.8.15 against every retained raw JaCoCo session, including setup sessions; its
leaf tables must agree with the production fragment's leaf methods before use.

Definitions before analysis:

- Stable edge: exact (executable test identity, descriptor-qualified method)
  present in all three order runs of one collector.
- Variable edge: present in the union but not the intersection. This includes
  appearance/disappearance and is not automatically a transfer between owners.
- Owner-set change: a method whose set of leaf owners differs across runs. Report
  methods, variable edges and per-run ownership separately. A positional pattern
  is tested within the declaring test class, with explicit rank/identity data.
- Keep setup coverage separate; do not expand class-level scopes into invented
  method edges. ASM has no JaCoCo sessions: its leaf method table comes from the
  production schema-v3 fragment, with setup scopes and diagnostics retained.

Classification rules, applied to every variable edge/method:

(a) Static initializer or code positively shown by source/bytecode to be reached
only during class initialization. Do not classify arbitrary constructors here.
(b) Framework/container bootstrap executed once per JVM/context, supported by
source/call-path evidence or explicit startup records. Merely being first is
not sufficient; ordinary application getters/constructors need causal evidence.
(c) An ordinary application method with an ordinary caller whose leaf edge varies
and is not established as (a) or (b). Identify the method and all affected tests.
This classification does not by itself prove asynchronous cross-boundary bleed:
legitimate shared state, caching or input differences can change execution. If
the differential cannot distinguish these causes, report C and the missing
measurement rather than claim a natural ASM-13 reset-boundary reproduction.

No movers in these finite permutations would mean no observed instability here,
not proof that temporal isolation always holds. If ASM varies, report that first;
ownership does not imply invariant execution in a stateful test suite.
