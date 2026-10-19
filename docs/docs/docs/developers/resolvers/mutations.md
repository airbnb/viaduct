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

=== "Kotlin"

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

=== "Java"

    ```java
    @Resolver
    public final class PublishListingResolver extends MutationResolvers.PublishListing {
      private final ListingServiceClient client;

      public PublishListingResolver(ListingServiceClient client) {
        this.client = client;
      }

      @Override
      public CompletableFuture<Listing> resolve(Context ctx) {
        client.publish(ctx.getArguments().getId().getInternalID());
        return CompletableFuture.completedFuture(ctx.ref(ctx.getArguments().getId()));
      }
    }
    ```


As this example shows, resolvers for mutation fields are almost identical to query field resolvers. A major difference is that `Context` implements `MutationFieldExecutionContext`. This allows mutation field resolvers to execute submutations using `Context.mutation()` in addition to executing [subqueries](subqueries.md) using `Context.query()`. Java contexts return `CompletableFuture` values; compose asynchronous work with `thenCompose` / `thenApply` and return that future. The example assumes a synchronous `client.publish`; an asynchronous client must finish before the node reference is returned.

Mutation field resolvers must be annotated with `@Resolver` and cannot declare `objectValueFragment` or `queryValueFragment`. The RSS restrictions are enforced at build time and runtime startup, including fields on `@namespaceType` types reachable from the mutation root.

Execute reads with `ctx.query()` and writes with `ctx.mutation()` inside `resolve`. In Kotlin, `ctx.mutation()` is available only on root mutation resolver contexts. See [Subqueries](subqueries.md) for declaring operations with `@GraphQLOperation`.

Java uses `viaduct.java.api.context.MutationFieldExecutionContext`. Calling `mutation()` from other Java resolver kinds is rejected at runtime; legacy methods on the common context are deprecated. See the [Java mutation contract tests](https://github.com/airbnb/viaduct/blob/main/core/javaapi/runtime/src/test/java/viaduct/java/runtime/featureapp/mutationcapability/JavaMutationCapabilityContractTest.java).
