---
title: Subqueries
description: Executing subqueries in resolvers
---

## ctx.query()

`ctx.query()` executes an annotated GraphQL query against the root `Query` type from inside a resolver. Declare the operation on a Kotlin singleton `object` that extends `QueryFromAnnotation`, then pass that object to `ctx.query()`. The result is a typed GRT object with accessor methods for each selected field.

```kotlin
import viaduct.api.documents.GraphQLOperation
import viaduct.api.documents.QueryFromAnnotation

@GraphQLOperation("query { viewer { user { id } } }")
private object ViewerQuery : QueryFromAnnotation()

@Resolver(
  "fragment _ on User { id firstName lastName }"
)
class UserDisplayNameResolver: UserResolvers.DisplayName() {
    override suspend fun resolve(ctx: Context): String? {
        val obj = ctx.getObjectValue()
        val id = obj.getIdOrThrow()
        val fn = obj.getFirstNameOrThrow()
        val ln = obj.getLastNameOrThrow()

        val query = ctx.query(ViewerQuery)
        val isViewer = id == query.getViewerOrThrow()?.getUserOrThrow()?.getIdOrThrow()
        val suffix = if (isViewer) " (you!)" else ""

        return when {
            fn == null && ln == null -> null
            fn == null -> ln
            ln == null -> fn
            else -> "$fn $ln$suffix"
        }
    }
}
```

The annotation contains a GraphQL operation document — fields, arguments, inline fragments, and aliases all work. The document is validated against the tenant module's schema at build time and can spread named fragments from that module. Execution through `ctx.query()` is sometimes called an "imperative subquery," as opposed to declaring data dependencies in the `@Resolver` annotation.

Kotlin `ctx.query()` and `ctx.mutation()` accept operation objects, not strings or `SelectionSet` instances. Do not pass `operationText` to them. See [GraphQL Operations](graphql_operations.md) for declaration rules and reusable operations.

Use `ctx.query()` when execution depends on runtime values or control flow. If runtime data determines which fields to fetch, choose among predefined, named operation objects; do not construct a selection string at runtime. See [Runtime branching](graphql_operations.md#runtime-branching). For dependencies that the engine can fetch before your resolver runs, prefer the `@Resolver` annotation's `objectValueFragment` or `queryValueFragment` instead.

### Variables

Declare variables in the operation document and supply their runtime values with the `variables` parameter:

```kotlin
@GraphQLOperation("query(\$listingId: ID!) { listing(id: \$listingId) { title coverPhoto { url } } }")
private object ListingQuery : QueryFromAnnotation()
```

Inside the resolver:

```kotlin
val query = ctx.query(
    ListingQuery,
    variables = mapOf("listingId" to listingId)
)
val title = query.getListingOrThrow()?.getTitleOrThrow()
```

Subquery variables are scoped to the subquery itself. They don't inherit from the parent request's variables, and they don't leak back. Two executions of the same operation object with different variables are fully independent.

### Async field access

The field getters on a subquery result are suspend functions. Your resolver can continue executing before the subquery has fully resolved — if you access a field that hasn't resolved yet, the getter suspends until the value is available.

If you access a field that wasn't selected by the operation you executed, you'll get an `UnsetFieldException` at runtime.

### Subquery results are partial GRTs

The GRT returned by `ctx.query()` contains exactly the fields in the subquery's selection set. It is not a complete snapshot of the GraphQL type.

For guidance on returning subquery GRTs from resolvers, including when to use a builder or a reference (node or root field), see [Do not return a GRT with an incomplete selection set](field_resolvers.md#do-not-return-a-grt-with-an-incomplete-selection-set).

## ctx.mutation()

Mutation field resolvers can execute submutations by passing an annotated `MutationFromAnnotation` object to `ctx.mutation()`. This works the same way as `ctx.query()`, but runs against the root `Mutation` type and executes top-level fields serially (matching standard GraphQL mutation semantics).

`ctx.mutation()` is only available in mutation resolver contexts. The type system prevents calling it from query resolvers at compile time.

```kotlin
import viaduct.api.documents.GraphQLOperation
import viaduct.api.documents.MutationFromAnnotation

@GraphQLOperation("mutation(\$id: ID!) { publishListing(id: \$id) { id title } }")
private object PublishListingMutation : MutationFromAnnotation()

@Resolver
class UpdateAndPublishResolver @Inject constructor(
  val client: ListingServiceClient
) : MutationResolvers.UpdateAndPublish() {
    override suspend fun resolve(ctx: Context): Listing {
        client.update(ctx.arguments.input)
        ctx.mutation(
            PublishListingMutation,
            variables = mapOf("id" to ctx.arguments.id)
        )
        return ctx.ref(ctx.arguments.id)
    }
}
```

Mutation resolvers can freely call `ctx.query()` too. See [Mutations](mutations.md) for more on mutation resolvers.

## Schema and isolation

Subqueries run against the full schema, not any restricted client-facing view. When a resolver issues a subquery, it's consulting the complete graph.

Each subquery gets its own isolated result store, so fields resolved in one subquery don't share results with other subqueries or the parent query. Request-level state *is* shared: data loaders, error accumulation, and instrumentation all carry over from the parent execution.

## Nested subqueries

Subqueries can issue their own subqueries. A resolver invoked during subquery execution has the same `ctx.query()` and `ctx.mutation()` capabilities as any other resolver. All nested subqueries share the parent execution context and request-scoped state.

## Error handling

Annotated operation documents are validated at build time. Runtime failures can still occur:

- Accessing a field not selected by the executed operation throws `UnsetFieldException`
- Engine execution setup or plan build failures surface as `SubqueryExecutionException`
- Field resolution errors flow into the result's error list, the same as top-level execution errors

Errors from subqueries are attributed separately from the parent query, so they won't silently contaminate the parent result.

## Choosing between subqueries and @Resolver fragments

The core distinction is *when* the engine schedules the data you need, not whether the document is known at build time. With `@Resolver` fragments (`objectValueFragment`, `queryValueFragment`), the engine sees your data requirements at query planning time. It fetches the data before your resolver runs, and it batches and deduplicates identical field requests across all instances of the resolver in the same request. With `ctx.query()`, the operation is declared at build time, but your resolver chooses whether and when to execute it, so each call triggers a separate execution.

| Approach | Use when |
|----------|----------|
| `objectValueFragment` in `@Resolver` | Your resolver needs fields from the parent object, known ahead of time |
| `queryValueFragment` in `@Resolver` | Your resolver needs fields from the root Query, known ahead of time |
| `ctx.query(operation, variables)` | Runtime values or conditional logic determine when to execute a subquery or which named query operation to use |
| `ctx.mutation(operation, variables)` | You need to execute a named mutation operation from a mutation resolver |
