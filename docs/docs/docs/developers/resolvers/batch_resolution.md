---
title: Batch Resolution
description: Batch node and field resolvers
---


Both [node resolvers](node_resolvers.md) and [field resolvers](field_resolvers.md) can be implemented using the `batchResolve` function. To opt into batching, declare `@resolver(isBatching: true)` in your schema — Viaduct will then generate only a `batchResolve` method (instead of `resolve`) in the resolver base class. This provides an alternative to the widely used [data loader](https://github.com/graphql/dataloader) pattern.

## The N+1 problem

Consider this example schema:

```graphql
type Query {
  recommendedListings: [Listing] @resolver
}

type Listing implements Node @resolver(isBatching: true) {
  id: ID!
  title: String
}
```

Suppose the query below returns 3 recommended listings. A `Listing` node resolver that makes a call to a listings service to fetch a single listing in the `resolve` function will result in 3 separate calls to the service.

```graphql
query {
  recommendedListings {
    id
    title
  }
}
```

This is the N+1 problem, which is commonly solved by implementing a data loader that batches calls to the listings service. The resolver calls the data loader, which then calls the data source.

## batchResolve

In Viaduct, you implement the generated `batchResolve` function to directly call the data source instead of going through a data loader. Under the hood, Viaduct still uses a data loader to batch requests. However, this data loader is part of Viaduct's framework, not something that application developers need to write and maintain. Here's an example `Listing` batch node resolver:

```kotlin
@Resolver
class ListingNodeResolver @Inject constructor(val client: ListingClient) : NodeResolvers.Listing() {
  override suspend fun batchResolve(contexts: List<Context>): List<FieldValue<Listing>> {
    val listingIDs = contexts.map { it.id.internalID }
    val responses = client.fetch(listingIDs)

    return contexts.map { ctx ->
      val listingID = ctx.id.internalID
      val response = responses[listingID]
      FieldValue.ofValue(
        Listing.Builder(ctx)
          .title(response.title)
          .build()
      )
    }
  }
}
```

### Input

`batchResolve` takes a list of `Context` objects as input. This is the same `Context` object type passed to the non-batching `resolve` function. Viaduct's GraphQL execution engine batches these contexts before passing them to the `batchResolve` function.

### Output

The list that `batchResolve` returns must have the same number of elements as the input list. Each output value corresponds to the input `Context` at the same list index.

#### FieldValue

Notice that the output list elements are wrapped in {{ kdoc("viaduct.api.FieldValue") }} (e.g., `List<FieldValue<Listing>>` in the example above). This represents either a successfully resolved value or an error value.

**Usage:**
* `FieldValue.ofValue(v)`: constructs a successfully resolved value, as shown in the example above
* `FieldValue.ofError(e)`: constructs an error value, where `e` is an exception. The corresponding value in the GraphQL response will be null, and there will be an error in the errors array.

### When to use `batchResolve`

Use batch resolution whenever you need to fetch data from an external data source that supports batch loading. This solves the N+1 problem and similar issues where multiple parts of a GraphQL query fetch data that can be batched together.

If your resolver does not have external data dependencies, there is generally no benefit to batching.

Those familiar with data loaders may know that they also provide an intra-request cache. In Viaduct, this memoization cache is decoupled from batching, so you do not need batch resolution for caching purposes.
