# G03 experiment plan

Question: does the already-proven raw reflection-order event explain all eight remaining `withGenericParameter` path edges, rather than only the ROUND 16 `ResolvableType#getInterfaces` edge?

Compare ROUND 15 fresh-JVM absent run 1 with present run 5. Verify that the raw `Class#getDeclaredMethods` structural set is equal, its order differs, ReflectionUtils and candidate insertion preserve that change, and ROUND 14 locates the first differing candidate decision. Then inspect the two retained per-test maps for every G03 target method.

All eight G03 methods are absent in run 1 and present in run 5. They lie on the single generic/interface-resolution path that is skipped when the Class candidate is first and entered when the Integer candidate is first. This is group-wide direct path evidence, not promotion from a representative edge or from the shared ROUND 16 vector.

Result: `GROUP_CAUSE_PROVEN`; all eight edges are `JDK_REFLECTION_ORDER` under pattern P01. Scope is limited to this test and these traced edges.
