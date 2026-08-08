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

Batch node resolvers return one `FieldValue` for each input `Context`, keyed by the original `Context` object. Map order does not matter. Returning the wrong number of entries or a key that was not in the input causes the entire batch to fail. Viaduct discards the returned values and reports an error for every context in that batch.

Batch field resolvers retain their positional list contract: their output list must have the same number of elements as the input list, and each output corresponds to the input context at the same index.

#### FieldValue

Resolved values and explicit per-node failures are wrapped in {{ kdoc("viaduct.api.FieldValue") }}. Use `FieldValue.ofError` with your application's appropriate exception type to report a node that was not found or another per-node failure.

**Usage:**

* `FieldValue.ofValue(v)`: constructs a successfully resolved value, as shown in the example above
* `FieldValue.ofError(e)`: constructs an error value, where `e` is an exception. The corresponding value in the GraphQL response will be null, and there will be an error in the errors array.

### Selection-aware node batches

Declaring a node with `@resolver(isBatching: true, isSelective: true)` combines [selective node resolution](node_resolvers.md#non-selective-and-selective-node-resolvers) with batch resolution:

* Selectivity gives each `Context` its own selection set. The resolver can use it to vary the data it loads and returns for that node.
* Batching lets Viaduct pass several contexts to one `batchResolve` call.

Together, these behaviors mean that one `batchResolve` call can contain several different selection sets. For example:

```text
context 1 (listing 101): title, price
context 2 (listing 102): title, price
context 3 (listing 103): title, reviews { rating }
```

These contexts request two different sets of data: `title` and `price` for contexts 1 and 2, and `title` and `reviews` for context 3. The resolver decides how to handle that difference. It can load one broader data set for the whole batch, make a separate backend request for every context, or divide the contexts into groups. The first option may overfetch, while the second gives up batching. One possible grouping places contexts 1 and 2 together and context 3 in a separate group; the resolver could then make one backend request for each group.

The helpers in `viaduct.api.batch` implement this grouping pattern. Each helper divides the input contexts into groups and runs the supplied block once for each group. The helper then combines the maps returned by the block into the map returned by `batchResolve`. Choose the helper that matches how the resolver groups its backend work:

* `batchBySameSelection` groups contexts that request the same fields at every level. Aliases and field order do not affect grouping. In the example above, contexts 1 and 2 form one group and context 3 forms another.
* `batchByOwnFields` groups contexts that request the same fields directly on the node and ignores differences beneath those fields. For example, `reviews { rating }` and `reviews { author }` produce the own-field key `{ Listing.reviews }`. The group retains both nested selections, so the resolver can account for `rating` and `author` when loading reviews.
* `batchByCustomGrouping` derives a grouping key from each context's `SelectionSet`. Use it when neither built-in grouping matches the resolver's needs. For example, a resolver can group contexts by whether they request `reviews`, regardless of which other fields they request.

Each group is represented by a `Group` containing:

* `contexts`: the original contexts in the group, in input order
* `key`: the value used to form the group
* `selections`: a view over all selections requested by the contexts in the group

`group.selections` represents all selections requested across the group. Use it when building a backend request that loads data for the group as a whole. If the resolver needs to make a decision for one context, use that context's `selections()` instead.

With `batchByOwnFields`, `group.key` is a set of `FieldCoordinate` values identifying the fields selected directly on the node. Nested selections are not part of the key, but they remain available through `group.selections`.

The following resolver makes one backend request for each group of listings that selected the same direct fields:

```kotlin
override suspend fun batchResolve(
  contexts: List<Context>
): Map<Context, FieldValue<Listing>> =
  batchByOwnFields(contexts) { group ->
    val responses = client.fetch(
      ids = group.contexts.map { it.id.internalID },
      fields = group.key,
    )

    group.contexts.associateWith { ctx ->
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
```

In this example, the block passed to `batchByOwnFields` runs once for each group. `group.contexts.associateWith` creates one result for every context in that group and uses the original `Context` objects as map keys. Each time the block runs, its returned map must contain exactly the `Context` objects in `group.contexts`. Viaduct fails the batch if a group context is missing or the map contains any other context.

### When to use `batchResolve`

Use batch resolution whenever you need to fetch data from an external data source that supports batch loading. This solves the N+1 problem and similar issues where multiple parts of a GraphQL query fetch data that can be batched together.

If your resolver does not have external data dependencies, there is generally no benefit to batching.

Those familiar with data loaders may know that they also provide an intra-request cache. In Viaduct, this memoization cache is decoupled from batching, so you do not need batch resolution for caching purposes.
