# Complete selection reasons

This correction adds explanation metadata only. It does not change selection, coverage collection, change detection, or Maven execution translation.

`SelectionCause` contains a `type` and an optional concrete `symbol`. Supported types are `METHOD_CHANGE`, `CLASS_CHANGE`, `NO_COVERAGE`, and `UNMAPPED`. Causes for each test are an ordered set: duplicates are removed and serialization order is enum type followed by the lexicographic symbol (with a missing symbol ordered as an empty string).

`TestSelector` accumulates every dependency overlap without changing the selected set. Method matches retain the fully qualified `Class#method`; class-level matches and safety escalation retain the class FQN. Empty coverage entries retain `NO_COVERAGE`. `TestSelectionEngine` adds `UNMAPPED` for independently detected runnable head tests.

`SelectionOutput.selectionCauses` is additive and optional. The legacy `selectionReasons` field remains available for old consumers and old output without `selectionCauses` remains readable. Coverage-map schema and keys are unchanged.

The HTML report prefers structured causes and displays every cause in stable order. Execution mode and fallback cause remain separate fields in the execution plan.

Excluded from this correction: selector algorithm changes, execution-plan changes, JaCoCo changes, change-analysis/Javadoc filtering, ASM, Surefire changes, and subject changes.
