# ForkJoin task ownership

The ASM agent observes application `ForkJoinTask.fork`/`invoke`, pool
`execute`/`submit`/`invoke` task overloads and `reinitialize`. It instruments concrete
application `RecursiveAction`/`RecursiveTask.compute` and custom `ForkJoinTask.exec`
methods. Both the submission site and execution boundary must be observed; JDK
classes and worker loops are not transformed.

The runtime associates an owner with the task by weak object identity, never by
user `equals` or `hashCode`. Execution temporarily installs the captured test or
container and restores the worker state on every exit. Recursive children inherit
that context. A no-owner binding does not borrow the worker's ambient owner.

## Ambiguity and limits

- Repeated submission without completed reinitialization is ambiguous. The original
  owner is not replaced, and ERROR `ASYNC_SETUP_UNSUPPORTED` diagnostics reject
  collection; subsequent ambiguous hits are quarantined as `UNKNOWN_CONTEXT`.
- Reinitialization during execution is unsupported, including cancelled work that
  is still running. Observed completed reinitialization permits sequential reuse.
- JDK-adapted tasks through task-shaped APIs have no instrumented execution boundary.
  An observed submission with an active owner reports unsupported setup rather
  than claiming coverage. Unobserved submissions cannot be inferred.
- Reflection, method handles, preloaded or excluded callers, bulk APIs and
  `CountedCompleter` are not covered by this mechanism.
- Finished-test hits remain late events. Closed-container work retains the setup
  diagnostic path. Publication does not wait for arbitrary descendants.

`ForkJoinContexts`, `ExecutorCallSiteTransformer` and `ForkJoinExecutionTransformer`
implement these rules. Runtime and agent fixtures exercise recursion, cancellation,
reuse, errors, late execution and closed setup containers. This is not a general
parallel-test support guarantee. See [context propagation](asm-context-propagation.md)
and the [historical record](history/asm-forkjoin-task-propagation.md).
