# ViaductSchema Testing Guide

This document is for **contributors** to the ViaductSchema library. If you're looking for information about using ViaductSchema's test fixtures in your own tests, see [TEST_FIXTURES.md](TEST_FIXTURES.md).

## Overview

ViaductSchema tests are organized into two categories:

- **Black-box tests**: Verify GraphQL semantics across all ViaductSchema implementations using shared test schemas. These tests validate *what* the implementations do without assuming *how* they do it.

- **Glass-box tests**: Verify implementation-specific behavior such as encoding limits, caching strategies, error handling, and internal data structures. These tests target a specific implementation.

This separation ensures that:

1. All implementations conform to the same GraphQL semantics (black-box)
2. Implementation-specific optimizations and constraints are properly tested (glass-box)
3. Adding a new test case to TestSchemas automatically tests all implementations
4. Implementation changes don't break semantic compatibility

## Black-Box Testing with TestSchemas

Black-box tests use the `TestSchemas` infrastructure (in testFixtures) which provides 134 comprehensive GraphQL schemas organized by definition kind:

| Category | Description |
|----------|-------------|
| `DIRECTIVE` | Directive definitions, applied directives, argument patterns |
| `ENUM` | Enum types, deprecated values, extensions |
| `INPUT` | Input types with default value patterns, nested defaults, extensions |
| `INTERFACE` | Interface hierarchies, implementors, extensions |
| `OBJECT` | Object types, field arguments, interface implementations |
| `SCALAR` | Custom scalar definitions |
| `UNION` | Union types and extensions |
| `ROOT` | Root type configurations (custom Query/Mutation/Subscription names) |
| `COMPLEX` | Multi-feature schemas testing combinations |

Each test case exercises GraphQL features rather than implementation details.

### Black-Box Test Classes

| Test Class | What It Tests |
|------------|---------------|
| `BlackBoxBSchemaTest` | BSchema binary round-trip (encode → decode → compare) |
| `BlackBoxGJComparisonTest` | GJSchema vs GJSchemaRaw equivalence |
| `BlackBoxToRegistryTest` | toRegistry() round-trip fidelity |
| `BlackBoxFilteredSchemaTest` | FilteredSchema with identity filter preserves content |

### Adding New Black-Box Tests

When you discover a GraphQL feature or edge case that should be tested across all implementations:

1. Add a new `Case` to the appropriate category in `TestSchemas.kt`
2. The case will automatically be tested by all black-box test classes
3. If the schema is incompatible with graphql-java (due to graphql-java limitations), set `gjIncompatible` with an explanation

```kotlin
// In TestSchemas.kt
Case(
    name = "descriptive name for the test case",
    kind = "ENUM",  // Primary GraphQL definition kind
    sdl = """
        enum Status @myDirective {
            ACTIVE
            INACTIVE @deprecated
        }
    """,
    gjIncompatible = null  // or explanation string if incompatible
)
```

## Glass-Box Testing

Glass-box tests target implementation-specific behavior that can't be tested through the ViaductSchema interface alone.

### BSchema Glass-Box Tests

| Test File | What It Tests |
|-----------|---------------|
| `BInputStreamTest` | Buffer management, boundary conditions, off-by-one bugs |
| `ValueStringConverterTest` | Value-to-string conversion for binary encoding |
| `ErrorHandlingTest` | Encoding-time error conditions |
| `DecodingErrorHandlingTest` | Decoding-time error conditions |
| `ListDepthLimitTest` | Maximum list nesting depth (27 levels) |

### GJSchema Glass-Box Tests

| Test File | What It Tests |
|-----------|---------------|
| `ToGraphQLSchemaGlassBoxTest` | Type caching, PassthroughCoercing, source location preservation |
| `GJSchemaErrorTest` | Populate-once semantics |
| `GJSchemaRawErrorTest` | Populate-once semantics |

### FilteredSchema Tests

| Test File | What It Tests |
|-----------|---------------|
| `FilteredSchemaTest` | Filter application, linkage preservation, nested filtering |

### When to Write Glass-Box Tests

Write a glass-box test when:

- Testing sub-abstractions (e.g., BInputStreamTest)
- Implementation specific behavior, corner cases and regressions (e.g., ListDepthLimitTest)
- Testing error handling (e.g., GJSchemaErrorTest)
- Testing invariants that only apply to a specific implementation (e.g., GJSchemaCheck)

## Contract Tests

Contract tests ensure all ViaductSchema implementations satisfy the interface contract. Every implementation must pass:

1. **`ViaductSchemaContract`**: Core schema behavior (type lookup, directive handling, root types)
2. **`ViaductSchemaSubtypeContract`**: Type hierarchy relationships

### Implementing Contract Tests

```kotlin
class MySchemaImplTest : ViaductSchemaContract<MySchemaImpl>() {
    override fun createSchema(sdl: String): MySchemaImpl {
        return MySchemaImpl.fromSdl(sdl)
    }
}

class MySchemaImplSubtypeTest : ViaductSchemaSubtypeContract<MySchemaImpl>() {
    override fun createSchema(sdl: String): MySchemaImpl {
        return MySchemaImpl.fromSdl(sdl)
    }
}
```

## Running Tests From Gradle

All commands run from `projects/viaduct/oss`.

### Unit Tests

```bash
./gradlew :core:shared:shared-viaductschema:test
```

### Integration Tests

```bash
./gradlew :core:shared:shared-viaductschema:run --args="mmdiff -p $HOME/repos/treehouse/projects/viaduct/modules"
```

### Benchmarks

Full benchmark:

```bash
BENCHMARK_SCHEMA_SIZE=5000 ./gradlew :core:shared:shared-viaductschema:jmh
```

Partial benchmarks with regex filter:

```bash
# Only ViaductSchemaBenchmark
BENCHMARK_SCHEMA_SIZE=5000 ./gradlew :core:shared:shared-viaductschema:jmh -Pjmh.includes="ViaductSchemaBenchmark"

# Only binary read benchmarks
BENCHMARK_SCHEMA_SIZE=5000 ./gradlew :core:shared:shared-viaductschema:jmh -Pjmh.includes=".*Bin.*"
```

## Running Tests From Bazel (Airbnb internal)

### Unit Tests

```bash
bazel test //projects/viaduct/oss/shared/viaductschema/...
```

### Integration Tests

```bash
bazel run //projects/viaduct/oss/shared/viaductschema:run -- mmdiff -p $HOME/repos/treehouse/projects/viaduct/modules
```

### Benchmarks

Full benchmark:

```bash
BENCHMARK_SCHEMA_SIZE=5000 bazel run //projects/viaduct/oss/shared/viaductschema/src/jmh/kotlin/viaduct/graphql/schema:schema
```

Partial benchmarks with regex filter:

```bash
# Only ViaductSchemaBenchmark
BENCHMARK_SCHEMA_SIZE=5000 bazel run //projects/viaduct/oss/shared/viaductschema/src/jmh/kotlin/viaduct/graphql/schema:schema -- "ViaductSchemaBenchmark"

# Only binary read benchmarks
BENCHMARK_SCHEMA_SIZE=5000 bazel run //projects/viaduct/oss/shared/viaductschema/src/jmh/kotlin/viaduct/graphql/schema:schema -- ".*Bin.*"

# List available benchmarks
bazel run //projects/viaduct/oss/shared/viaductschema/src/jmh/kotlin/viaduct/graphql/schema:schema -- -l
```

## Test Coverage Guidelines

### Required Coverage for New Implementations

When adding a new ViaductSchema implementation:

1. **Contract tests**: Extend `ViaductSchemaContract` and `ViaductSchemaSubtypeContract`
2. **Black-box tests**: Add to an existing black-box test class or create a new one
3. **Glass-box tests**: Add implementation-specific tests for:
   - Construction edge cases
   - Error handling
   - Performance-critical code paths
   - Implementation-specific invariants

### Required Coverage for Bug Fixes

When fixing a bug:

1. **Add a regression test** that reproduces the bug before the fix
2. **Prefer black-box tests** if the bug affects GraphQL semantics
3. **Use glass-box tests** if the bug is in implementation-specific code

### Edge Cases to Cover

- Empty schemas (no types or only built-in scalars)
- Cyclic type references (direct and indirect)
- Complex extensions (multiple extensions on the same type)
- Deeply nested types (lists within lists, nullable at various levels)
- Applied directives with default argument values
- Type expressions with multiple wrapper layers

## Best Practices

1. **Use public factory functions**: In tests, prefer `ViaductSchema.fromTypeDefinitionRegistry()` over internal constructors unless testing implementation-specific behavior.

2. **Use SchemaDiff for comparisons**: The `SchemaDiff` utility provides comprehensive schema comparison with helpful diff output.

3. **Group black-box tests by category**: Use JUnit 5's `assertAll` to run all cases in a TestSchemas category together.

4. **Document glass-box test rationale**: Add comments explaining why a glass-box test exists and what specific behavior it verifies.

5. **Check reference vs value semantics**: Most ViaductSchema types use reference equality; `TypeExpr` and `AppliedDirective` use value equality.
