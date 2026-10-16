---
title: Node Resolvers
description: Writing resolvers for nodes in Viaduct
---


## Schema

Nodes are types that are resolvable by ID and implement the `Node` interface. A node type gets a corresponding node resolver only when it also declares a type-level `@resolver`.

```graphql
interface Node {
  id: ID!
}

type User implements Node @resolver {
  id: ID!
  firstName: String
  lastName: String
  displayName: String @resolver
}
```

If a type implements `Node` but omits the type-level `@resolver`, Viaduct does not generate a node resolver base class for it.

## Generated base class

Viaduct generates an abstract base class for object types that both implement `Node` and declare `@resolver`. For the `User` example above, Viaduct generates the following code:

=== "Kotlin"

    ```kotlin
    object NodeResolvers {
      abstract class User {
        abstract suspend fun resolve(ctx: Context): viaduct.api.grts.User

        class Context: NodeExecutionContext<viaduct.api.grts.User>
      }

      // If there were more nodes, their base classes would be generated here
    }
    ```

=== "Java"

    ```java
    public final class NodeResolvers {
      public abstract static class User {
        public abstract CompletableFuture<viaduct.java.grts.User> resolve(Context ctx);

        // The generated Context implements NodeExecutionContext<viaduct.java.grts.User>.
      }
    }
    ```


The nested `Context` class is described in more detail [below](#context).

The schema's batching setting determines which resolver method is generated. With
`@resolver(isBatching: true)`, the generated `User` base class declares `batchResolve`
instead of `resolve`:

=== "Kotlin"

    ```kotlin
    abstract suspend fun batchResolve(
      contexts: List<Context>
    ): Map<Context, FieldValue<viaduct.api.grts.User>>
    ```

=== "Java"

    ```java
    public abstract CompletableFuture<Map<Context, FieldValue<viaduct.java.grts.User>>>
        batchResolve(List<Context> contexts);
    ```


## Implementation

Implement a node resolver by subclassing the generated base class, annotating the class with `@Resolver`, and overriding its generated `resolve` or `batchResolve` method.

Here's an example of a non-batching resolver for `User` that calls a user service to get data for a single user:

=== "Kotlin"

    ```kotlin
    @Resolver
    class UserNodeResolver @Inject constructor(
      val userService: UserServiceClient
    ): NodeResolvers.User() {
      override suspend fun resolve(ctx: Context): User {
        // Fetches data for a single User ID
        val data = userService.fetch(ctx.id.internalID)
        return User.Builder(ctx)
          .id(ctx.id)
          .firstName(data.firstName)
          .lastName(data.lastName)
          .build()
      }
    }
    ```

=== "Java"

    ```java
    // Import NodeResolvers from your module's generated resolverbases package.

    @Resolver
    public final class UserNodeResolver extends NodeResolvers.User {
      private final UserServiceClient userService;

      @Inject
      public UserNodeResolver(UserServiceClient userService) {
        this.userService = userService;
      }

      @Override
      public CompletableFuture<User> resolve(Context ctx) {
        var data = userService.fetch(ctx.getId().getInternalID());
        User user = User.builder(ctx)
            .id(ctx.getId())
            .firstName(data.firstName())
            .lastName(data.lastName())
            .build();
        return CompletableFuture.completedFuture(user);
      }
    }
    ```


The Java example assumes `UserServiceClient.fetch` is a synchronous application method returning a record with `firstName()` and `lastName()` accessors. For an asynchronous client, compose its future with `thenApply` instead of blocking with `join()`. Configure your dependency injection framework to create the resolver; the [Java quickstart](../../../getting_started/index.md) uses Micronaut's `@Prototype`.

Points illustrated by this example:

* Dependency injection can be used to provide access to values beyond what’s in the execution context.
* You should not provide values for fields outside the resolver's output selection set. In the example above, we do not set `displayName` when building the `User` [GRT](../generated_code/index.md).

Alternatively, if the user service provides a batch endpoint, you should implement a batch node resolver. Node resolvers typically implement `batchResolve` to avoid the N+1 problem. Learn more about batch resolution [here](batch_resolution.md).

## Context

Both `resolve` and `batchResolve` take `Context` objects as input. For ordinary node resolvers this class implements the language-specific `NodeExecutionContext` API:

=== "Kotlin"

    ```kotlin
    interface NodeExecutionContext<R: NodeObject>: ResolverExecutionContext {
      val id: GlobalID<R>
    }
    ```

=== "Java"

    ```java
    public interface NodeExecutionContext<R extends NodeObject>
        extends ResolverExecutionContext {
      GlobalID<R> getId();
    }
    ```

For the example `User` type, the `R` type would be the User [GRT](../generated_code/index.md).

`NodeExecutionContext` includes the ID of the node to be resolved. Most node resolvers are non-selective, which means they should return the same data for a given node ID regardless of what fields were requested.

Since `NodeExecutionContext` implements `ResolverExecutionContext`, it also includes the utilities provided there, which allow you to:

* Execute [subqueries](subqueries.md)
* Construct [node references](node_references.md)
* Construct [GlobalIDs](../globalids/index.md)

### Non-Selective and Selective Node Resolvers
There are two primary categories of Node Resolver:

1. Non-Selective Node Resolvers: Serve most use cases and are the default option. These resolvers always return the same data for a given node ID and benefit from higher cache hit rates.
2. Selective Node Resolvers: Can vary the response data returned for a given node ID based on the fields requested by the caller. These resolvers are declared directly in SDL with `@resolver(isSelective: true)`.

Selective node resolvers opt in through schema, not by implementing a marker interface:

```graphql
type User implements Node @resolver(isSelective: true) {
  id: ID!
  firstName: String
  expensiveField: String
}
```

In Kotlin, a node declared with `@resolver(isSelective: true)` has a generated `Context` implementing {{ kdoc("viaduct.api.context.SelectiveNodeExecutionContext") }} with typed selection access. Java currently rejects that schema declaration, as described in its tab:

=== "Kotlin"

    ```kotlin
    @Resolver
    class SelectiveUserNodeResolver @Inject constructor(
      val userService: UserServiceClient
    ): NodeResolvers.User() {
      override suspend fun resolve(ctx: Context): User {
        val sel = ctx.selections()
        return User.Builder(ctx)
          .id(ctx.id)
          .apply {
            // Conditionally fetch expensive fields based on what's requested
            if (sel.contains(User.expensiveField)) {
              expensiveField(fetchExpensiveData())
            }
          }
          .build()
      }
    }
    ```

=== "Java"

    Java code generation rejects `@resolver(isSelective: true)` on node types, including batched nodes. See the [Java codegen rejection tests](https://github.com/airbnb/viaduct/blob/main/core/javaapi/codegen/src/test/java/viaduct/x/javaapi/codegen/GraphQLSchemaParserTest.java).

    The experimental `SelectiveNodeExecutionContext` interface remains in the Java API, but its `selections()` accessor is not implemented by the runtime. Kotlin's `SelectionSet.contains` example has no supported Java equivalent. Use an ordinary `@resolver` node that fills its full output selection set, and place expensive optional values behind separate field resolvers.


Non-selective node resolvers should not use selection access, and their generated `Context` does not expose that API. Field-level `@resolver(isSelective: true)` directives inside a node type do not change node resolver generation or make the node resolver selective.

## Output selection set

The node resolver is responsible for resolving all fields, including nested fields, that do not have their own resolver. These are typically core fields that are stored together and can be efficiently retrieved together.

In the example above, the node resolver for `User` is responsible for returning the `id`, `firstName`, and `lastName` fields, but not the `displayName` field, which has its own resolver. Since the ID is an input to the node resolver, it can populate the field directly from `ctx.id` in Kotlin or `ctx.getId()` in Java.

Node resolvers are also responsible for determining whether the node exists. An unbatched resolver should throw an exception when the requested node does not exist. A batch resolver should return a `FieldValue` error for the corresponding `Context`, as described in [Batch Resolution](batch_resolution.md#output).
