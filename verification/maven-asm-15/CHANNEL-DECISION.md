# Class-order channel decision (before mapping permutations)

Both channel controls passed 69 tests without STP collection. The tracked subject
was unchanged. See raw/channel-reverse and raw/channel-probe, including expanded
commands, the measurement overlay and Surefire reports.

- Surefire reversealphabetical + Jupiter ClassName: actual top-level order is
  alphabetical, starting PetClinicConcurrencyTests, ending VetTests.
- Surefire alphabetical + Jupiter Random, seed 20261104: actual top-level order
  starts PetValidatorTests, PetControllerTests, VisitControllerTests,
  I18nPropertiesSyncTest, OwnerControllerTests; PetClinicConcurrencyTests is sixth.

Thus the final class order here is Jupiter's, not Surefire's input order. The
retained actual Surefire 3.5.6 provider bytecode explains both possible routes:
execute uses one multi-selector launcher request when allowEagerReading is true,
otherwise separate per-class requests. The observations are consistent with the
multi-selector route; do not infer that every Surefire configuration uses it.
No additional listener or subject class was needed to observe the outcome.

For the measured differential use Surefire alphabetical throughout and Jupiter
ClassName, Random seed 8675309, Random seed 20261104. MethodName stays fixed for
every run. This replaces the contingent Surefire-order plan in PRE-RUN.md.
The built-in global ClassOrderer also reorders nested classes, so nested class
order is not held fixed; this is still class-only variation, not within-class
method permutation. Check that each declaring class's method list is identical
across all runs and collectors, and that the actual top-level orders differ.

The PRE-RUN.md classification and power definitions remain unchanged. Record
strict changed-successor power separately from incident-pair power. All controls
and this decision precede examination of class-permutation mapping results.
