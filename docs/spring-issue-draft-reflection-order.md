# Draft: Should BridgeMethodResolver candidate evaluation depend on reflection result order?

## Title

Should `BridgeMethodResolver` normalize candidate order when multiple valid candidate paths exist?

## Minimal reproduction

The standalone fixture in `research/spring-reflection-order-reproducer` defines a generic bridge plus `getFor(Class)` and `getFor(Integer)` overloads. It prints the raw `Class#getDeclaredMethods()` order, the order preserved by `ReflectionUtils.doWithMethods`, calls Spring's actual package-visible `BridgeMethodResolver.isBridgeMethodFor` predicate in candidate order, and invokes `findBridgedMethod`.

Run `./run-repeated.sh 50` with JDK 21. No STP agent or Spring source patch is involved.

## Observed behavior

On Homebrew OpenJDK 21.0.11 arm64, fresh JVMs returned the same structural declared-method set in different orders. Spring preserved the raw order. With the integer overload first, bridge resolution evaluated a failed candidate and entered generic/interface resolution before evaluating the successful class overload. With the class overload first, it returned on the first direct match. The final resolved method was the same, but the internal control path differed.

## Expected discussion/question

Is this internal order sensitivity intentional, or should `BridgeMethodResolver` establish a deterministic candidate order when more than one candidate passes its inexpensive filter? This is a question about deterministic evaluation, not a claim that either Spring or the JDK violates a contract.

## Environment

- Spring Framework source revision `99a366baf6640b275d08dde60f05da719139bb6a` (`6.1.22-SNAPSHOT`)
- Standalone dependency `org.springframework:spring-core:6.1.21` (same resolver implementation; the proven full-suite revision identified itself as unreleased `6.1.22-SNAPSHOT`)
- Gradle 8.14.2
- Homebrew OpenJDK 21.0.11, arm64
- macOS

## Why order sensitivity matters

Different valid candidate orders can produce different internal branches, cache interactions, performance profiles, and method-level coverage even when the returned method is identical. Deterministic candidate handling would make those properties easier to reason about.

Important: `Class#getDeclaredMethods()` does not promise a stable order. The reproducer treats differing raw order as permitted JDK behavior and asks only whether Spring wants its candidate evaluation to inherit that variability.

Upstream issue status: **draft only; not opened**.
