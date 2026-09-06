# G02 experiment plan

Question: do the 15 `SoftEntryReference` edges reflect clearing/purge, or ordinary occupancy-dependent reference traversal?

Partition exact five-run signatures and identify the three same-test `getHash` / `getNext` pairs. Reuse ROUND 11's forced-GC result and ROUND 12's negative sampling boundary. Require an exact relevant reference to be present, then cleared or purged, followed by a changed path before assigning `REFERENCE_LIFECYCLE`.

The pairs establish co-movement compatible with one bucket traversal. They do not identify a concrete cache owner or reference. The prior controlled GC run observed no relevant reclamation, so its changed capacity-method presence did not classify an edge. No exact reference can be followed safely in retained evidence, and generic JFR/heap work is explicitly out of scope. Result: `INSUFFICIENT_EVIDENCE`; all 15 edges remain `UNKNOWN` and none is labeled `REFERENCE_LIFECYCLE`.
