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
    return ctx.nodeRef(ctx.arguments.id) // Creates a Listing node reference
  }
}
```

As this example shows, resolvers for mutation fields are almost identical to query field resolvers. A major difference is that `Context` implements `MutationFieldExecutionContext`. This allows mutation field resolvers to execute submutations using `Context.mutation()` in addition to executing [subqueries](subqueries.md) using `Context.query()`.

Mutation field resolvers should still be annotated with `@Resolver`. However, they may not provide a required selection set using `objectValueFragment`, since those selections would include other mutation fields. Mutation field resolvers can execute other mutation fields by calling `Context.mutation()` instead.
