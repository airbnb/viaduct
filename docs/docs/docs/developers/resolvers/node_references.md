---
title: Node References
description: Creating node references in resolvers
---


GraphQL resolvers frequently need to link to other `Node` types in the graph. Consider this example, where a `Listing` type has an edge to its `host` user:

```graphql
type Listing implements Node {
  id: ID!
  host: User
  ...
}

type User implements Node {
  id: ID!
  ...
}
```

Rather than requiring the `Listing` resolver to also be responsible for resolving `User` data, the `Listing` resolver can use `Context.nodeRef()` to create a *node reference*. The `nodeRef` function takes a [GlobalID](../globalids/index.md) as input and returns a special [GRT](../generated_code/index.md) for that node:

```kotlin
@Resolver
class ListingNodeResolver @Inject constructor(val client: ListingClient) : NodeResolvers.Listing() {
  override suspend fun resolve(ctx: Context): Listing {
    val data = client.fetch(ctx.id.internalID)
    val hostGlobalID = ctx.globalIDFor(User.Reflection, data.hostID)

    // Creates a node reference as a User GRT
    val host = ctx.nodeRef(hostGlobalID)
    return Listing.builder(ctx)
      .host(host)
      /* ... other fields populated from [data] */
      .build()
  }
}
```

When this resolver returns, the Viaduct engine will invoke the `User` node resolver, as well as any field resolvers on the `User` type, to fetch data for the `Listing.host` field.

This example illustrates a subtle aspect of the "responsibility set" of resolvers, which is that the responsibility for resolving a field with a node type is split between the resolver whose responsibility set contains the field, and the resolver of the node being returned. Specifically, the containing resolver is responsible for resolving the node's `id` field and returning a node reference as illustrated here. From there, the node resolver takes over to resolve the rest of the node's responsibility set.

We noted above that the GRT returned by `nodeRef` is "special." It's special because, in the code that calls `nodeRef`, *only* the `id` field is set; all other fields are not set and will throw an exception on an attempt to read them. If for some reason a resolver needs a *resolved* node rather than a node reference, the resolver can use a [subquery](subqueries.md).
