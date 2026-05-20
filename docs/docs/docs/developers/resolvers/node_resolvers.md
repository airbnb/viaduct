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

```kotlin
object NodeResolvers {
  abstract class User {
    open suspend fun resolve(ctx: Context): viaduct.api.grts.User =
      throw NotImplementedError()

    open suspend fun batchResolve(contexts: List<Context>): List<FieldValue<viaduct.api.grts.User>> =
      throw NotImplementedError()

    class Context: NodeExecutionContext<viaduct.api.grts.User>
  }

  // If there were more nodes, their base classes would be generated here
}
```

The nested `Context` class is described in more detail [below](#context).

## Implementation

Implement a node resolver by subclassing the generated base class, annotating the class with `@Resolver`, and overriding exactly one of either `resolve` or `batchResolve`.

Here's an example of a non-batching resolver for `User` that calls a user service to get data for a single user:

```kotlin
@Resolver
class UserNodeResolver @Inject constructor(
  val userService: UserServiceClient
): NodeResolvers.User() {
  override suspend fun resolve(ctx: Context): User {
    // Fetches data for a single User ID
    val data = userService.fetch(ctx.id.internalId)
    return User.builder(ctx)
      .firstName(data.firstName)
      .lastName(data.lastName)
      .build()
  }
}
```

Points illustrated by this example:

* Dependency injection can be used to provide access to values beyond what’s in the execution context.
* You should not provide values for fields outside the resolver's responsibility set. In the example above, we do not set `displayName` when building the `User` [GRT](../generated_code/index.md).

Alternatively, if the user service provides a batch endpoint, you should implement a batch node resolver. Node resolvers typically implement `batchResolve` to avoid the N+1 problem. Learn more about batch resolution [here](batch_resolution.md).

## Context

Both `resolve` and `batchResolve` take `Context` objects as input. For ordinary node resolvers this class is an instance of {{ kdoc("viaduct.api.context.NodeExecutionContext") }}:

```kotlin
interface NodeExecutionContext<R: NodeObject>: ResolverExecutionContext {
  val id: GlobalID<R>
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

When a node is declared with `@resolver(isSelective: true)`, the generated resolver `Context` implements {{ kdoc("viaduct.api.context.SelectiveNodeExecutionContext") }} and exposes `ctx.selections()`:

```kotlin
@Resolver
class SelectiveUserNodeResolver @Inject constructor(
  val userService: UserServiceClient
): NodeResolvers.User() {
  override suspend fun resolve(ctx: Context): User {
    val sel = ctx.selections()
    return User.builder(ctx)
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

Non-selective node resolvers should not use `ctx.selections()`, and their generated `Context` does not expose that API. Field-level `@resolver(isSelective: true)` directives inside a node type do not change node resolver generation or make the node resolver selective.

## Responsibility set

The node resolver is responsible for resolving all fields, including nested fields, without its own resolver. These are typically core fields that are stored together and can be efficiently retrieved together.

In the example above, the node resolver for `User` is responsible for returning the `firstName` and `lastName` fields, but not the `displayName` field, which has its own resolver. Note that node resolvers are *not* responsible for the `id` field, since the ID is an input to the node resolver.

Node resolvers are also responsible for determining whether the node exists. If a node resolver returns an error value, the entire node in the GraphQL response will be null, not just the fields in the node resolver's responsibility set.
