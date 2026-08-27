---
title: Field Resolvers
description: Writing resolvers for fields in Viaduct
---


## Schema

All schema fields with the `@resolver` directive have a corresponding field resolver. This directive can only be placed on object, not interface fields.

In this example schema, we've added `@resolver` to the `displayName` field:

```graphql
type User implements Node {
  id: ID!
  firstName: String
  lastName: String
  displayName: String @resolver
}
```

If a field resolver needs access to the caller's field selection set, declare that explicitly in SDL:

```graphql
type Query {
  profile: Profile @resolver(isSelective: true)
}
```

That causes the generated resolver `Context` to expose `ctx.selections()`. Without `isSelective: true`, the generated field-resolver context does not expose selection access. On a field of a node type, this only affects that field resolver. It does not make the enclosing node resolver selective.

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

Implement a field resolver by subclassing the generated base class, and overriding exactly one of either `resolve` or `batchResolve`. Learn more about batch resolution [here](batch_resolution.md).

Let’s look at the resolver for `User.displayName`:

```kotlin
@Resolver(
  "fragment _ on User { firstName lastName }"
)
class UserDisplayNameResolver : UserResolvers.DisplayName() {
  override suspend fun resolve(ctx: Context): String? {
    val obj = ctx.getObjectValue()
    val fn = obj.getFirstNameOrThrow()
    val ln = obj.getLastNameOrThrow()
    return when {
      fn == null && ln == null -> null
      fn == null -> ln
      ln == null -> fn
      else -> "$fn $ln"
    }
  }
}
```

As this example illustrates, the `@Resolver` annotation can contain an optional fragment on the parent type of the field being resolved. We call this fragment the *required selection set* of the resolver. In this case, the required selection set asks for the `firstName` and `lastName` fields of `User`, which are combined to generate the user's display name. If a resolver attempts to access a field that’s not in its required selection set, an `UnsetFieldException` is thrown at runtime.

The `@Resolver` annotation can also be used to declare data dependencies on the root Query type. Learn more about the annotation [here](resolver_annotation.md).

**Important clarification:** there are no requirements on the names of these resolver classes: We use `UserDisplayNameResolver` here as an example of a typical name, but that choice is not dictated by the framework.

### Parent fields in required selection sets

Use a field marked `@parent` when a resolver or checker for a child object needs fields from the object that produced it:

```graphql
type Company {
  name: String!
  user: User @resolver
}

type User {
  parent: Company @parent
  companyDisplayName: String @resolver
}
```

The resolver selects the parent field as part of its object required selection set and reads it through the generated GRT:

```kotlin
@Resolver(
  objectValueFragment =
    """
    fragment _ on User {
      parent { name }
    }
    """
)
class UserCompanyDisplayNameResolver : UserResolvers.CompanyDisplayName() {
  override suspend fun resolve(ctx: Context): String? =
    ctx.getObjectValue().getParentOrThrow()?.getNameOrThrow()
}
```

Viaduct resolves `User.parent` to the particular `Company` object from which that `User` was reached. It executes `Company.name` normally if the value is not already available. Parent selections can be nested when each object in the traversal declares its own `@parent` field.

`@parent` is intended for required selection sets, not client queries. Parent fields are tenant-local automatically and are absent from any externally accessible schemas.

The `@parent` field itself must be declared directly on an object type, rather than on an interface or a field inherited from an interface. It may be nullable or non-null, and its base type may be an object, interface, or union, but it cannot return a list, take arguments, or carry `@resolver`. The engine resolves this field from execution ancestry.

The field that produces the child is a separate field: `Company.user` in this example. Across the schema, exactly one non-`@parent` field may have the child type as its unwrapped return type, and that field's containing type must be compatible with the type returned by `User.parent`. The producer may return the child directly, in a list, or in a nested list, and it may carry `@resolver`; the resolver prohibition applies only to the `@parent` field.

The parent target type and the nearest upstream resolver that produces it must not be selective. A non-selective resolver is allowed and forms a boundary, so a selective resolver farther upstream does not invalidate the parent field.

## Context

Both `resolve` and `batchResolve` take `Context` objects as input. This class is an instance of {{ kdoc("viaduct.api.context.FieldExecutionContext") }}:


{{ codefile("core/tenant/api/src/main/kotlin/viaduct/api/context/FieldExecutionContext.kt", lang="kotlin") }}


* `getObjectValue()` returns the object that contains the field being resolved, with all selections eagerly pre-resolved. Fields of that object can be accessed, but only if those fields are in the resolver’s required selection set. If the resolver tries to access a field not included within its required selection set, it results in an `UnsetFieldException` at runtime.

* `getQueryValue()` is similar to `getObjectValue()`, but applies to the root query object of the Viaduct central schema. Like `getObjectValue()`, fields on the returned value can only be accessed if they are in the resolver’s required selection set.

* `arguments` gives access to the arguments to the resolver. When a field takes arguments, the Viaduct build system will generate a GRT representing the values of those arguments. If `User.displayName` took arguments, for example, Viaduct would generate a type `User_DisplayName_Arguments` having one property per argument taken by `displayName`. In our example, the field execution context for `displayName` is parameterized by the special type `NoArguments` indicating that the field takes no arguments.

* For fields declared with `@resolver(isSelective: true)`, the generated `Context` also implements `SelectiveFieldExecutionContext<R>`, which exposes `selections()`. This returns the selections being requested for the field in the query. The `SelectionSet` type is parameterized by the field's output type. For example, if a resolver returns `Profile`, `selections()` returns `SelectionSet<Profile>`. If the field returns a scalar or enum, `selections()` returns `SelectionSet<NotComposite>`. See [Selective Resolution](selective_resolution.md) for more on selective resolvers.

Since {{ kdoc("viaduct.api.context.FieldExecutionContext") }} implements {{ kdoc("viaduct.api.context.ResolverExecutionContext") }}, it also includes the utilities provided there, which allow you to:

* Execute [subqueries](subqueries.md)
* Construct [node references](node_references.md)
* Construct [GlobalIDs](../globalids/index.md)

## Output selection set

For scalar and enum fields like `displayName`, the field resolver is just responsible for resolving the single field. If the field has a node type, the field resolver is responsible for returning a node reference containing just the node's GlobalID (which tells the engine to run the node resolver). For fields with non-node object types, the field resolver is responsible for all direct and nested fields that do not have their own resolver.

Fields with their own resolver are resolved independently when requested. They are not part of the current resolver's output selection set, and setting them in a returned GRT does not prevent their own resolvers from running.

### Do not return a GRT with an incomplete selection set

A GRT is a partial representation, not a complete snapshot of its GraphQL type. It contains exactly the fields selected when it was created:

- `ctx.getObjectValue()` and `ctx.getQueryValue()` contain the fields selected by the resolver's `@Resolver` annotation: `objectValueFragment` for the former and `queryValueFragment` for the latter (the required selection set, or RSS).
- A GRT returned by `ctx.query()` contains the fields in the subquery's selection set.
- A GRT created with a builder contains the fields explicitly set on the builder.

These cases are equivalent. In each case, fields outside that selection set are unset, even when they are nullable. Accessing an unset field raises `UnsetFieldException`.

Aliases are part of the selection as well. If an RSS selects `listingTitle: title`, the GRT contains `title` under the `listingTitle` response name. `getTitle(alias = "listingTitle")` works, but `getTitle()` is still unset. Returning that GRT directly does not satisfy an unaliased `title` selection in the resolver's output.

**In practice, avoid directly returning a GRT from `getObjectValue()`, `getQueryValue()`, or `ctx.query()`.** Its selection set may not include every field the resolver is responsible for returning, which can fail at runtime or produce an incomplete response.

Before returning a GRT, make sure the selection set that created it covers every field the resolver is responsible for returning. For a non-node object output, construct a new GRT with a builder and set the required fields explicitly when the source selection set is insufficient. If the object should be delegated to another resolver, use [`nodeRef`](node_references.md) for node types or a [root field reference](root_field_references.md) for root and namespace object fields instead.

For example, suppose `FeaturedListing` returns a `Listing` and the client requests `id`, `title`, and `coverPhoto`. The following resolver is unsafe because its RSS does not include `coverPhoto`:

```kotlin
// Bad: returns a partial GRT directly.
@Resolver("fragment _ on Query { listing { id title } }")
class FeaturedListingResolver : QueryResolvers.FeaturedListing() {
    override suspend fun resolve(ctx: Context): Listing? {
        return ctx.getObjectValue().getListingOrThrow()
    }
}
```

If `Listing` is a node, return a node reference instead. The node resolver will then resolve the fields requested by the client:

```kotlin
// Good: returns a node reference, not a partial Listing GRT.
@Resolver("fragment _ on Query { listing { id } }")
class FeaturedListingResolver : QueryResolvers.FeaturedListing() {
    override suspend fun resolve(ctx: Context): Listing? {
        val listing = ctx.getObjectValue().getListingOrThrow() ?: return null
        return ctx.nodeRef(listing.getIdOrThrow())
    }
}
```

The same issue can occur if the RSS includes the full output selection set but includes an alias:

```kotlin
// Bad: `formattedNumber` is unset in the response because of the alias
@Resolver("fragment _ on Listing { phoneNumbers { num: formattedNumber } }")
class PrimaryPhoneNumberResolver : ListingResolvers.PrimaryPhoneNumber() {
    override suspend fun resolve(ctx: Context): PhoneNumber? {
        return ctx.getObjectValue().getPhoneNumbersOrThrow().firstOrNull()
    }
}
```

Either omit the alias or recreate the GRT using the builder:

```kotlin
// Good: the returned GRT has `formattedNumber` set.
@Resolver("fragment _ on Listing { phoneNumbers { num: formattedNumber } }")
class PrimaryPhoneNumberResolver : ListingResolvers.PrimaryPhoneNumber() {
    override suspend fun resolve(ctx: Context): PhoneNumber? {
        return ctx.getObjectValue().getPhoneNumbersOrThrow().firstOrNull()?.let {
            PhoneNumber.Builder(ctx)
                .formattedNumber(it.getFormattedNumberOrThrow(alias = "num"))
                .build()
        }
    }
}
```
