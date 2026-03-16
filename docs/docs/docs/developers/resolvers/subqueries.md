---
title: Subqueries
description: Executing subqueries in resolvers
---

## ctx.query()

`ctx.query()` executes a GraphQL query against the root `Query` type from inside a resolver. The result is a typed GRT object with accessor methods for each selected field.

```kotlin
@Resolver(
  "fragment _ on User { id firstName lastName }"
)
class UserDisplayNameResolver: UserResolvers.DisplayName() {
    override suspend fun resolve(ctx: Context): String? {
        val id = ctx.objectValue.getId()
        val fn = ctx.objectValue.getFirstName()
        val ln = ctx.objectValue.getLastName()

        // determine if user is the logged-in user, in which case
        // we add a suffix to their displayName
        // loads a selection set on the root Query object
        val query = ctx.query("{ viewer { user { id } } }")
        val isViewer = id == query.getViewer()?.getUser()?.getId()
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

The selection string uses standard GraphQL selection syntax — fields, arguments, inline fragments, and aliases all work. This is sometimes called an "imperative subquery," as opposed to the declarative approach of specifying data dependencies in the `@Resolver` annotation.

Use `ctx.query()` when the selections you need aren't known until runtime. If you know what fields you need at registration time, prefer declaring them in the `@Resolver` annotation's `objectValueFragment` or `queryValueFragment` instead.

### Variables

Pass variables to a subquery with the `variables` parameter:

```kotlin
val query = ctx.query(
    "{ listing(id: \$listingId) { title coverPhoto { url } } }",
    variables = mapOf("listingId" to listingId)
)
val title = query.getListing()?.getTitle()
```

Subquery variables are scoped to the subquery itself. They don't inherit from the parent request's variables, and they don't leak back. Two subqueries with the same selection string but different variables are fully independent.

### Async field access

The field getters on a subquery result are suspend functions. Your resolver can continue executing before the subquery has fully resolved — if you access a field that hasn't resolved yet, the getter suspends until the value is available.

If you access a field that wasn't part of your selection string, you'll get an `UnsetFieldException` at runtime.

## ctx.mutation()

Mutation field resolvers can execute submutations via `ctx.mutation()`. This works the same way as `ctx.query()`, but runs against the root `Mutation` type and executes top-level fields serially (matching standard GraphQL mutation semantics).

`ctx.mutation()` is only available in mutation resolver contexts. The type system prevents calling it from query resolvers at compile time.

```kotlin
@Resolver
class UpdateAndPublishResolver @Inject constructor(
  val client: ListingServiceClient
) : MutationResolvers.UpdateAndPublish() {
    override suspend fun resolve(ctx: Context): Listing {
        client.update(ctx.arguments.input)
        val result = ctx.mutation(
            "{ publishListing(id: \$id) { id title } }",
            variables = mapOf("id" to ctx.arguments.id)
        )
        return ctx.nodeFor(ctx.arguments.id)
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

Subquery failures surface as `SubqueryExecutionException`:

- Accessing a field not in the selection string throws `UnsetFieldException`
- Invalid selection syntax causes a plan build failure, wrapped in `SubqueryExecutionException`
- Field resolution errors flow into the result's error list, the same as top-level execution errors

Errors from subqueries are attributed separately from the parent query, so they won't silently contaminate the parent result.

## Choosing between subqueries and @Resolver fragments

The core distinction is *when* the engine learns what data you need. With `@Resolver` fragments (`objectValueFragment`, `queryValueFragment`), the engine sees your data requirements at query planning time. It fetches the data before your resolver runs, and it batches and deduplicates identical field requests across all instances of the resolver in the same request. With `ctx.query()`, the engine doesn't know what you need until your resolver calls it, so each call triggers a separate execution with its own query plan.

| Approach | Use when |
|----------|----------|
| `objectValueFragment` in `@Resolver` | Your resolver needs fields from the parent object, known ahead of time |
| `queryValueFragment` in `@Resolver` | Your resolver needs fields from the root Query, known ahead of time |
| `ctx.query()` | Which fields you need depends on runtime data or conditional logic |
| `ctx.mutation()` | You need to execute another mutation from a mutation resolver |
