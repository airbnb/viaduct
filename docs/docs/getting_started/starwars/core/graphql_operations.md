---
title: GraphQL Operations
description: Declaring reusable GraphQL operations with @GraphQLOperation and running them as subqueries in the Star Wars demo app.
---


A resolver often needs to run a **subquery** or **submutation** — a query or mutation against the root `Query`/`Mutation` type from inside the resolver. Declare it with `@GraphQLOperation` and pass the operation object to [`ctx.query()` / `ctx.mutation()`](../../../docs/developers/resolvers/subqueries.md). Both languages support this pattern, including build-time validation and reuse across resolvers. Java also provides string overloads for dynamically constructed selections; Kotlin's operation API takes operation objects.

`@GraphQLOperation` is annotated `@StableApi` in both tenant APIs.

## Declaring a query operation

A GraphQL operation is annotated with `@GraphQLOperation` and extends `QueryFromAnnotation` for a query (or `MutationFromAnnotation` for a mutation). Kotlin uses a singleton `object`; Java uses a class and explicitly exposes an `INSTANCE` to reuse. The `filmography` tenant declares one that looks a character up by name and selects its identity fields through the shared `CharacterIdentityFields` named fragment:

=== "Kotlin"

    {{ codetag("demoapps/starwars/modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/characters/operations/CharacterByNameOperation.kt", "query_operation_example", lang="kotlin") | indent(4) }}

=== "Java"

    {{ codefile("demoapps/starwars-java/modules/filmography/src/main/java/com/example/starwars/modules/filmography/characters/operations/CharacterByNameOperation.java", lang="java") | indent(4) }}


The document is validated against the schema at build time: it must contain exactly one operation, its type must match the base class (`query` for `QueryFromAnnotation`), variables must line up with the fields they feed, and any `...FragmentName` spread must resolve to a `@GraphQLFragment` declared in the same tenant module.

## Running it with `ctx.query()`

Pass the operation object — not a string — to `ctx.query()`, along with a map of its variables. Viaduct inlines the external fragments the operation spreads and executes it against the root `Query` type, returning a typed Query GRT whose getters mirror the schema. Java returns it asynchronously in a `CompletableFuture`, so the consumer uses `thenApply`:

=== "Kotlin"

    {{ codetag("demoapps/starwars/modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/characters/queries/CharacterSummaryByNameQueryResolver.kt", "query_operation_consumer", lang="kotlin") | indent(4) }}

=== "Java"

    {{ codefile("demoapps/starwars-java/modules/filmography/src/main/java/com/example/starwars/modules/filmography/characters/queries/CharacterSummaryByNameQueryResolver.java", lang="java") | indent(4) }}


Try it in GraphiQL:

```graphql
query {
  characterSummaryByName(name: "Luke")
}
```

## Declaring and running a mutation operation

Mutations work the same way, using `MutationFromAnnotation` and `ctx.mutation()`. This operation delegates to the existing `updateCharacterName` mutation:

=== "Kotlin"

    {{ codetag("demoapps/starwars/modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/characters/operations/RenameCharacterOperation.kt", "mutation_operation_example", lang="kotlin") | indent(4) }}

=== "Java"

    {{ codefile("demoapps/starwars-java/modules/filmography/src/main/java/com/example/starwars/modules/filmography/characters/operations/RenameCharacterOperation.java", lang="java") | indent(4) }}


`ctx.mutation()` is only available from a resolver on the root `Mutation` type, so the consumer backs a `Mutation` field. The submutation shares the parent request's state — including the admin security context — so the `updateCharacterName` resolver it delegates to is still access-checked:

=== "Kotlin"

    {{ codetag("demoapps/starwars/modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/characters/mutations/RenameCharacterSummaryMutation.kt", "mutation_operation_consumer", lang="kotlin") | indent(4) }}

=== "Java"

    {{ codefile("demoapps/starwars-java/modules/filmography/src/main/java/com/example/starwars/modules/filmography/characters/mutations/RenameCharacterSummaryMutation.java", lang="java") | indent(4) }}


Run it with the `security-access: admin` header set (see [Request Context](../requestcontext/index.md)):

```graphql
mutation {
  renameCharacterSummary(id: "Q2hhcmFjdGVyOjE=", name: "Ben Kenobi")
}
```

## When to reach for `@GraphQLOperation`

Use a declared operation when the selection is **fixed** (known at build time) and you want it validated against the schema, reused across resolvers, or simply kept out of the resolver body. Java's string overloads can handle selections constructed at runtime; these are not Kotlin overloads. If the parent object or root `Query` already has the data you need before the resolver runs, prefer declaring it in the `@Resolver` annotation's `objectValueFragment` / `queryValueFragment` — see [Subqueries](../../../docs/developers/resolvers/subqueries.md#choosing-between-subqueries-and-resolver-fragments) for the full comparison.

> For the API reference and validation rules, see the [GraphQL Operations developer reference](../../../docs/developers/resolvers/graphql_operations.md). For the fragment the operations above spread, see [Named Fragments](named_fragments.md).
