---
title: Field Resolvers
description: Writing resolvers for fields in Viaduct
weight: 2
---

## Schema

All schema fields with the {{< kdoc viaduct.api.Resolver "@resolver" >}} directive have a corresponding field resolver. This directive can only be placed on object, not interface fields.

In this example schema, we've added `@resolver` to the `displayName` field:

```graphql
type User implements Node {
  id: ID!
  firstName: String
  lastName: String
  displayName: String @resolver
}
```

### When to use @resolver

Field resolvers are typically used in the following scenarios:

* Fields with arguments should have their own resolver, since resolvers don't have access to the arguments of nested fields:
  ```graphql
  address(format: AddressFormat): Address @resolver
  ```

* Fields that are backed by a different data source than the core fields on a type should have their own resolver. In the example below, suppose the resolver for `wishlists` is backed by a Wishlist service endpoint, whereas `firstName` and `lastName` are backed by a User service endpoint:
  ```graphql
  firstName: String
  lastName: String
  wishlists: [Wishlist] @resolver
  ```
  This avoids executing the `wishlists` resolver and calling the Wishlist service if the field isn't in the client query.

* Fields that are derived from other fields, such as the `displayName` example shown in more detail below, which is derived from `firstName` and `lastName`. Although this example is simple, in practice there can be complex resolvers that have large required selection sets. This keeps the logic for these fields contained in their own resolvers which is easier to understand and maintain.

## Generated base class

Viaduct generates an abstract base class for all schema fields with the `@resolver` directive. For `User.displayName`, Viaduct generates the following code:

```kotlin
object UserResolvers {
  abstract class DisplayName {
    open suspend fun resolve(ctx: Context): String? =
      throw NotImplementedError()

    open suspend fun batchResolve(contexts: List<Context>): List<FieldValue<String?>> =
      throw NotImplementedError()

    class Context: FieldExecutionContext<User, Query, NoArguments, NotComposite>
  }

  // If there were more User fields with @resolver, their base classes would be generated here
}
```

The nested `Context` class is described in more detail [below](#context).

## Implementation

Implement a field resolver by subclassing the generated base class, and overriding exactly one of either `resolve` or `batchResolve`. Learn more about batch resolution [here](/docs/developers/resolvers/batch_resolution/).

Let’s look at the resolver for `User.displayName`:

```kotlin
@Resolver(
  "fragment _ on User { firstName lastName }"
)
class UserDisplayNameResolver : UserResolvers.DisplayName() {
  override suspend fun resolve(ctx: Context): String? {
    val fn = ctx.objectValue.getFirstName()
    val ln = ctx.objectValue.getLastName()
    return when {
      fn == null && ln == null -> null
      fn == null -> ln
      ln == null -> fn
      else -> "$fn $ln"
    }
  }
}
```

As this example illustrates, the `@Resolver` annotation can contain an optional fragment on the parent type of the field being resolved. We call this fragment the *required selection set* of the resolver. In this case, the required selection set asks for the `firstName` and `lastName` fields of `User`, which are combined to generate the user's display name. If a resolver attempts to access a field that’s not in its required selection set, an `UnsetSelectionException` is thrown at runtime.

The `@Resolver` annotation can also be used to declare data dependencies on the root Query type. Learn more about the annotation [here](/docs/developers/resolvers/resolver_annotation).

**Important clarification:** there are no requirements on the names of these resolver classes: We use `UserDisplayNameResolver` here as an example of a typical name, but that choice is not dictated by the framework.

## Context

Both `resolve` and `batchResolve` take `Context` objects as input. This class is an instance of {{< kdoc viaduct.api.context.FieldExecutionContext >}}:

{{< codefile path="tenant/api/src/main/kotlin/viaduct/api/context/FieldExecutionContext.kt" start="9" end="40" >}}

* `objectValue` gives access to the object that contains the field being resolved. Fields of that object can be accessed, but only if those fields are in the resolver’s required selection set. If the resolver tries to access a field not included within its required selection set, it results in an `UnsetSelectionException` at runtime.

* `queryValue` is similar to `objectValue`, but applies to the root query object of the Viaduct central schema. Like `objectValue`, fields on `queryValue` can only be accessed if they are in the resolver's required selection set.

* `arguments` gives access to the arguments to the resolver. When a field takes arguments, the Viaduct build system will generate a GRT representing the values of those arguments. If `User.displayName` took arguments, for example, Viaduct would generate a type `User_DisplayName_Arguments` having one property per argument taken by `displayName`. In our example, the field execution context for `displayName` is parameterized by the special type `NoArguments` indicating that the field takes no arguments.

* `selections()` returns the selections being requested for this field in the query, same as the `selections` function for the node resolver. The `SelectionSet` type is parameterized by the type of the selection set. For example, in the case of `User`'s node resolver, `selections` returned `SelectionSet<User>`. In the case of `displayName`, `selections` returns `SelectionSet<NotComposite>`, where the special type `NotComposite` indicates that `displayName` does not return a composite type (it returns a scalar instead).

Since {{< kdoc viaduct.api.context.NodeExecutionContext >}} implements {{< kdoc viaduct.api.context.ResolverExecutionContext >}}, it also includes the utilities provided there, which allow you to:
* Execute [subqueries](/docs/developers/resolvers/subqueries)
* Construct [node references](/docs/developers/resolvers/node_references)
* Construct [GlobalIDs](/docs/developers/globalids)

## Responsibility set

For scalar and enum fields like `displayName`, the field resolver is just responsible for resolving the single field. If the field has a node type, the field resolver constructs a node reference using just the node's GlobalID, which tells the engine to run the node resolver. For fields with non-node object types, the field resolver is responsible for all nested fields without its own resolver.
