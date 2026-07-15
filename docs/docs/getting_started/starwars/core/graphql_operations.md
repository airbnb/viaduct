---
title: GraphQL Operations
description: Declaring reusable GraphQL operations with @GraphQLOperation and running them as subqueries in the Star Wars demo app.
---


A resolver often needs to run a **subquery** or **submutation** — a query or mutation against the root `Query`/`Mutation` type from inside the resolver. The usual way is to pass an inline selection string to [`ctx.query()` / `ctx.mutation()`](../../../docs/developers/resolvers/subqueries.md). When the same operation is used from more than one place, or you simply want it validated against the schema at build time, you can declare it **once** with `@GraphQLOperation` and hand the object to `ctx.query()` / `ctx.mutation()`.

!!! warning "Experimental API"
    `@GraphQLOperation` is an experimental Viaduct API (`@ExperimentalApi`) and may change in a future release.

## Declaring a query operation

A GraphQL operation is a Kotlin singleton `object` annotated with `@GraphQLOperation`, extending `QueryFromAnnotation` for a query (or `MutationFromAnnotation` for a mutation). The `filmography` tenant declares one that looks a character up by name and selects its identity fields through the shared `CharacterIdentityFields` named fragment:

{{ codetag("demoapps/starwars/modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/characters/operations/CharacterByNameOperation.kt", "query_operation_example", lang="kotlin") }}


The document is validated against the schema at build time: it must contain exactly one operation, its type must match the base class (`query` for `QueryFromAnnotation`), variables must line up with the fields they feed, and any `...FragmentName` spread must resolve to a `@GraphQLFragment` declared in the same tenant module.

## Running it with `ctx.query()`

Pass the operation object — not a string — to `ctx.query()`, along with a map of its variables. Viaduct inlines the external fragments the operation spreads and executes it against the root `Query` type, returning a typed Query GRT whose getters mirror the schema:

{{ codetag("demoapps/starwars/modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/characters/queries/CharacterSummaryByNameQueryResolver.kt", "query_operation_consumer", lang="kotlin") }}


Try it in GraphiQL:

```graphql
query {
  characterSummaryByName(name: "Luke")
}
```

## Declaring and running a mutation operation

Mutations work the same way, using `MutationFromAnnotation` and `ctx.mutation()`. This operation delegates to the existing `updateCharacterName` mutation:

{{ codetag("demoapps/starwars/modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/characters/operations/RenameCharacterOperation.kt", "mutation_operation_example", lang="kotlin") }}


`ctx.mutation()` is only available from a resolver on the root `Mutation` type, so the consumer backs a `Mutation` field. The submutation shares the parent request's state — including the admin security context — so the `updateCharacterName` resolver it delegates to is still access-checked:

{{ codetag("demoapps/starwars/modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/characters/mutations/RenameCharacterSummaryMutation.kt", "mutation_operation_consumer", lang="kotlin") }}


Run it with the `security-access: admin` header set (see [Request Context](../requestcontext/index.md)):

```graphql
mutation {
  renameCharacterSummary(id: "Q2hhcmFjdGVyOjE=", name: "Ben Kenobi")
}
```

## When to reach for `@GraphQLOperation`

Use a declared operation when the selection is **fixed** (known at build time) and you want it validated against the schema, reused across resolvers, or simply kept out of the resolver body. If the fields you need aren't known until runtime, pass an inline selection string to `ctx.query()` / `ctx.mutation()` instead. And if the parent object or root `Query` already has the data you need before the resolver runs, prefer declaring it in the `@Resolver` annotation's `objectValueFragment` / `queryValueFragment` — see [Subqueries](../../../docs/developers/resolvers/subqueries.md#choosing-between-subqueries-and-resolver-fragments) for the full comparison.

> For the API reference and validation rules, see the [GraphQL Operations developer reference](../../../docs/developers/resolvers/graphql_operations.md). For the fragment the operations above spread, see [Named Fragments](named_fragments.md).
