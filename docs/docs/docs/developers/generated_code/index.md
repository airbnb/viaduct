---
title: Generated Code
description: What code does Viaduct generate for you?
---


## GraphQL Representational Type (GRT)

The API generates two kinds of classes: *GraphQL Representational Types* (GRTs), and *resolver base classes,* described in the [Resolvers](../resolvers/index.md) section.

For each GraphQL type, Viaduct generates a number of Kotlin classes to represent its values.  These classes are found in the `viaduct.api.grts` package. We generate two classes for each GraphQL type: a class representing a *value* of a given type, and a builder-class allowing you to construct a value of a given type.  Consider this simple schema:

```graphql
type User implements Node {
  id: ID!
  firstName: String
  lastName: String
  displayName: String
}
```

The signature of the GRT for this type would look approximately like this:

```kotlin
package viaduct.api.grts

class User private constructor(...): NodeObject {
  suspend fun getId(alias: String? = null): GlobalID<User>
  suspend fun getFirstName(alias: String? = null): String?
  suspend fun getLastName(alias: String? = null): String?
  suspend fun getDisplayName(alias: String? = null): String?

  // For each getter, Viaduct also generates an `OrNull` variant — see
  // "Soft-failing accessors with getXOrNull()" below.
  suspend fun getIdOrNull(alias: String? = null): GlobalID<User>?
  suspend fun getFirstNameOrNull(alias: String? = null): String?
  suspend fun getLastNameOrNull(alias: String? = null): String?
  suspend fun getDisplayNameOrNull(alias: String? = null): String?

  class Builder(ctx: ExecutionContext): DynamicValueOutputBuilder<User> {
    fun id(id: GlobalID<User>): Builder
    fun firstName(firstName: String?): Builder
    fun lastName(lastName: String?): Builder
    fun displayName(displayName: String?): Builder
    override fun build(): User
  }
}
```

{{ kdoc("viaduct.api.types.NodeObject") }} is a tagging interface (i.e., an interface with no methods) for GRTs representing GraphQL object types.  `DynamicValueOutputBuilder` is an interface for builders of such types (it is parameterized on `T` and defines a `build` function that returns a `T`).

The values from a fragment on `User` (for example) are accessed through the GRT for `User`.  As a result, the Viaduct GRTs for object types distinguish fields that are "not set," because they haven’t been requested for in the fragment, from fields that are in the fragment and thus are "set."  If you attempt to access a field that has not been set, a `UnsetFieldException` exception will be thrown, even if that field is nullable.  Also, when you build an object-type value, you do *not* have to set all fields, even if those fields are non-nullable.

### Soft-failing accessors with `getXOrNull()`

For every generated `getX()` accessor, Viaduct also generates a matching `getXOrNull()` variant. The distinction is **not** about whether the field is nullable in the schema — both forms already return `T?` when the schema field is nullable. It is about how *errors* are surfaced: `getX()` throws on any failure, while `getXOrNull()` returns `null` for data-side failures (upstream resolver errors, field values stored as errors) so callers can degrade gracefully when a dependency fails. Tenant misuse (e.g. accessing a field that wasn't selected, throwing `UnsetFieldException`), framework bugs (`FrameworkException`), and `CancellationException` still propagate, so real bugs and coroutine cancellation remain visible.

```kotlin
// Strict: throws on any failure.
val name: String? = user.getDisplayName()

// Soft: returns null on data-side failures; tenant/framework bugs still throw.
val nameOrNull: String? = user.getDisplayNameOrNull()
```

Because `getXOrNull()` discards the underlying data-side error, a caller that needs to distinguish "field is genuinely null" from "field errored and was swallowed" should use `getX()` and handle the exception explicitly.

The GRTs for interface types are Kotlin interfaces with suspending getters (but no builders), while the GRTs for union types are simply Kotlin "tagging" interfaces (i.e., Kotlin interfaces with no members).

For GraphQL input-object types, the pattern for GRTs is similar to that for output-object types illustrated by `User`.  However, instead of suspending getter functions, the GRTs for input-object types use Kotlin properties for accessing fields.  "Partial" input-object types are not possible: every field of an input-object GRT instance is defined (thus, `UnsetFieldException` is never thrown when accessing their fields).  To achieve this invariant, builders for input-object types are stricter than those for object types: if you call `build` on an `InputType.Builder` instance without having set all required fields of that type, then `build` will raise a runtime error.  A field is "required" if it’s defined with the \`\!\` (non-null) wrapper *and* it has no default value.

Viaduct is what is known as a "schema-first" GraphQL server: developers write GraphQL schema directly, and Viaduct generates "GraphQL representational types (GRTs)" to allow developers to read and write the types expressed by the schema.

Any single tenant module consumes only a small fraction of the central schema, so building representational types for the entire schema for every tenant is wasteful. Instead, Viaduct uses "compilation schemas", a per-tenant-module, private view of the central schema consisting of only the schema elements used by a tenant module. This makes Viaduct builds fast and scalable by ensuring that tenant modules are built in parallel and are only rebuilt when needed.

The tenant module compilation schema is used to generate the GRTs described above. The compilation schema is a *subset* of the total schema visible to a tenant module, a subset generated by looking at the import statements in the tenant module’s source code. Tenant compilation schemas are always valid, self-contained GraphQL schemas.

[//]: # (## Addressing an unresolved GraphQL object type)

[//]: # ()
[//]: # (The algorithm for computing the compilation schema will occasionally miss a needed type, leading to a type-not-found error during compilation.  In these cases you will need to include types used in the fragments in the compilation schema by adding them to your tenant’s explicit\_compilation\_schema\_types.txt file.)

[//]: # ()
[//]: # (If a GraphQL object type is missing, the build will throw an unresolved reference error:)

[//]: # ()
[//]: # (```)

[//]: # (error: unresolved reference: NewType)

[//]: # ()
[//]: # (            import com.example.generated.schema.NewType)

[//]: # ()
[//]: # (             ^)

[//]: # (```)

[//]: # ()
[//]: # (To resolve this, add the field's GraphQL type to your tenant's explicit\_compilation\_schema\_types.txt file and rebuild.)

## Connection and Edge Types

For GraphQL types marked with `@connection` and `@edge` directives, Viaduct generates GRTs that implement pagination interfaces. Consider this schema:

```graphql
type UserConnection @connection {
  edges: [UserEdge!]!
  pageInfo: PageInfo!
}

type UserEdge @edge {
  node: User!
  cursor: String!
}
```

The generated connection GRT implements {{ kdoc("viaduct.api.types.Connection") }}:

```kotlin
class UserConnection private constructor(...): Connection<UserEdge, User> {
  suspend fun getEdges(alias: String? = null): List<UserEdge>
  suspend fun getPageInfo(alias: String? = null): PageInfo
}
```

The generated edge GRT implements {{ kdoc("viaduct.api.types.Edge") }}:

```kotlin
class UserEdge private constructor(...): Edge<User> {
  suspend fun getNode(alias: String? = null): User
  suspend fun getCursor(alias: String? = null): String
}
```

See [Pagination](../pagination/index.md) for details on building connection responses.

## PageInfo

`PageInfo` is a built-in type that provides pagination metadata for connections. Unlike other types that are generated per-tenant, `PageInfo` is part of Viaduct's default schema and is automatically available to all connection types.

When a custom `PageInfo` type is defined in the schema, Viaduct validates that it conforms exactly to the [Relay Connection specification](https://relay.dev/graphql/connections.htm){:target="_blank"}. Custom fields and directives are not permitted on the `PageInfo` type.

See [PageInfo in the Pagination guide](../pagination/index.md#pageinfo) for details on automatic handling and validation rules.
