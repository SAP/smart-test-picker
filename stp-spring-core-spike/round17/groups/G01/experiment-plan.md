# G01 experiment plan

Question: do the 69 `ConcurrentReferenceHashMap` capacity/restructure edges share a concrete cache-owner, occupancy, resize, purge, reference-lifecycle, or predecessor trigger?

Use the ROUND 16 five-run vectors first. Treat each same-test `getLoadFactor` / `createReferenceArray` / `restructure` triplet as one candidate internal operation cluster. Reuse ROUND 11's exact-population receiver limitation, cache-reset contrast, forced-GC observation, and controlled-order evidence. Do not infer a receiver from the calling test or method name.

Bounded decision: the 22 triplets prove co-movement and a shared internal call path, while the three restructure-only edges show that not every restructure is a resize-shaped triplet. The retained exact-population evidence has no concrete map/segment identity, before/after occupancy, restructure reason, or reference identity for these invocations. ROUND 11's broad reset did not move its representative edge and its forced-GC contrast observed no reclamation. Those negatives rule out promotion; they do not prove a common alternative.

No new broad receiver-provenance instrumentation is added. Resolving every exact invocation would require the invasive, population-wide object provenance that defines the ROUND 17 stop boundary. Result: `INSUFFICIENT_EVIDENCE`; all 69 edges remain `UNKNOWN`.
