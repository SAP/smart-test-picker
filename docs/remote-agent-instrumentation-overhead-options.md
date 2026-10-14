<!--
SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
SPDX-License-Identifier: Apache-2.0
-->

# Remote STP agent: instrumentation overhead without JVM choke — options A–E

Status date: 2026-10-14. Repository branch at authoring: `feature/asm-codex-remote`.

This note records a production problem observed in SAP Commerce (MCS) and the candidate solutions
with their trade-offs. It is an evidence-and-options record, not a selected design. No agent code
was changed by this note.

## The problem (observed)

The `cxmc-product-pipeline` E2E suite `api_ctof_order_karate` began failing after the Remote STP
agent `includes` were widened from the servlet-filter/processing layer to the full business-code
roots:

```
# worked
includes=de.hybris.platform.processing.,de.hybris.platform.servicelayer.web.
# choked
includes=com.sap.cx.,de.hybris.,com.hybris.,com.sap.smartedittools.,org.apache.ddlutils.
```

Under the wide set, the Hybris bulk ImpEx import (`POST /api/platform/impex/v1/imports`, part of
Karate `base_data_import` test setup) could not return `202 Accepted` within the client's ~51 s
read timeout. The suite failed in `@BeforeAll` setup, so no client correlation manifest was
produced. Narrowing back to the filter+processing layer restored a working run; the known-good
example run `3a18e8b6` recorded 264 server requests and matched 49/49 client requests, containing
only `de.hybris.platform.servicelayer.web.*` classes.

Narrow includes are sufficient for client↔server correlation by `requestId` (filters run on every
request) but do NOT give full business-code coverage, which is the actual goal for diff-based test
selection ("did this code change affect what this test touches?"). That goal needs the wide set,
which is exactly what chokes. This note is about closing that gap.

## Root cause (from source)

Two independent, additive costs in the current Remote agent (`stp-remote-agent`):

### Cost 1 — class-transform wave at class load

`RemoteMethodEntryTransformer.transform` reads each matching class into an ASM `ClassNode`
(tree API), visits every method, inserts an entry hook, and reserializes with
`ClassWriter.COMPUTE_MAXS`. With the wide set, Commerce has tens of thousands of matching classes.
ImpEx import is the first request that touches a very large fraction of them, so the transform wave
lands during the import itself.

### Cost 2 — per-call hook on hot paths

The injected hook is 4 instructions pushing three strings (`className`, `methodName`, `descriptor`)
into `RemoteHooks.methodHit` → `RemoteRecorder.methodHit`
(`stp-remote-agent/src/main/java/.../RemoteHooks.java`,
`.../RemoteMethodEntryTransformer.java:46-53`). Per call, `RemoteRecorder.methodHit`:

1. reads `RemoteTestContext.currentIdentity()` (OTel `Baggage.current()` lookup — not free),
2. enters `synchronized (STATE_LOCK)`,
3. does `HashSet.add(className + "#" + methodName + descriptor)` — a fresh string concatenation and
   hash on every invocation.

On an ImpEx bulk loop (millions of getter/setter/validate calls) this is millions of synchronized
sections plus millions of string concatenations. This is the dominant strangler.

Note: a method is *recorded* only when the request carries STP baggage (`methodHit` returns early if
`currentIdentity()` is null). But the *instrumentation* (both costs above) is paid regardless of
whether a request is tagged — the bytecode hook is compiled in and invoked on every call. So Cost 2
is paid even for untagged ImpEx traffic.

### Prior art already in this repository

The mature JUnit-side collector `stp-agent` already avoids Cost 2:

- `MethodEntryClassFileTransformer` pushes a single precomputed `long` method id
  (`LdcInsnNode(methodId)` → `methodHit(J)V`), not three strings
  (`stp-agent/src/main/java/.../MethodEntryClassFileTransformer.java:120-123`).
- The id is computed once at transform time by `Fnv1a64MethodIdHasher` and stored in a
  `MethodCatalog` (`ConcurrentHashMap<Long, TreeSet<MethodIdentity>>`) with collision detection and
  `AgentMetrics` (`.../MethodCatalog.java:27-38`).

The Remote agent is the newer, simpler prototype that has not yet adopted these techniques. Several
options below are therefore a *port* of validated `stp-agent` code, not greenfield work. See also
`docs/asm-coverage-mapping-current-state.md` (FNV-1a ids, streaming envelope, collision detection)
and `docs/asm-vs-jacoco.md` (why method-entry coverage, not sampling, is the correct selection
signal).

## How profilers and APMs instrument, and why it does not apply here

A recurring question is "Dynatrace and other APMs instrument huge applications without choking — why
can we not just do what they do?" The answer is that they do **not** do what the STP remote agent
needs. They solve a different problem, and their techniques are either insufficient or unsafe for
diff-based test selection. Three categories:

### Sampling profilers (async-profiler, JFR, YourKit sampling mode)

They instrument nothing. A timer periodically (e.g. every 10 ms) captures the stack of every thread
through a JVM facility (`AsyncGetCallTrace`, JDK Flight Recorder). Overhead is ~1–2%. The output is a
**statistical** "where time is spent" profile.

- Why it does not choke: no per-call work at all; cost is fixed per sample interval, independent of
  call volume.
- Why it is wrong for us: a method that executes once and quickly is very likely never sampled. For
  "which methods did this request touch" the result is incomplete with unpredictable gaps. For test
  selection that is a **safety failure** — a missed touched-method means a test that should run is
  skipped.

### APMs (Dynatrace, AppDynamics, New Relic)

They do instrument bytecode, but **selectively at boundaries**, not every method:

- Instrumented points are entry/exit boundaries of interest: web entry (servlet/controller), DB calls
  (JDBC), remote calls (HTTP client), and known framework hooks (Spring, messaging). A trace
  ("PurePath") is a chain of those boundaries, not every getter on the call path.
- Full per-method instrumentation ("deep monitoring") is opt-in, budgeted, and **adaptively throttled**:
  when a method becomes hot (millions of calls/second) the agent reduces or disables its
  instrumentation to protect the application. There is an explicit back-pressure valve.
- The goal is latency/throughput/error observability, not an exact per-request set of touched methods.

So an APM would survive the ImpEx bulk loop precisely because it would **not** be recording every
model getter/setter call there — which is exactly the coverage the STP use case needs. Adopting the
APM strategy would reproduce the gap we are trying to close.

### What we actually need is coverage, not APM tracing

The question "does changing method M affect test T" needs only a **binary touched/not-touched set of
methods per request** — no call counts, no timing, no call-chain ordering. That is the coverage-tool
model (JaCoCo), not the APM model. JaCoCo stays cheap because:

- it writes one boolean per probe into a `boolean[]` (`arr[i] = true`), not a method call into a map;
- the flag is idempotent, so repeated passes cost almost nothing;
- there is no synchronization, no string building, and no map lookup on the hot path.

This repository has already measured that a JaCoCo-style ASM method-entry collector gives the correct
selection signal: `docs/asm-vs-jacoco.md` and `docs/asm-coverage-mapping-current-state.md` validate
`stp-agent` against JaCoCo and a JVMTI `MethodEntry` oracle (ASM: 2,955 TP, 0 FP, 0 FN on the bounded
Spring sample). The remote agent uses the same method-entry model but a far more expensive
per-call implementation (three-string key + global lock + map add) than `stp-agent` (single `long`
id, coverage-set, no global lock).

**Conclusion for direction:** the fix is not to imitate APM boundary-tracing or profiler sampling. It
is to make the remote agent's hot path as cheap as the already-validated `stp-agent` / JaCoCo coverage
model, while keeping full method-entry coverage. That is Options B and C below. Profiler sampling is
Option E and is rejected on safety grounds.

## How code-to-test mapping works once coverage is reliable

For reference, the end-to-end mapping the coverage data feeds (independent of which option is chosen,
as long as the per-request method set is complete):

1. **Server fragment** (`observations.json`): per `(suiteId, testId, requestId)`, the set of business
   methods the request touched (`de.hybris.*`, `com.sap.cx.*`).
2. **Client manifest** (Karate hook): per `(testId, requestId)`, which feature/scenario issued it.
3. **Join** (`smart-test-picker-cli join-remote`) → `joined-map.json`: **test → set of business
   methods** it covers across all its requests. The example run `3a18e8b6` already produced this with
   the thin filter layer only; Options B/C would populate it with the full business layer.
4. **Diff-based selection**: a git diff yields changed methods; select every test whose joined set
   contains at least one changed method. This is the same selection semantics STP applies for JUnit
   (`docs/current-development-state.md`, schema-3 target-aware mapping).

The final artifact is therefore `test → touched methods`, and selection is the intersection with the
changed-method set. Sampling (Option E) breaks this because a missed touch produces a wrongly-skipped
test.

## Options

### Option A — `excludes=` for hot/bulk paths (no code change)

Keep wide `includes`, add `excludes=` for packages that are pure bulk/plumbing (ImpEx engine, DDL
tooling, generated model getter/setter layer). The agent already supports this; `instruments()` lets
an exclude override an include (`RemoteAgentConfiguration.java:78-81`).

- Addresses: both Cost 1 and Cost 2, but only for the excluded packages.
- Pros: zero agent change; deployable immediately; reversible via config.
- Cons: loses coverage of the excluded packages, so a code change inside an excluded package would
  not be attributed to any test (a diff-based selector could then wrongly skip a test — a *safety*
  risk, not just a recall loss). Requires manually finding and maintaining the bulk/business
  boundary; a new bulk path reintroduces the choke. Brittle.
- Effort: minutes (config only).

### Option B — kill per-call string + global lock (small agent change)

Port the `stp-agent` hot path to the Remote agent: push a single precomputed `long` method id in the
injected hook; replace the global `synchronized` + `HashSet<String>` with a per-request id set that
does not take a process-wide lock on every call (e.g. a set keyed by request identity holding
`long` ids, updated without `STATE_LOCK`, or a per-thread buffer drained at checkpoint).

- Addresses: Cost 2 (the dominant strangler). Does NOT address Cost 1.
- Pros: keeps full business-code coverage (nothing excluded); mostly a port of validated code;
  method-id collision detection/metrics come along from `stp-agent`.
- Cons: output serialization must map `long` id → `MethodIdentity` (port `MethodCatalog`), changing
  the fragment-building path and the join input contract (the CLI `join-remote` currently reads
  method strings — see `docs/remote-stp-output-contract.md` and the join contract doc). Needs
  rebuild and a fresh PetClinic + Commerce proof.
- Effort: moderate; bounded by the existing `stp-agent` implementation to copy from.

### Option C — coverage-set semantics (first-hit wins, then no-op)

For diff-based selection the needed signal is "was this method touched for this request", not a call
count. Record each (request × method id) once: the first hit sets a flag, further calls take an
early-return branch. Combined with Option B's `long` ids, the hot path becomes approximately
`if (!seen.contains(id)) seen.add(id)` — near-free on bulk loops.

- Addresses: Cost 2 permanently for all code, with the exact semantics a selector needs. Does NOT
  address Cost 1.
- Pros: correct selection semantics (matches the ASM method-entry coverage model already validated
  in `docs/asm-vs-jacoco.md`); best steady-state hot-path cost; full coverage retained.
- Cons: larger change than B (dedupe structure per request, careful concurrency); same output/join
  contract migration as B; rebuild + re-prove.
- Effort: moderate-to-large; superset of B.

### Option D — cheaper class transform (reduce Cost 1)

Reduce the per-class transform cost that lands as a wave during ImpEx: replace the `ClassNode` tree
pipeline + `COMPUTE_MAXS` with a streaming `MethodVisitor` that only prepends the hook (entry
instrumentation does not grow the stack enough to need full frame/max recomputation in the common
case), and/or skip trivial methods below a size threshold. Do this alongside B/C.

Candidate "trivial method" filter (this is a shape filter the current agent cannot express — it only
has prefix include/exclude, so this requires a code change in `RemoteMethodEntryTransformer`):

- getter/setter shape: `getX`/`isX`/`setX` with a body of only load/field-access/return;
- constructors that only call `super.<init>` and return;
- method bodies below a bytecode-instruction threshold (e.g. < ~6 real instructions);
- synthetic/bridge methods, `lambda$...`, `access$...`, and compiler-generated accessors.

The ImpEx bulk loop is dominated by exactly these shapes on `de.hybris.*`/`com.sap.cx.*` generated
model classes, so skipping them removes most of both the transform wave and the per-call volume while
keeping real business logic. JDK (`java.*`, `javax.*`) and framework (`org.springframework.*`) methods
are already never instrumented — they are only instrumented if their prefix is in `includes`, which it
is not; descriptor types such as `Ljavax/servlet/...` in a recorded method only name parameter types,
not instrumented classes.

- Addresses: Cost 1 (transform wave), and materially reduces Cost 2 and catalog size.
- Pros: directly attacks the load-time spike that A/B/C leave untouched; keeps full coverage of
  non-trivial methods.
- Cons: streaming transform is trickier to keep correct across all class shapes (the current tree
  code is deliberately defensive); skipping accessors is a coverage **policy** decision — a change
  inside a skipped accessor would not be attributed to any test, so a diff-based selector could skip
  a test whose only change was in an accessor. Decide this explicitly. Needs careful shape testing
  (annotations, interfaces, synthetic).
- Effort: moderate.

### Option E — sampling (Dynatrace-style) — NOT recommended for this use case

Statistically sample calls instead of recording every method entry.

- Pros: lowest overhead; this is how production profilers avoid the choke.
- Cons: wrong signal for diff-based selection. Sampling can miss a method a test rarely touches,
  producing a false "test does not cover this change" — a safety failure in a test selector. Profiler
  sampling answers "where is time spent", not "what was touched". Recorded here for completeness and
  explicitly advised against for selection.
- Effort: moderate; not worth it given the semantic mismatch.

## Trade-off summary

| Option | Addresses | Keeps full coverage | Agent code change | Output/join contract change | Re-prove needed | Primary risk |
| --- | --- | --- | --- | --- | --- | --- |
| A `excludes` | Cost 1+2 (excluded pkgs only) | No (gaps) | None | None | No | Selector may skip a test whose changed code was excluded (safety) |
| B long-id + no global lock | Cost 2 | Yes | Small (port) | Yes | Yes | Contract migration + concurrency |
| C coverage-set | Cost 2 | Yes | Moderate | Yes | Yes | Dedup concurrency correctness |
| D cheap transform | Cost 1 | Yes (unless accessors skipped) | Moderate | No | Yes | Transform correctness across class shapes |
| E sampling | Cost 1+2 | No (probabilistic) | Moderate | Yes | Yes | Wrong signal for selection (safety) |

## Recommended sequence

1. **Now / demo:** narrow `includes` (filter+processing). Already shipped in `cxmc-product-pipeline`
   commit `d4b984f5`. Gives working client↔server correlation; not full coverage.
2. **Short term for full coverage without choke:** **B + C together** (precomputed `long` ids +
   coverage-set, no process-wide per-call lock), ported from `stp-agent`. This is the only path that
   yields "all code like JUnit" while removing the dominant strangler. Prove on PetClinic before
   Commerce.
3. **If the transform wave (Cost 1) still hurts ImpEx after B+C:** add **D** (streaming transform,
   skip trivial accessors) and/or a targeted **A** (`excludes` for genuinely pure-bulk packages such
   as `org.apache.ddlutils.` and generated model accessors).
4. **E is not pursued** for selection.

## Open questions before implementation

- Join input contract: `smart-test-picker-cli join-remote` currently consumes method-name strings in
  the fragment. B/C either keep emitting resolved strings at serialization time (map `long` →
  `MethodIdentity` in the agent before writing, preserving the contract) or change the fragment to
  carry ids + a catalog (changes the CLI). The first keeps the join contract stable and is preferred.
- Accessor-skipping (D) is a coverage-policy decision that affects selector safety; decide
  explicitly, do not infer.
- Measure the real bulk stranglers on the ImpEx path (which packages dominate method-call volume)
  before choosing any `excludes` set, so A/D are evidence-based rather than guessed.
