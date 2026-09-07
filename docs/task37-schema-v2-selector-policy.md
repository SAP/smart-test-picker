# TASK 37 schema-v2 selector policy

TASK 37 implements the build-tool-neutral selection policy in `SchemaV2TestSelector`. Its only
semantic input is TASK 36's `SelectionAnalysisResult`: unsafe analysis becomes `FULL_SUITE`, while a
ready `SelectionContext` is evaluated without filesystem, Git, map decoding, or inventory discovery.

For a ready context, the mandatory execution set is the deterministic union of:

1. mapped, non-empty tests with an exact `coveredClasses` match;
2. every head test in each exact `SetupScope.affectedContainers` container when the scope covers a
   changed class;
3. every published schema-v2 unmapped identity still present at head, regardless of reason;
4. every new head identity; and
5. every TASK 36-classified changed head identity.

The union is intersected with `headTests`, naturally excluding deleted identities, and sorted by
`TestIdentity`. Method coverage and historical PASS/FAIL outcomes do not affect selection.
`COLLECTED_EMPTY` creates no direct edge but remains eligible through setup, new, or changed rules.

Setup expansion treats all scope types identically and remains bounded by explicitly named binary
containers. Nested containers do not imply parent membership. An affected container absent from the
authoritative head inventory is a selector-relevant inconsistency and fails open to `FULL_SUITE`.

The result is `SELECTED` exactly when the mandatory set is non-empty and `NONE` exactly when it is
empty. `selectedTests` is the complete executable set. `unmappedTests` retains sorted schema-v2 reason
diagnostics for compatible reporting, but is not a second execution policy.

TASK 37 does not change Gradle, Maven, or CLI adapter behavior. TASK 38 must connect authoritative head
inventory and reconcile execution adapters, then prove the real-project PetClinic regression before
5b can be marked done. Backlog status therefore remains **5b IN PROGRESS**.
