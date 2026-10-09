---
title: Mutations
description: Mutating data in Viaduct
---


Mutation fields should use the `@resolver` directive to provide a field resolver that executes the mutation. For the following example schema:

```graphql
extend type Mutation {
  publishListing(id: ID! @idOf(type: "Listing")): Listing @resolver
}
```

The resolver might look like:

```kotlin
@Resolver
class PublishListingResolver @Inject constructor(
  val client: ListingServiceClient
) : MutationResolvers.PublishListing() {
  override suspend fun resolve(ctx: Context): Listing {
    client.publish(ctx.arguments.id.internalID)
    return ctx.ref(ctx.arguments.id) // Creates a Listing node reference
  }
}
```

As this example shows, resolvers for mutation fields are almost identical to query field resolvers. A major difference is that `Context` implements `MutationFieldExecutionContext`. This allows mutation field resolvers to execute submutations using `Context.mutation()` in addition to executing [subqueries](subqueries.md) using `Context.query()`.

Mutation field resolvers must be annotated with `@Resolver` and cannot declare `objectValueFragment` or `queryValueFragment`. The RSS restrictions are enforced at build time and runtime startup, including fields on `@namespaceType` types reachable from the mutation root.

Execute reads with `ctx.query()` and writes with `ctx.mutation()` inside `resolve`. In Kotlin, `ctx.mutation()` is available only on root mutation resolver contexts. See [Subqueries](subqueries.md) for declaring operations with `@GraphQLOperation`.
