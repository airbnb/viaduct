---
title: Node Resolvers
description: Writing resolvers for nodes in Viaduct
---


## Schema

Nodes are types that are resolvable by ID and implement the `Node` interface. Every object type that implements the `Node` interface has a corresponding node resolver.

```graphql
interface Node {
  id: ID!
}

type User implements Node {
  id: ID!
  firstName: String
  lastName: String
  displayName: String @resolver
}
```

## Generated base class

Viaduct generates an abstract base class for all object types that implement Node. For the `User` example above, Viaduct generates the following code:

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

Implement a node resolver by subclassing the generated base class and overriding exactly one of either `resolve` or `batchResolve`.

Here's an example of a non-batching resolver for `User` that calls a user service to get data for a single user:

```kotlin
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

Both `resolve` and `batchResolve` take `Context` objects as input. This class is an instance of {{ kdoc("viaduct.api.context.NodeExecutionContext") }}:

```kotlin
interface NodeExecutionContext<R: NodeObject>: ResolverExecutionContext {
  val id: GlobalID<R>
  fun selections(): SelectionSet<R>
}
```
For the example `User` type, the `R` type would be the User [GRT](../generated_code/index.md).

`NodeExecutionContext` includes the ID of the node to be resolved, and the selection set for the node being requested by the query. Most node resolvers are not "selective," i.e., they ignore this selection set and thus don’t call this function. In this case, as discussed above, it’s important that the node resolver returns its entire responsibility set.

Since `NodeExecutionContext` implements `ResolverExecutionContext`, it also includes the utilities provided there, which allow you to:

* Execute [subqueries](subqueries.md)
* Construct [node references](node_references.md)
* Construct [GlobalIDs](../globalids/index.md)

### Non-Selective and Selective Node Resolvers
There are two primary categories of Node Resolver:
1. Non-Selective Node Resolvers: Serve most use cases and are the default option. These resolvers always return the same data for a given node ID and benefit from higher cache hit rates.

**Note: Selective Node Resolvers are still under development and not ready for use. This section specifies how they are intended to function.**
2. Selective Node Resolvers: Can vary the response data returned for a given node ID based on the fields selected via the `selections` function on the node. While these resolvers are not cached as efficiently they enable the conditional resolution of potentially expensive fields only when requested.

Selective resolvers must extend the `SelectiveResolver` interface in order to access the `selections` function to read its selection set from the context:

```kotlin
class SelectiveUserNodeResolver @Inject constructor(
  val userService: UserServiceClient
    // Extends SelectiveResolver to enable selections functionality
): NodeResolvers.User(), SelectiveResolver{
  override suspend fun resolve(ctx: Context): User {
    // Uses the selections function to get the selection set from context
    val sel = selections(ctx)
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

## Responsibility set

The node resolver is responsible for resolving all fields, including nested fields, without its own resolver. These are typically core fields that are stored together and can be efficiently retrieved together.

In the example above, the node resolver for `User` is responsible for returning the `firstName` and `lastName` fields, but not the `displayName` field, which has its own resolver. Note that node resolvers are *not* responsible for the `id` field, since the ID is an input to the node resolver.

Node resolvers are also responsible for determining whether the node exists. If a node resolver returns an error value, the entire node in the GraphQL response will be null, not just the fields in the node resolver's responsibility set.
