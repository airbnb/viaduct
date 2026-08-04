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
  override suspend fun batchResolve(
    contexts: List<Context>
  ): Map<Context, FieldValue<Listing>> {
    val listingIDs = contexts.map { it.id.internalID }
    val responses = client.fetch(listingIDs)

    return contexts.associateWith { ctx ->
      val response = responses[ctx.id.internalID]
      if (response == null) {
        FieldValue.ofError(ListingNotFoundException("Listing ${ctx.id} was not found"))
      } else {
        FieldValue.ofValue(
          Listing.Builder(ctx)
            .title(response.title)
            .build()
        )
      }
    }
  }
}
```

### Input

`batchResolve` takes a list of `Context` objects as input. This is the same `Context` object type passed to the non-batching `resolve` function. Viaduct's GraphQL execution engine batches these contexts before passing them to the `batchResolve` function.

For node resolvers, a single invocation never contains the same decoded internal node ID more than once. If one execution batch contains duplicate internal IDs, Viaduct splits it into concurrent resolver invocations while preserving the selections associated with each context. If a split invocation fails, only the contexts in that invocation fail; the other invocations continue independently.

### Output

Batch node resolvers return a map from every original input `Context` object to its value. Use the exact `Context` instances supplied in `contexts`; map iteration order does not affect result matching. Missing or foreign context keys are rejected.

Batch field resolvers retain their positional list contract: their output list must have the same number of elements as the input list, and each output corresponds to the input context at the same index.

#### FieldValue

Resolved values and explicit per-node failures are wrapped in {{ kdoc("viaduct.api.FieldValue") }}. Use `FieldValue.ofError` with your application's appropriate exception type to report a node that was not found or another per-node failure.

**Usage:**

* `FieldValue.ofValue(v)`: constructs a successfully resolved value, as shown in the example above
* `FieldValue.ofError(e)`: constructs an error value, where `e` is an exception. The corresponding value in the GraphQL response will be null, and there will be an error in the errors array.

### When to use `batchResolve`

Use batch resolution whenever you need to fetch data from an external data source that supports batch loading. This solves the N+1 problem and similar issues where multiple parts of a GraphQL query fetch data that can be batched together.

If your resolver does not have external data dependencies, there is generally no benefit to batching.

Those familiar with data loaders may know that they also provide an intra-request cache. In Viaduct, this memoization cache is decoupled from batching, so you do not need batch resolution for caching purposes.
