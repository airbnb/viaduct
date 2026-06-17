# Testing Assertion Libraries

## Rule

Use **JUnit 5** for basic assertions. Use **Kotest** where JUnit is missing an assertion or its diagnostic output is insufficient to debug failures quickly. Do not add any other assertion libraries.

## JUnit 5 for basic assertions

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

## Kotest for collection assertions and richer diagnostics

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

### When to prefer Kotest over JUnit

- **Collection equality/containment** — use Kotest (`shouldContainExactly`, `shouldContainExactlyInAnyOrder`, `shouldContain`, `shouldHaveSize`, `shouldBeEmpty`) rather than JUnit's `assertEquals` on lists, whose diagnostic output is a bare "expected X but was Y" with no diff.
- **Null checks on complex types** — `value.shouldNotBe(null)` gives better context than `assertNotNull(value)`.
- **Substring / partial matching** — `shouldContain` on strings, where JUnit has no direct equivalent.

## Prohibited libraries

Do not add or use:
- `assertj-core`
- `strikt-core`
- `kotlin-test` (the entire `kotlin.test.*` package, including `kotlin.test.Test` and all assertion functions)
- `guava-testlib` (use the local `EqualsTesterHelper` in `viaduct.utils.collections` if you need equality-group testing)
