# ViaductSchema Testing Guidelines

## Overview

The ViaductSchema library includes comprehensive contract-based testing infrastructure. All `ViaductSchema` implementations should:

1. **Use contract testing**: Extend `ViaductSchemaContract` and `ViaductSchemaSubtypeContract`
2. **Validate type signatures**: Ensure proper Kotlin type relationships
3. **Test edge cases**: Handle empty schemas, cyclic types, complex extensions
4. **Verify invariants**: Schemas must satisfy structural invariants

## Contract Test Structure

### Core Contracts

The "contract tests" help ensure that a `ViaductSchema` implementation obeys the "contact" expected of such implementations.  All `ViaductSchema` implementations must extend and pass:

1. **`ViaductSchemaContract`**: Validates core schema behavior (type lookup, directive handling, root types, etc.)
2. **`ViaductSchemaSubtypeContract`**: Validates type hierarchy relationships (Record, etc.)

### Writing Contract Tests

If `MySchemaImpl` is an implementation of `ViaductSchema`:

```kotlin
class MySchemaImplTest : ViaductSchemaContract<MySchemaImpl>() {
    override fun createSchema(sdl: String): MySchemaImpl {
        // Your implementation-specific construction logic
        return MySchemaImpl.fromSdl(sdl)
    }
}

class MySchemaImplSubtypeTest : ViaductSchemaSubtypeContract<MySchemaImpl>() {
    override fun createSchema(sdl: String): MySchemaImpl {
        return MySchemaImpl.fromSdl(sdl)
    }
}
```

### Other Tests

**Unit tests**

Bazel:
```bash
bazel test //projects/viaduct/oss/shared/viaduct/...
```

Gradle (from `projects/viaduct/oss`):
```bash
./gradlew :core:shared:shared-viaductschema:test
```

**Integration Tests**

Bazel:
```bash
bazel run //projects/viaduct/oss/shared/viaductschema:run -- mmdiff -p $HOME/repos/treehouse/projects/viaduct/modules
```

Gradle (from `projects/viaduct/oss`):
```bash
./gradlew :core:shared:shared-viaductschema:run --args="mmdiff -p $HOME/repos/treehouse/projects/viaduct/modules"
```

**Benchmark**

To run the full benchmark from Bazel:
```bash
BENCHMARK_SCHEMA_SIZE=5000 bazel run //projects/viaduct/oss/shared/viaductschema/src/jmh/kotlin/viaduct/graphql/schema:schema
``
and from Gradle:
Gradle:
```bash
BENCHMARK_SCHEMA_SIZE=5000 ./gradlew :core:shared:shared-viaductschema:jmh
```

**Partial Benchmarks**

To run only specific benchmarks, add a regex filter:
```bash
# Only ViaductSchemaBenchmark
BENCHMARK_SCHEMA_SIZE=5000 bazel run //projects/viaduct/oss/shared/viaductschema/src/jmh/kotlin/viaduct/graphql/schema:schema -- "ViaductSchemaBenchmark"

# Only CentralSchemaBenchmark
bazel run //projects/viaduct/oss/shared/viaductschema/src/jmh/kotlin/viaduct/graphql/schema:schema -- "CentralSchemaBenchmark"

# Only binary read benchmarks
BENCHMARK_SCHEMA_SIZE=5000 bazel run //projects/viaduct/oss/shared/viaductschema/src/jmh/kotlin/viaduct/graphql/schema:schema -- ".*Bin.*"
```

To list available benchmarks without running them:
```bash
bazel run //projects/viaduct/oss/shared/viaductschema/src/jmh/kotlin/viaduct/graphql/schema:schema -- -l
```

## Required Test Coverage

### 1. Type Signatures

Verify Kotlin type relationships are properly maintained:

```kotlin
val schema: ViaductSchema = createSchema(sdl)
val obj = schema.types["User"] as ViaductSchema.Object
assertTrue(obj is ViaductSchema.Record)  // Object is also Record
assertTrue(obj.isComposite)  // Object is a compound output type
```

### 2. Edge Cases

Test boundary conditions:

- **Empty schemas**: Schema with no types or only built-in scalars
- **Cyclic types**: Types that reference themselves (directly or indirectly)
- **Complex extensions**: Multiple extensions of the same type
- **Deeply nested types**: Lists within lists, nullable at various levels

### 3. Schema Invariants

Verify structural requirements:

- All type references resolve to actual type definitions
- Interface implementations are bidirectional (implementing type lists interface, interface lists implementing type)
- Union members exist and are object types
- Applied directives reference valid directive definitions
- Extension relationships are consistent (base definition exists, extensions reference correct base)

### 4. Applied Directives

Test directive argument resolution:

- Explicitly provided arguments have correct values
- Default values are populated for omitted arguments
- Null values are handled correctly (explicit null vs defaulted null)

```kotlin
val field = obj.field("name")
val deprecated = field.appliedDirective("deprecated")
assertNotNull(deprecated)
assertEquals("Use fullName instead", deprecated.arguments["reason"])
```

## Testing FilteredSchema

When testing `FilteredSchema`, additionally verify:

1. **Filter application**: Types/fields/values excluded by filter are not present
2. **Linkage preservation**: Filtered elements maintain correct relationships
3. **Invalid schema tolerance**: Filtered schemas need not be valid GraphQL (e.g., fields can reference non-existent types)
4. **Nested filtering**: Fields, interface implementations, and union members are properly filtered

## Implementation-Specific Tests

Beyond contract tests, add implementation-specific tests for:

- Performance characteristics (if relevant)
- Memory usage patterns (especially for large schemas)
- Construction edge cases (parsing failures, validation errors)
- Implementation-specific optimizations (lazy loading, caching, etc.)

## Testing Best Practices

1. **Use real schemas**: Include tests with actual Viaduct tenant schemas, not just toy examples
2. **Test incrementally**: Validate behavior at each level of the type hierarchy
3. **Verify immutability**: Ensure all returned values are truly immutable
4. **Check reference vs value semantics**: Most types use reference equality; `TypeExpr` and `AppliedDirective` use value equality
5. **Test error conditions**: Verify appropriate behavior for malformed schemas, invalid references, etc.

## Example Test Pattern

```kotlin
@Test
fun `test field navigation with complex nesting`() {
    val schema = createSchema("""
        type Query {
            user: User
        }
        type User {
            address: Address
        }
        type Address {
            street: String!
        }
    """)

    val queryType = schema.queryTypeDef!!
    val streetField = queryType.field(listOf("user", "address", "street"))

    assertNotNull(streetField)
    assertEquals("String", streetField.type.baseTypeDef.name)
    assertFalse(streetField.type.baseTypeNullable)
}
```

## Running Tests

Contract tests automatically run comprehensive validation. To run all ViaductSchema tests:

```bash
bazel test //projects/viaduct/oss/shared/viaductschema/...
```
