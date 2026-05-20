---
title: Testing
description: Testing Tenant Module Code
---


## Overview

At their core, resolvers are functions: they take inputs and produce outputs. That makes them a natural fit for straightforward JUnit-style unit tests.

The catch is that resolver inputs are highly stylized. Rather than plain method parameters, a resolver receives an `ExecutionContext` whose shape — which arguments are present, what the parent object looks like, what query values are available — is determined by the resolver's `@Resolver` annotation and the schema fields it serves. Constructing a valid `ExecutionContext` by hand is tedious and error-prone.

`ResolverTestBase` removes that friction. It provides a type-safe DSL for building `ExecutionContext` values that exactly match what a given resolver expects, so tests stay focused on behavior rather than wiring.

A minimal example:

```kotlin
@OptIn(ExperimentalApi::class)
class FooLabelResolverTest : ResolverTestBase() {
    @Test
    fun `returns label`() = runBlocking {
        val result = runFieldResolver(FooLabelResolver()) {
            objectValue = Foo.of(context) { label("bar") }
        }
        assertEquals("bar", result)
    }
}
```

The sections below cover each resolver type and the full set of available spec properties.

---

## `ResolverTestBase` *(Experimental)*

> **Note:** These APIs are marked `@ExperimentalApi` and may change in future releases.

### Setup

Extend `ResolverTestBase` — no additional configuration needed. Resolvers run in isolation with no HTTP, DI framework, or Spring startup. The schema loads automatically from classpath resources.

```kotlin
@OptIn(ExperimentalApi::class)
class FooResolverTest : ResolverTestBase() {
    @Test
    fun `name of test`() = runBlocking {
        val result = runFieldResolver(FooLabelResolver()) {
            objectValue = ...
            arguments = ...
        }
        assertEquals(..., result)
    }
}
```

The spec lambda (`{ objectValue = ..., arguments = ... }`) is how you supply the resolver's inputs. The next section explains how to construct those values.

### Constructing Test Inputs

The `context: ExecutionContext` property is available in every test. Use it with the `Type.of(context) { … }` DSL to build GRT objects and arguments:

```kotlin
val foo = Foo.of(context) { name("bar") }                    // GRT object
val args = Foo_MyField_Arguments.of(context) { limit(10) }   // arguments object
```

The builder form also works but is more verbose:

```kotlin
val foo = Foo.Builder(context).name("bar").build()
```

### Building GlobalIDs

```kotlin
val id = globalIDFor(Foo.Reflection, "some-internal-id")
```

Use `globalIDFor` directly on the test class — it is a convenience wrapper around `context.globalIDFor`.

### APIs

Each method runs a specific resolver type via a typed **spec** lambda. The compiler enforces that you only set properties that match the resolver's declared types.

| Method | Spec properties |
|---|---|
| `runFieldResolver` | `objectValue`, `queryValue`, `arguments`, `contextQueryValues` |
| `runFieldBatchResolver` | `objectValues`, `queryValues` |
| `runNodeResolver` | `id` *(required)* |
| `runNodeBatchResolver` | `ids` |
| `runMutationFieldResolver` | `queryValue`, `arguments`, `contextQueryValues`, `contextMutationValues` |

Every spec also has `requestContext` for seeding header/scope data.

---

## Field resolver

Use `runFieldResolver` and set `objectValue` to a GRT built with `Type.of(context)`.

Schema:

```graphql
type Foo {
  label: String @resolver
}
```

```kotlin
@Test
fun `returns label`() = runBlocking {
    val result = runFieldResolver(FooLabelResolver()) {
        objectValue = Foo.of(context) { label("bar") }
    }
    assertEquals("bar", result)
}
```

### With arguments

Set `arguments` alongside `objectValue` using the generated `Type_Field_Arguments.of(context)` DSL.

Schema:

```graphql
type Foo {
  label(uppercase: Boolean): String @resolver
}
```

```kotlin
@Test
fun `returns uppercase label when flag is set`() = runBlocking {
    val result = runFieldResolver(FooLabelResolver()) {
        objectValue = Foo.of(context) { label("bar") }
        arguments = Foo_Label_Arguments.of(context) { uppercase(true) }
    }
    assertEquals("BAR", result)
}
```

---

## Field batch resolver

Use `runFieldBatchResolver` and pass a list of objects as `objectValues`. Call `.get()` on each result to extract the value.

Schema:

```graphql
type Foo {
  label: String @resolver(isBatching: true)
}
```

```kotlin
@Test
fun `resolves label for each foo in batch`() = runBlocking {
    val foos = listOf("a", "b", "c").map { Foo.of(context) { id(globalIDFor(Foo.Reflection, it)) } }

    val results = runFieldBatchResolver(FooLabelBatchResolver()) {
        objectValues = foos
    }

    assertEquals(3, results.size)
    results.forEach { fv -> assertNotNull(fv.get()) }
}
```

`objectValues.size` must equal `queryValues.size` when `queryValues` is provided.

---

## Node resolver

Use `runNodeResolver` and set `id` using `globalIDFor` — this property is required.

Schema:

```graphql
type Foo implements Node @resolver {
  id: ID!
  label: String
}
```

```kotlin
@Test
fun `fetches foo by id`() = runBlocking {
    val result = runNodeResolver(FooNodeResolver()) {
        id = globalIDFor(Foo.Reflection, "42")
    }
    assertEquals("42", result.getId())
}
```

---

## Node batch resolver

Use `runNodeBatchResolver` and pass a list of `GlobalID`s as `ids`. Call `.get()` on each result to extract the resolved object.

Schema:

```graphql
type Foo implements Node @resolver(isBatching: true) {
  id: ID!
  label: String
}
```

```kotlin
@Test
fun `fetches multiple foos by id`() = runBlocking {
    val ids = listOf("1", "2").map { globalIDFor(Foo.Reflection, it) }

    val results = runNodeBatchResolver(FooNodeResolver()) {
        this.ids = ids
    }

    assertEquals(2, results.size)
}
```

---

## Mutation resolver

Use `runMutationFieldResolver` and build the input and arguments with the generated `.of(context)` DSL.

Schema:

```graphql
type Mutation {
  createFoo(input: CreateFooInput!): Foo @resolver
}
```

```kotlin
@Test
fun `creates foo and returns it`() = runBlocking {
    val input = CreateFooInput.of(context) { label("bar") }

    val result = runMutationFieldResolver(CreateFooMutation()) {
        arguments = Mutation_CreateFoo_Arguments.of(context) { input(input) }
    }

    assertEquals("bar", result!!.getLabel())
}
```

---

For worked examples against a real schema, see the [Star Wars Testing examples](../../../getting_started/starwars/testing/index.md).
