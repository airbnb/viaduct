# Migrating to the New Resolver Testing API

This guide helps you migrate from the deprecated `viaduct.tenant.testing` API to the new
type-safe `viaduct.api.testing` API.

## Overview

The new resolver testing API provides:

- **Zero runtime dependencies**: Only depends on API-level packages
- **Type-safe testing**: Explicit type parameters eliminate reflection-based type inference
- **Factory/DSL pattern**: Better IDE support and auto-completion
- **Stable API**: Marked with `@StableApi` for long-term support

## Quick Migration Guide

### Step 1: Update BUILD Dependencies

Replace the old dependency:

```python
# Old
"//projects/viaduct/oss/core/tenant/runtime/src/testFixtures/kotlin/viaduct/tenant/testing"
```

With the new dependency:

```python
# New
"//projects/viaduct/oss/core/tenant/api/src/testFixtures/kotlin/viaduct/api/testing"
```

### Step 2: Change Base Class to Factory Pattern

**Before** (inheritance-based):

```kotlin
import viaduct.tenant.testing.DefaultAbstractResolverTestBase

class MyResolverTest : DefaultAbstractResolverTestBase() {
    override fun getSchema() = mySchema

    @Test
    fun testResolver() = runBlocking {
        val result = runFieldResolver(
            resolver = MyFieldResolver(),
            objectValue = myObject,
            arguments = myArgs
        )
        assertEquals(expected, result)
    }
}
```

**After** (factory-based):

```kotlin
import viaduct.api.testing.FieldResolverTester
import viaduct.api.testing.ResolverTester.TesterConfig

class MyResolverTest {
    private val tester = FieldResolverTester.create<
        MyObject,       // Object type
        Query,          // Query type
        MyArguments,    // Arguments type
        MyOutput        // Return type
    >(
        TesterConfig(schemaSDL = MY_SCHEMA_SDL)
    )

    @Test
    fun testResolver() = runBlocking {
        val result = tester.test(MyFieldResolver()) {
            objectValue = myObject
            arguments = myArgs
        }
        assertEquals(expected, result)
    }
}
```

### Step 3: Specify Type Parameters

The new API requires explicit type parameters. Here's how to find them:

1. **Object type (O)**: The type containing the field (e.g., `Wishlist`, `User`)
2. **Query type (Q)**: Usually just `Query`
3. **Arguments type (A)**: Generated arguments class (e.g., `Wishlist_Name_Arguments`)
4. **Return type (R)**: Return type of the resolver (e.g., `String`, `User`)

Look at your resolver class signature for hints:

```kotlin
// From this resolver:
class Wishlist_NameResolver : WishlistResolvers.Name() {
    override suspend fun resolve(ctx: Context): String = ...
}

// Extract these types:
// - Object type: Wishlist (the parent type)
// - Arguments type: Wishlist_Name_Arguments
// - Return type: String (from resolve return type)
```

## Migration Examples

### Field Resolver

```kotlin
// Old
class WishlistNameResolverTest : DefaultAbstractResolverTestBase() {
    override fun getSchema() = testSchema

    @Test
    fun testName() = runBlocking {
        val wishlist = Wishlist.Builder(context)
            .internalName("Hawaii")
            .build()

        val result = runFieldResolver(
            resolver = Wishlist_NameResolver(),
            objectValue = wishlist,
            arguments = Wishlist_Name_Arguments()
        )

        assertEquals("Hawaii", result)
    }
}

// New
class WishlistNameResolverTest {
    private val tester = FieldResolverTester.create<
        Wishlist,
        Query,
        Wishlist_Name_Arguments,
        String
    >(TesterConfig(schemaSDL = SCHEMA_SDL))

    @Test
    fun testName() = runBlocking {
        val wishlist = Wishlist.Builder(tester.context)
            .internalName("Hawaii")
            .build()

        val result = tester.test(Wishlist_NameResolver()) {
            objectValue = wishlist
            arguments = Wishlist_Name_Arguments()
        }

        assertEquals("Hawaii", result)
    }
}
```

### Mutation Resolver

```kotlin
// Old
class CreateWishlistMutationTest : DefaultAbstractResolverTestBase() {
    @Test
    fun testMutation() = runBlocking {
        val result = runMutationFieldResolver(
            resolver = Mutation_CreateWishlistResolver(),
            arguments = Mutation_CreateWishlist_Arguments(
                input = CreateWishlistInput(name = "My List")
            )
        )
        assertNotNull(result)
    }
}

// New
class CreateWishlistMutationTest {
    private val tester = MutationResolverTester.create<
        Query,
        Mutation_CreateWishlist_Arguments,
        CreateWishlistPayload
    >(TesterConfig(schemaSDL = SCHEMA_SDL))

    @Test
    fun testMutation() = runBlocking {
        val result = tester.test(Mutation_CreateWishlistResolver()) {
            arguments = Mutation_CreateWishlist_Arguments(
                input = CreateWishlistInput(name = "My List")
            )
        }
        assertNotNull(result)
    }
}
```

### Node Resolver

```kotlin
// Old
class WishlistNodeResolverTest : DefaultAbstractResolverTestBase() {
    @Test
    fun testNode() = runBlocking {
        val id = globalIDFor(Wishlist.Reflection, "wishlist-123")

        val result = runNodeResolver(
            resolver = WishlistNodeResolver(),
            id = id
        )

        assertNotNull(result)
    }
}

// New
class WishlistNodeResolverTest {
    private val tester = NodeResolverTester.create<Wishlist>(
        TesterConfig(schemaSDL = SCHEMA_SDL)
    )

    @Test
    fun testNode() = runBlocking {
        val result = tester.test(WishlistNodeResolver()) {
            id = tester.globalIDFor(Wishlist.Reflection, "wishlist-123")
        }

        assertNotNull(result)
    }
}
```

### Batch Resolver

```kotlin
// Old
class BatchResolverTest : DefaultAbstractResolverTestBase() {
    @Test
    fun testBatch() = runBlocking {
        val results = runFieldBatchResolver(
            resolver = MyBatchResolver(),
            objectValues = listOf(obj1, obj2, obj3)
        )
        assertEquals(3, results.size)
    }
}

// New
class BatchResolverTest {
    private val tester = FieldResolverTester.create<
        MyObject, Query, Arguments.NoArguments, String
    >(TesterConfig(schemaSDL = SCHEMA_SDL))

    @Test
    fun testBatch() = runBlocking {
        val results = tester.testBatch(MyBatchResolver()) {
            objectValues = listOf(obj1, obj2, obj3)
        }
        assertEquals(3, results.size)
    }
}
```

## Common Issues and Solutions

### Issue: "Cannot infer type parameter"

**Solution**: Explicitly specify all type parameters:

```kotlin
// Won't compile:
val tester = FieldResolverTester.create(config)

// Will compile:
val tester = FieldResolverTester.create<MyObject, Query, MyArgs, MyOutput>(config)
```

### Issue: "Schema resource not found"

**Solution**: Use the correct resource path:

```kotlin
// If schema is at src/test/resources/schema.graphql:
val config = TesterConfig.fromResource("/schema.graphql")

// Or provide SDL directly:
val config = TesterConfig(schemaSDL = """
    type Query {
        hello: String
    }
""".trimIndent())
```

### Issue: "GRT class not found"

**Solution**: Ensure `grtPackage` matches your generated GRTs:

```kotlin
TesterConfig(
    schemaSDL = sdl,
    grtPackage = "com.airbnb.myservice.grts"  // Match your GRT package
)
```

### Issue: "Expected resolver to contain a nested class called 'Context'"

**Solution**: Ensure your resolver extends the correct generated base class:

```kotlin
// Correct - extends generated base class with Context
class Wishlist_NameResolver : WishlistResolvers.Name() {
    override suspend fun resolve(ctx: Context): String = ...
}

// Incorrect - missing Context class
class Wishlist_NameResolver : ResolverBase<String> {
    suspend fun resolve(): String = ...  // No Context!
}
```

## API Reference

### FieldResolverTester

```kotlin
interface FieldResolverTester<O : Object, Q : Query, A : Arguments, R : CompositeOutput> {
    val context: ExecutionContext
    val config: TesterConfig

    suspend fun test(resolver: ResolverBase<R>, block: FieldTestConfig.() -> Unit): R
    suspend fun testBatch(resolver: ResolverBase<R>, block: BatchFieldTestConfig.() -> Unit): List<FieldValue<R>>

    companion object {
        fun <O, Q, A, R> create(config: TesterConfig): FieldResolverTester<O, Q, A, R>
    }
}
```

### MutationResolverTester

```kotlin
interface MutationResolverTester<Q : Query, A : Arguments, R : CompositeOutput> {
    val context: ExecutionContext
    val config: TesterConfig

    suspend fun test(resolver: ResolverBase<R>, block: MutationTestConfig.() -> Unit): R

    companion object {
        fun <Q, A, R> create(config: TesterConfig): MutationResolverTester<Q, A, R>
    }
}
```

### NodeResolverTester

```kotlin
interface NodeResolverTester<R : NodeObject> {
    val context: ExecutionContext
    val config: TesterConfig

    suspend fun test(resolver: NodeResolverBase<R>, block: NodeTestConfig.() -> Unit): R
    suspend fun testBatch(resolver: NodeResolverBase<R>, block: BatchNodeTestConfig.() -> Unit): List<FieldValue<R>>

    companion object {
        fun <R> create(config: TesterConfig): NodeResolverTester<R>
    }
}
```

### TesterConfig

```kotlin
data class TesterConfig(
    val schemaSDL: String,
    val grtPackage: String = "viaduct.api.grts",
    val classLoader: ClassLoader = ClassLoader.getSystemClassLoader()
) {
    companion object {
        fun fromResource(resourcePath: String, grtPackage: String, classLoader: ClassLoader): TesterConfig
    }
}
```

## Benefits of the New API

1. **Compile-time type checking**: Type errors are caught at compile time, not runtime
2. **Better IDE support**: Full auto-completion for DSL configuration
3. **No inheritance required**: Tests are simpler without base class dependencies
4. **Zero runtime dependencies**: Faster compilation, cleaner dependency graph
5. **Stable API guarantees**: Marked with `@StableApi` for long-term support

## Questions?

- See the interface documentation in KDoc
- Contact #viaduct-dev on Slack
- Open an issue on the Treehouse repository
