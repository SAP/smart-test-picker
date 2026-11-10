# STP-PERF-01: pre-change measurement protocol

Recorded before running the benchmarks or modifying production code.

Subject: unchanged STP at `498ada8831db49ea654af136a72329706a3cb0a0`.
Use the existing shaded agent with SHA-256
`dfa69a8afe2689b13e8ccecd9ee26817f252aa354485e4ffa37279e7ded981cc`,
whose build provenance is retained in STP-PETCLINIC-PARITY-01.

Each sample is a fresh JVM running one real Jupiter test through the Platform
launcher. Only `perf.subject.HotMethod` is included for instrumentation. Its
`touch(long)` method computes a recurrence whose result is printed and compared
between agent and control runs; no constant empty loop or unused result is timed.
There are no application locks or allocations in this method.

- Hot-loop samples: 1,000,000; 10,000,000; 100,000,000 calls on the test thread.
- Contention samples: 10,000,000 total calls, divided evenly between 1, 2, 4, 8,
  and 16 platform threads. Worker creation is outside the timed window. Workers
  wait at a latch and are joined before the test ends. Agent runs explicitly wrap
  workers with `RuntimeHooks.wrap`, so all hits belong to the same live test.
- Every JVM warms the same method with 1,000,000 calls before timing. This warmup
  remains within the same test; diagnostics must account for warmup plus measured
  calls exactly. No production scope or owner semantics are changed.
- Three samples per configuration and mode. Alternate agent/control launch order
  by repetition. Run samples serially, never benchmarks concurrently.
- Fixed JDK, `-Xms256m -Xmx256m -XX:+UseG1GC`. Record exact commands, dependency
  hashes, agent hash, hardware and JVM version.
- Report median/min/max measured-loop wall time, nanoseconds per call, agent/control
  ratios, and complete process wall time separately. The latter includes startup,
  warmup and diagnostic serialization and is not substituted for loop cost.

Decision gate: inspect agent time growth when N increases tenfold and its per-call
cost across the three sizes. Approximately linear growth or declining per-call cost
does not establish the task's required superlinear behavior. Inspect contention
against the fixed-total one-worker reference, reporting every thread count rather
than assuming monotonic degradation. If either required premise is not demonstrated,
follow the explicit stop rule: report the measurements and implement nothing.
A high constant overhead can still motivate a different performance task; it is not
proof of superlinear growth or the cause of the Hybris failures.

These are local proxies, not Hybris reproductions. They cannot establish the cause
of a six-hour class, fix the 17 shard failures, or qualify any change for CI.
