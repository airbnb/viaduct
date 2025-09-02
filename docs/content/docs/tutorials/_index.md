---
title: Tutorials
description: Step-by-step guides to using Viaduct
weight: 5
---

# Tutorials

These step-by-step guides will teach you how to build powerful GraphQL APIs with minimal code.

## How It Works:

1. **Write GraphQL schema** with `@resolver` directives

2. **Build your project** - Viaduct generates resolver base classes

3. **Implement resolvers** - Extend generated classes with your logic

4. **Get a working API** - Type-safe, performant GraphQL server

## Tutorial Path

Follow these tutorials in order to master Viaduct's resolver patterns:

### 1. [Field Resolver Tutorial](https://github.com/airbnb/viaduct/blob/main/tenant/runtime/src/test/kotlin/viaduct/tenant/runtime/tutorials/fieldresolver/SimpleFieldResolverFeatureAppTest.kt)

**Start here!** Learn the most basic resolver type.

- **What you'll learn**: How to create computed fields with custom logic

- **Key concepts**: Field resolvers, Query type resolvers, `@Resolver` annotation

- **Example**: A simple `foo: String!` field that returns computed values

- **Generated classes**: `QueryResolvers.*`

### 2. [Node Resolver Tutorial](https://github.com/airbnb/viaduct/blob/main/tenant/runtime/src/test/kotlin/viaduct/tenant/runtime/tutorials/noderesolver/SimpleNodeResolverFeatureAppTest.kt)

Learn the foundation of Viaduct's object system.

- **What you'll learn**: How to create objects that can be refetched by ID

- **Key concepts**: Node interface, GlobalID system, Node resolvers

- **Example**: A `Foo` type that implements `Node` and can be resolved by ID

- **Generated classes**: `Nodes.*`, Global ID encoding/decoding

- **Why it matters**: Foundation for all object resolution in Viaduct

### 3. [Simple Resolvers Tutorial](https://github.com/airbnb/viaduct/blob/main/tenant/runtime/src/test/kotlin/viaduct/tenant/runtime/tutorials/simpleresolvers/SimpleResolversFeatureAppTest.kt)

See how Field and Node resolvers work together.

- **What you'll learn**: How different resolver types interact

- **Key concepts**: `objectValueFragment`, accessing parent data, resolver composition

- **Example**: A `User` with computed `fullName` field using `firstname` + `lastname`

- **Generated classes**: `QueryResolvers.*`, `UserResolvers.*`, `Nodes.*`

- **Advanced feature**: Field resolvers accessing parent object data

## Key Concepts Across All Tutorials

### The `@resolver` Directive

```graphql
directive @resolver on FIELD_DEFINITION | OBJECT
```

This tells Viaduct "generate a resolver for this". Different placements create different resolver types:

- **On object types**: `type User @resolver` → Creates `Nodes.User()` (Node Resolver)

- **On Query fields**: `foo: String! @resolver` → Creates `QueryResolvers.Foo()`

- **On object fields**: `fullName: String! @resolver` → Creates `UserResolvers.FullName()`

### Resolver Types

| Resolver Type             | Purpose                          | Generated Class              | When to Use                                 |
|---------------------------|----------------------------------|------------------------------|---------------------------------------------|
| **Node Resolver**         | Create/fetch objects by GlobalID | `Nodes.TypeName()`           | Objects that implement `Node` interface     |
| **Query Field Resolver**  | Handle root query fields         | `QueryResolvers.FieldName()` | Top-level API entry points                  |
| **Object Field Resolver** | Compute derived fields           | `TypeResolvers.FieldName()`  | Fields that need parent data or computation |

### The GlobalID System

Viaduct's built-in global object identification:

- **Encodes**: Object type + internal ID into a single string

- **Type-safe**: Can't accidentally use wrong ID for wrong type

- **Utilities**: `ctx.globalIDFor()` to create, `ctx.nodeFor()` to resolve

- **Standard**: Follows Relay specification for global object identification

### Field Resolver Data Access

Field resolvers can access parent object data:

```kotlin
@Resolver(objectValueFragment = "fragment _ on User { firstname lastname }")
```

- Tells Viaduct exactly what parent data your resolver needs

- Framework automatically fetches required fields

- Available via type-safe `ctx.objectValue.getFirstname()`
