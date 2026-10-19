---
title: Worked Examples
description: Step-by-step examples for learning Viaduct patterns
---


## Worked Examples

These step-by-step examples will teach you how to build powerful GraphQL APIs with minimal code.

### How It Works:

1. **Write GraphQL schema** with `@resolver` directives

2. **Build your project** - Viaduct generates resolver base classes

3. **Implement resolvers** - Extend generated classes with your logic

4. **Get a working API** - Type-safe, performant GraphQL server

### Example Path

Follow these examples in order to learn Viaduct's resolver patterns. Kotlin examples come from [`core/tenant/tutorials/src/test/kotlin/viaduct/tenant`](https://github.com/airbnb/viaduct/tree/main/core/tenant/tutorials/src/test/kotlin/viaduct/tenant). Java examples below come from the [Java Star Wars application](../getting_started/starwars/index.md), which you can clone, run, and test. Both follow the same schema-first sequence.

#### Field Resolver Tutorial

=== "Kotlin"

    [Open the Kotlin feature-app test](https://github.com/airbnb/viaduct/blob/main/core/tenant/tutorials/src/test/kotlin/viaduct/tenant/tutorial01/SimpleFieldResolverFeatureAppTest.kt)

    **Start here!** Learn the most basic resolver type.

    - **What you'll learn**: How to create computed fields with custom logic

    - **Key concepts**: Field resolvers, Query type resolvers, `@Resolver` annotation

    - **Example**: A simple `foo: String!` field that returns computed values

    - **Generated classes**: `QueryResolvers.*`

=== "Java"

    **What you’ll learn:** implement a computed field by extending its generated resolver base. Java returns `CompletableFuture<T>` and imports `viaduct.java.api.annotations.Resolver`.

    {{ codefile("demoapps/starwars-java/modules/filmography/src/main/java/com/example/starwars/modules/filmography/characters/resolvers/CharacterDisplayNameResolver.java", lang="java") | indent(4) }}

    The parent fields are declared in `objectValueFragment` before they are read. Continue with the [field resolver walkthrough](../getting_started/starwars/core/field_resolvers.md).

#### Node Resolver Tutorial

=== "Kotlin"

    [Open the Kotlin feature-app test](https://github.com/airbnb/viaduct/blob/main/core/tenant/tutorials/src/test/kotlin/viaduct/tenant/tutorial02/SimpleNodeResolverFeatureAppTest.kt)

    Learn the foundation of Viaduct's object system.

    - **What you'll learn**: How to create objects that can be refetched by ID

    - **Key concepts**: Node interface, GlobalID system, Node resolvers

    - **Example**: A `Foo` type that implements `Node` and can be resolved by ID

    - **Generated classes**: `NodeResolvers.*`, Global ID encoding/decoding

    - **Why it matters**: Foundation for all object resolution in Viaduct

=== "Java"

    **What you’ll learn:** retrieve a node by its typed Global ID. Java obtains the internal key with `context.getId().getInternalID()` and returns a generated GRT asynchronously.

    {{ codefile("demoapps/starwars-java/modules/filmography/src/main/java/com/example/starwars/modules/filmography/films/resolvers/FilmNodeResolver.java", lang="java") | indent(4) }}

    The [node resolver walkthrough](../getting_started/starwars/core/node_resolvers.md) includes client queries and missing-node behavior.

#### Simple Resolvers Tutorial

=== "Kotlin"

    [Open the Kotlin feature-app test](https://github.com/airbnb/viaduct/blob/main/core/tenant/tutorials/src/test/kotlin/viaduct/tenant/tutorial03/SimpleResolversFeatureAppTest.kt)

    See how Field and Node resolvers work together.

    - **What you'll learn**: How different resolver types interact

    - **Key concepts**: `objectValueFragment`, accessing parent data, resolver composition

    - **Example**: A `User` with computed `fullName` field using `firstname` + `lastname`

    - **Generated classes**: `QueryResolvers.*`, `UserResolvers.*`, `NodeResolvers.*`

    - **Advanced feature**: Field resolvers accessing parent object data

=== "Java"

    **What you’ll learn:** compose a node with a related object field. The parent’s required fields are declared in the annotation; generated Java getters and builders keep the data flow typed.

    {{ codefile("demoapps/starwars-java/modules/filmography/src/main/java/com/example/starwars/modules/filmography/characters/resolvers/CharacterHomeworldResolver.java", lang="java") | indent(4) }}

    Read [Resolver Integration Patterns](../getting_started/starwars/core/resolver_integrations.md) for how node, field, and batch resolvers compose.

#### Subqueries Tutorial

=== "Kotlin"

    [Open the Kotlin feature-app test](https://github.com/airbnb/viaduct/blob/main/core/tenant/tutorials/src/test/kotlin/viaduct/tenant/tutorial11/SimpleSubqueriesFeatureAppTest.kt)

    Execute subqueries against the Query and Mutation roots from inside a resolver.

    - **What you'll learn**: How to use `ctx.query()` and `ctx.mutation()` to issue imperative subqueries

    - **Key concepts**: `ctx.query()`, `ctx.mutation()`, subquery variable scoping, when to use subqueries vs declarative `@Resolver` fragments

    - **Example**: A field resolver that fetches data from the Query root at runtime; a mutation resolver that calls another mutation via `ctx.mutation()`

    - **Why it matters**: Enables resolvers to fetch related data that isn't known at query planning time

=== "Java"

    **What you’ll learn:** execute a reusable, statically validated query operation from a resolver. Java composes the future with `thenApply`.

    {{ codefile("demoapps/starwars-java/modules/filmography/src/main/java/com/example/starwars/modules/filmography/characters/queries/CharacterSummaryByNameQueryResolver.java", lang="java") | indent(4) }}

    [GraphQL Operations](../getting_started/starwars/core/graphql_operations.md) shows the declaration and corresponding submutation. Java supports string selections and annotated documents; Kotlin’s typed `SelectionSet` overloads do not have a Java equivalent.

### Key Concepts Across These Examples

#### The `@resolver` Directive

```graphql
directive @resolver on FIELD_DEFINITION | OBJECT
```

This tells Viaduct "generate a resolver for this". Different placements create different resolver types:

- **On object types**: `type User @resolver` → Creates `NodeResolvers.User()` (Node Resolver)

- **On Query fields**: `foo: String! @resolver` → Creates `QueryResolvers.Foo()`

- **On object fields**: `fullName: String! @resolver` → Creates `UserResolvers.FullName()`

#### Resolver Types

| Resolver Type             | Purpose                          | Generated Class              | When to Use                                 |
|---------------------------|----------------------------------|------------------------------|---------------------------------------------|
| **Node Resolver**         | Create/fetch objects by GlobalID | `NodeResolvers.TypeName()`   | Objects that implement `Node` interface     |
| **Query Field Resolver**  | Handle root query fields         | `QueryResolvers.FieldName()` | Top-level API entry points                  |
| **Object Field Resolver** | Compute derived fields           | `TypeResolvers.FieldName()`  | Fields that need parent data or computation |

#### The GlobalID System

Viaduct's built-in global object identification:

- **Encodes**: Object type + internal ID into a single string

- **Type-safe**: Can't accidentally use wrong ID for wrong type

- **Utilities**: `ctx.globalIDFor()` to create, `ctx.ref()` to return a lazy node reference

- **Standard**: Follows Relay specification for global object identification

#### Field Resolver Data Access

Field resolvers can access parent object data:

=== "Kotlin"

    ```kotlin
    @Resolver(objectValueFragment = "fragment _ on User { firstname lastname }")
    ```

=== "Java"

    ```java
    @Resolver(objectValueFragment = "fragment _ on User { firstname lastname }")
    ```

- Tells Viaduct exactly what parent data your resolver needs

- Framework automatically fetches required fields

- Available via generated getters such as `context.getObjectValue().getFirstnameOrThrow()` (matching the field name declared in SDL)
