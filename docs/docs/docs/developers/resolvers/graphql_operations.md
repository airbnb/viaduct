---
title: GraphQL Operations
description: Declaring reusable, build-time-validated GraphQL operations with @GraphQLOperation and executing them via ctx.query / ctx.mutation.
---


`@GraphQLOperation` lets you declare a GraphQL **operation** (a query or a mutation) **once** on a Kotlin singleton `object` and execute it as a [subquery](subqueries.md) with `ctx.query()` / `ctx.mutation()`, instead of passing an inline selection string at every call site. The operation document is validated against the schema at build time.

!!! warning "Experimental API"
    `@GraphQLOperation` is an [experimental API](../api_stability/index.md) (`@ExperimentalApi`). The API surface may change in a future release.

## Declaring an operation

Annotate a Kotlin singleton `object` with `@GraphQLOperation` and have it extend `QueryFromAnnotation` (for a query) or `MutationFromAnnotation` (for a mutation):

```kotlin
@GraphQLOperation("query(\$id: ID!) { user(id: \$id) { id name } }")
object GetUserQuery : QueryFromAnnotation()

@GraphQLOperation("mutation(\$input: SendMessageInput!) { sendMessage(input: \$input) { success } }")
object SendMessageMutation : MutationFromAnnotation()
```

Rules:

* The annotated declaration must be a Kotlin singleton `object`.
* The document must contain **exactly one** operation.
* The operation type must match the base class — a `query` for `QueryFromAnnotation`, a `mutation` for `MutationFromAnnotation`. (An anonymous shorthand document like `{ ... }` counts as a query.) Subscriptions are not supported.
* Variable declarations are validated against the schema.
* The document may spread named fragments (`...FragmentName`) declared with [`@GraphQLFragment`](named_fragments.md) in the **same tenant module**. Reachable fragments are inlined into the operation at build time.

Operations are discovered at build time by scanning for `@GraphQLOperation` (via KSP) and validated against the tenant module's compilation schema.

## Executing a query operation

Pass the operation **object** — not its text — to `ctx.query()`, together with a map of variables. The result is a typed Query GRT, exactly as with the string overload:

```kotlin
@Resolver
class UserLabelResolver : QueryResolvers.UserLabel() {
    override suspend fun resolve(ctx: Context): String? {
        val result = ctx.query(GetUserQuery, mapOf("id" to ctx.arguments.id))
        return result.getUserOrThrow()?.getNameOrThrow()
    }
}
```

## Executing a mutation operation

`ctx.mutation()` accepts a `MutationFromAnnotation` object the same way. As always, `ctx.mutation()` is only available in a resolver on the root `Mutation` type — the type system prevents calling it from a query resolver at compile time:

```kotlin
@Resolver
class SendAndConfirmResolver : MutationResolvers.SendAndConfirm() {
    override suspend fun resolve(ctx: Context): Boolean {
        val result = ctx.mutation(SendMessageMutation, mapOf("input" to ctx.arguments.input))
        return result.getSendMessageOrThrow()?.getSuccessOrThrow() ?: false
    }
}
```

Submutations run against the root `Mutation` type with standard serial semantics and share the parent request's state (data loaders, error accumulation, instrumentation, and request-scoped context such as access checks). See [Subqueries](subqueries.md#schema-and-isolation) for the isolation model.

## Passing the operation text directly

Both base classes expose the raw document as `operationText`, so you can still use the string overloads if you prefer:

```kotlin
ctx.query(GetUserQuery.operationText, mapOf("id" to id))
```

Note that the string overload does **not** inline external `@GraphQLFragment` spreads — pass the operation object itself when the document spreads named fragments.

## Choosing between operations, inline subqueries, and `@Resolver` fragments

| Approach | Use when |
|----------|----------|
| `@GraphQLOperation` + `ctx.query()` / `ctx.mutation()` | The operation is fixed (known at build time) and you want it schema-validated, reused, or kept out of the resolver body — especially if it spreads named fragments |
| Inline string `ctx.query()` / `ctx.mutation()` | The fields you need depend on runtime data or conditional logic |
| `objectValueFragment` / `queryValueFragment` in `@Resolver` | The data comes from the parent object or root `Query` and is known ahead of time, so the engine can fetch and batch it before your resolver runs |

For the underlying subquery execution model, see [Subqueries](subqueries.md). For a worked, runnable example in the Star Wars demo app, see the [GraphQL Operations tutorial](../../../getting_started/starwars/core/graphql_operations.md).
