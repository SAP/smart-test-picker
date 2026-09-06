# Spring reflection-order reproducer

This research-only fixture isolates the ROUND 15 mechanism without STP or the full Spring test suite. It uses the same generic/overload shape as `BridgeMethodResolverTests.withGenericParameter` and prints four observations from each fresh application JVM:

1. the structural order returned by `Class#getDeclaredMethods()`;
2. the candidate order produced by Spring `ReflectionUtils.doWithMethods`;
3. the actual `BridgeMethodResolver.isBridgeMethodFor` predicate path until its first match; and
4. the method returned by `BridgeMethodResolver.findBridgedMethod`.

## Run

Use JDK 21, then run Gradle directly or repeat fresh JVMs:

```sh
../../gradlew --no-daemon -p . run
./run-repeated.sh 50
```

If this directory is copied outside the main repository, use any Gradle 8.14.2 installation or generate a wrapper with that version. The fixture itself has no dependency on STP.

## Expected observations

The structural method set remains the same. Across sufficiently many fresh JVM launches, the raw order may place `getFor(Integer)` before or after `getFor(Class):String`. `ReflectionUtils` preserves that order in the candidate list. When the integer overload comes first, the printed actual Spring predicate path contains a failed candidate before the successful class overload; when the class overload comes first, it succeeds immediately. The final resolved method is the same in both cases, while the internal control path differs.

Reflection ordering is explicitly unspecified by `Class#getDeclaredMethods()`, so a run that shows only one order is not a failure. It is a bounded sampling result; increase the repetition count or compare JVM builds. ROUND 15 observed both orders on Homebrew OpenJDK 21.0.11 arm64.

## Environment

- Java: OpenJDK 21.0.11, arm64
- Gradle: 8.14.2
- Standalone Spring dependency: 6.1.21 (the nearest published 6.1.x artifact with the same resolver implementation)
- Proven full-suite source revision: `99a366baf6640b275d08dde60f05da719139bb6a` (`6.1.22-SNAPSHOT` at that revision)

This matters because Spring consumes an unspecified reflection order while evaluating multiple valid candidate paths. The observed public result is stable in this fixture, but per-method runtime coverage differs because one order takes additional generic/interface-resolution branches.
