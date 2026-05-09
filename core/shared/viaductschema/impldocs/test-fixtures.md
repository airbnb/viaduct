# ViaductSchema Test Fixtures

The test fixtures provide utilities and sample schemas for testing code that uses ViaductSchema (they are also used internally to test ViaductSchema implementations). They are organized into:

- **Kotlin utilities** for creating and comparing schemas
- **Resource schemas** of varying sizes for integration and performance testing

## Kotlin Utilities

Package: `viaduct.graphql.schema.test`

### Schema Creation Functions

| Function | Description |
|----------|-------------|
| `mkSchema(sdl: String)` | Create a `ViaductSchema` from an SDL string. Automatically prepends minimal Query/Mutation types and common scalars (Long, Short). |
| `mkGraphQLSchema(sdl: String)` | Create a graphql-java `GraphQLSchema` from an SDL string. Same auto-prepending behavior as `mkSchema`. |
| `mkSchemaWithSourceLocation(sdl, sourceName, ...)` | Create a schema with explicit source location metadata for all types/fields. |
| `mkSchemaWithSourceLocations(sdlAndSourceNames, ...)` | Create a schema from multiple SDL fragments, each with its own source name. |
| `loadGraphQLSchema(resourcePath?)` | Load a `ViaductSchema` from `.graphqls` files on the classpath. |

**Constants:**

- `BUILTIN_SCALARS` - SDL string defining the five built-in GraphQL scalars (Boolean, Float, ID, Int, String). Useful when parsing raw SDL that needs these definitions.

### Test Schema Collection (`TestSchemas`)

The `TestSchemas` object provides a curated collection of GraphQL schemas for black-box testing. Each schema is designed to exercise specific GraphQL features and edge cases.

**Usage:**
```kotlin
TestSchemas.ALL           // All test cases
TestSchemas.DIRECTIVE     // Directive-related test cases
TestSchemas.byKind("ENUM") // Filter by GraphQL definition kind
```

**Schema categories:**

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

Each `TestSchemas.Case` contains:

- `name` - Descriptive test name
- `kind` - Primary GraphQL definition kind being tested
- `sdl` - The raw SDL (without built-ins)
- `fullSdl` - The SDL with built-in directives and scalars prepended
- `gjIncompatible` - If non-null, explains why this schema can't be used with graphql-java

### Schema Comparison (`SchemaDiff`)

`SchemaDiff` compares two `ViaductSchema` instances for structural equality:

```kotlin
val checker = SchemaDiff(expected, actual).diff()
if (!checker.isEmpty) {
    println("Differences found: ${checker.joinToString("\n")}")
}
```

This is useful for verifying that a function which returns a `ViaductSchema` returns the **expected** schema.

## Resource Schemas

### arb-schema-5k

A randomly-generated GraphQL schema containing approximately 5,000 type definitions.

- **File:** `arb-schema-5k/arb-schema-5k.graphqls`
- **Size:** ~544 KB (30,000+ lines)
- **Purpose:** Comprehensive feature coverage testing

This schema exercises a wide variety of GraphQL constructs including:

- Many directive definitions with various argument patterns
- Object, Interface, Input, Enum, Union, and Scalar types
- Field arguments with default values
- Type extensions
- Complex type relationships

**Loading:**
```kotlin
val schema = javaClass.classLoader
    .getResourceAsStream("arb-schema-5k/arb-schema-5k.graphqls")
    ?.let { ViaductSchema.fromTypeDefinitionRegistry(SchemaParser().parse(it)) }
```

### large-schema-4 and large-schema-5

Very large schemas intended for performance benchmarking.

| Schema | Compressed | Uncompressed | Purpose |
|--------|-----------|--------------|---------|
| `large-schema-4` | ~796 KB | ~6 MB | Performance benchmarks |
| `large-schema-5` | ~1.2 MB | ~11 MB | Stress testing at scale |

These anonymized versions of the Airbnb central schema (`-4` being a few years older than `-5`).  These are stored as zip files to reduce repository size.  Because of their size they tend to exhaust the memory of test workers, so use them sparingly.  (Internally they areused for JMH benchmarks measuring schema loading and traversal performance.)

## Recommended Usage Patterns

### Using `TestSchemas`

Use `TestSchemas` for comprehensive black-box testing. The recommended pattern groups tests by schema category using JUnit 5's `assertAll`:

```kotlin
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import viaduct.graphql.schema.test.SchemaDiff
import viaduct.graphql.schema.test.TestSchemas

class MySchemaTest {
    private fun assertMyTransformWorks(fullSdl: String) {
        val original = parseSchema(fullSdl)
        val transformed = myTransform(original)
        SchemaDiff(original, transformed).diff().assertEmpty("\n")
    }

    @Test
    @DisplayName("DIRECTIVE schemas")
    fun `transform works for directive schemas`() {
        assertAll(
            TestSchemas.DIRECTIVE.map { case ->
                Executable { assertMyTransformWorks(case.fullSdl) }
            }
        )
    }

    @Test
    @DisplayName("ENUM schemas")
    fun `transform works for enum schemas`() {
        assertAll(
            TestSchemas.ENUM.map { case ->
                Executable { assertMyTransformWorks(case.fullSdl) }
            }
        )
    }

    // ... repeat for INPUT, INTERFACE, OBJECT, SCALAR, UNION, ROOT, COMPLEX
}
```

If your code uses graphql-java, filter out incompatible schemas:

```kotlin
private fun filterCompatible(schemas: List<TestSchemas.Case>) =
    schemas.filter { it.gjIncompatible == null }

@Test
fun `works for union schemas`() {
    assertAll(
        filterCompatible(TestSchemas.UNION).map { case ->
            Executable { assertMyTransformWorks(case.fullSdl) }
        }
    )
}
```

### Quick Schema Creation in Tests

Use `mkSchema` for simple test cases:

```kotlin
@Test
fun `field lookup works`() {
    val schema = mkSchema("""
        type User {
            name: String
            email: String!
        }
    """)
    val user = schema.types["User"] as ViaductSchema.Object
    assertNotNull(user.field("name"))
    assertFalse(user.field("email")!!.type.isNullable)
}
```

### Testing with Source Locations

Use `mkSchemaWithSourceLocation` when testing source location handling:

```kotlin
@Test
fun `source locations are preserved`() {
    val schema = mkSchemaWithSourceLocation(
        sdl = "type User { name: String }",
        sourceName = "user-module.graphqls"
    )
    val user = schema.types["User"]!!
    assertEquals("user-module.graphqls", user.sourceLocation?.sourceName)
}
```
