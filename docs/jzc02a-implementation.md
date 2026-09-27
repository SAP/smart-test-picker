# JZC-02A method-exact Maven execution

Parent treatment: `JZC-01` at `1151bbd99faf3c172bb42e7625cf4ba828da7b94`.

JZC-02A preserves the selected logical set and adds an execution-only layer:

- the JaCoCo listener writes optional structured identity sidecars beside existing per-test sessions;
- coverage-map generation carries that metadata additively while retaining every legacy map key;
- old maps without metadata remain readable and receive conservative class fallback;
- the Maven planner emits exactly one mode per selected unit: `METHOD_EXACT`, `CLASS_FALLBACK`, `FULL_SUITE_FALLBACK`, or `NOT_EXECUTABLE_ERROR`;
- exact filters use module-scoped Surefire `test` properties passed explicitly through Maven Invoker;
- named JUnit4 Parameterized methods use the empirically proven `#method[*]` form; ordinary, indexed JUnit4 Parameterized and Jupiter parameterized methods use `#method`;
- new/unmapped classes and unresolved legacy identities remain conservative and expose an explicit fallback cause;
- a non-zero reduced Maven fork is fatal; zero-match execution is not silently accepted.

Coverage probes, NO_COVERAGE selection, change analysis and selection-cause reporting are unchanged. JZC-03 is deliberately absent.

Regression coverage includes structured-map backward compatibility, unchanged selected sets, parameterized execution-shape classification, hash-like legitimate method names, stale/unknown/legacy fallbacks, deliberate class-level mode, module isolation, and actual InvocationRequest property propagation.

No ASM collector, runtime, configuration, dependency edge, or hybrid behavior was added.
