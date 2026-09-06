# G05 experiment plan

Question: is the singleton `SerializableTypeWrapper#unwrap` edge controlled by memoized resolution or another shared state?

Reuse ROUND 11's SerializableTypeWrapper grouping and negative shared-state conclusion. The edge moved with vector `[1,0,0,0,0]`, but has no same-group edge and no retained hit/miss or state-reset contrast connected to it.

This one-edge group has the lowest information gain. A dedicated object/cache-state investigation would explain at most one edge and would violate the round's instruction not to investigate every singleton indefinitely. Result: `INSUFFICIENT_EVIDENCE`; the edge remains `UNKNOWN`.
