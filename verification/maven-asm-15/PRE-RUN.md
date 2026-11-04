# MAVEN-ASM-15 pre-run measurement contract

Source: research/asm-codex at e606140a09f1d92cd83da191b8c71a575c2b0ae9.
PetClinic: 88e37c15cf6fc8490b01bc3e8e2c800cec1ac272, unchanged ASM-10 H2
69-test scope. No production, subject-source, evaluation-harness or paper edits.

First analyze the existing ASM-14 records, without new runs. Reconstruct the
sequential leaf sequence from top-level Surefire Running records and testcase
order in each corresponding XML (nested classes remain in their enclosing
report). Check this against the declaring-class orders in differential.json.
Use raw-session-derived method sets, already checked against production fragments.

A changed directed adjacent boundary is an ordered pair present in at least one
but not all orders. Report every such pair, per-order symmetric method-set
difference and detectability. Also distinguish actual relative-order reversal
from adjacency replacement. Pair-level detectability requires a nonempty
symmetric difference; edge-level detectability additionally requires that the
particular suspect method be in that difference. Count distinct suspect edges,
not repeated pair occurrences. Report incident-pair power and the stricter
changed-successor power separately: bleed into the next test is directional.
An unchanged internal successor remains a blind spot even if its class moves.

Before mapping permutations, inspect the actual Surefire provider and run two
configuration-channel controls: reverse Surefire order with Jupiter ClassName;
alphabetical Surefire order with Jupiter Random. Keep MethodName fixed. Use
actual top-level execution order, not XML filenames, to identify the controlling
channel. These controls are not attribution evidence. If neither channel can
vary class order, stop and report that result, without subject modifications.

Then use three genuinely different deterministic class orders via the effective
channel (alphabetical, reverse alphabetical, fixed-seed random if Surefire
controls it). MethodName and nested ClassName remain fixed. Same reused single
fork; Jupiter parallel disabled. No introduced observers, latches or classes in
the subject. All six mapping runs start from fresh clones and use agent 0.8.15
and production core/report 0.8.13 for JaCoCo; ASM runs disable JaCoCo attachment.

Reuse ASM-14 Differential.java's exact edge/owner comparisons and raw-session
oracle checks, adapting only configuration validation and class-sequence checks.
Keep setup scopes and raw setup sessions separate from leaf method edges.

Classification rules are unchanged from ASM-14:
(a) static initializer or positively established class-init-only code, not any
constructor by default;
(b) positively established once-per-JVM/context bootstrap, not first position
alone;
(c) an ordinary application method with an ordinary caller that varies and is
not established as (a) or (b). A category-(c) mover still requires causal evidence
before calling it bleed rather than state/cache-dependent actual execution.

Report ASM variation first if present. Report all movers, or an empty ledger,
alongside boundary/edge power. A finite null differential cannot establish
universal temporal isolation. Do not infer completion-before-response merely
from stable set-valued coverage; work before the test finishes can be in-window
even after the response. No baseline suite reruns absent production changes.
