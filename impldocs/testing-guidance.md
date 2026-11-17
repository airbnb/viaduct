# Testing Guidance

## Engine behavior and engine consumers

Use shared contracts for behavior observable through the Engine API. Keep reusable scenarios and assertions in the [Engine API test fixtures](../core/engine/api/src/testFixtures/kotlin/viaduct/engine/runtime/execution/), with concrete runners for `runtime` and `runtime2` supplying their engine setup. A new contract test then applies to both implementations. Keep tests of implementation-specific algorithms, data structures, and invariants beside that implementation.

When testing an engine consumer, such as the service or wiring layer, keep the tests in the consumer's project. Parameterize over the real engines where their integration with the consumer matters. Construct engines through a factory that encapsulates `FlagManager`, passing the engine choice explicitly for each invocation so both variants run regardless of the process environment. Make the selected engine visible in test results. Pure helper tests and tests using only a mocked engine do not need a parameter for the real-engine implementations.

### Choosing mocks and real engines

Choose the implementations used by each consumer test according to the behavior it establishes:

| Test setup | Use when |
| --- | --- |
| Local mock | Checking calls, arguments, execution options, response handling, or unusual failures at the Engine API boundary. |
| Both real engines | Checking integration that depends on actual execution, including coroutine/executor propagation, streaming, ordering, errors, and cancellation cleanup. |
| Mock plus both real engines | Equivalent scenarios can share meaningful assertions about the consumer's observable behavior across all three variants. |

A mock records the behavior the consumer expects from its dependency. Mock verification establishes how the consumer calls the engine; it does not establish that real engines honor those calls. Shared Engine API contracts and real-engine integration tests supply that evidence. Continue to execute the consumer itself, and avoid assertions that merely repeat mock configuration.

For a test parameterized over a mock and real engines, have the mock return a known response and configure the real engines to produce the equivalent response, then apply the same consumer assertions to every variant. Use this pattern where the scenarios fit naturally; a mock need not be a parameter of every integration test. A controlled mock producer can also exercise a precise stream failure while complementary real-engine tests cover the actual streaming bridge and cleanup.

Create fresh MockK mocks within each test or parameter invocation. Keep their configuration and verification local rather than introducing a shared reusable MockK mock. Shared factories for real-engine construction remain useful.

### Preserving behavioral coverage

Classify tests by their assertions and dependencies. A test inside `runtime` may exercise a reusable Engine API behavior worth extracting into a contract, but constructing an engine does not make a test independent of its implementation. Keep tests that depend on old-engine internals specific to `runtime`.

Preserve the expectations for each supported capability. For incremental delivery, shared contracts assert incremental results when supported and the complete-result fallback otherwise. Keep focused streaming integration checks for engines and configurations that support streaming, alongside coverage of the fallback. Do not weaken a behavioral assertion or skip an entire consumer suite to accommodate a different engine.

## Assertion libraries

### Rule

Use **JUnit 5** for basic assertions. Use **Kotest** where JUnit is missing an assertion or its diagnostic output is insufficient to debug failures quickly. Do not add any other assertion libraries.

### JUnit 5 for basic assertions

Prefer JUnit assertions for equality, nullness, sameness, instance checks, and exception throwing:

```kotlin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.assertThrows  // top-level Kotlin extension
```

### Kotest for collection assertions and richer diagnostics

JUnit lacks some useful collection assertions (e.g., no `assertContainsExactly` that checks same elements regardless of order), and its diagnostic output can be sparse for collection failures. Use Kotest matchers in those cases:

```kotlin
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.assertions.throwables.shouldThrow
```

#### When to prefer Kotest over JUnit

- **Collection equality/containment** — use Kotest (`shouldContainExactly`, `shouldContainExactlyInAnyOrder`, `shouldContain`, `shouldHaveSize`, `shouldBeEmpty`) rather than JUnit's `assertEquals` on lists, whose diagnostic output is a bare "expected X but was Y" with no diff.
- **Null checks on complex types** — `value.shouldNotBe(null)` gives better context than `assertNotNull(value)`.
- **Substring / partial matching** — `shouldContain` on strings, where JUnit has no direct equivalent.

### Prohibited libraries

Do not add or use:
- `assertj-core`
- `strikt-core`
- `kotlin-test` (the entire `kotlin.test.*` package, including `kotlin.test.Test` and all assertion functions)
- `guava-testlib` (use the local `EqualsTesterHelper` in `viaduct.utils.collections` if you need equality-group testing)
