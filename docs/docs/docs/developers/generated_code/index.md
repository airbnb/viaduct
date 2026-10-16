---
title: Generated Code
description: What code does Viaduct generate for you?
---


## GraphQL Representational Type (GRT)

The API generates two kinds of classes: *GraphQL Representational Types* (GRTs), and *resolver base classes,* described in the [Resolvers](../resolvers/index.md) section.

For each GraphQL type, Viaduct generates classes to represent its values. Kotlin GRTs are in `viaduct.api.grts`; Java GRTs are in `viaduct.java.grts`. We generate two classes for each GraphQL type: a class representing a *value* of a given type, and a builder-class allowing you to construct a value of a given type.  Consider this simple schema:

```graphql
type User implements Node {
  id: ID!
  firstName: String
  lastName: String
  displayName: String
}
```

The signature of the GRT for this type would look approximately like this (method bodies and additional generated interfaces are omitted):

=== "Kotlin"

    ```kotlin
    package viaduct.api.grts

    class User private constructor(...): NodeObject {
      suspend fun getId(alias: String? = null): GlobalID<User>?
      suspend fun getFirstName(alias: String? = null): String?
      suspend fun getLastName(alias: String? = null): String?
      suspend fun getDisplayName(alias: String? = null): String?

      // For each getter, Viaduct also generates an `OrThrow` variant — see
      // "Strict and soft-failing accessors" below.
      suspend fun getIdOrThrow(alias: String? = null): GlobalID<User>
      suspend fun getFirstNameOrThrow(alias: String? = null): String?
      suspend fun getLastNameOrThrow(alias: String? = null): String?
      suspend fun getDisplayNameOrThrow(alias: String? = null): String?

      fun toBuilder(): Builder

      class Builder(ctx: ExecutionContext): DynamicValueOutputBuilder<User> {
        fun id(id: GlobalID<User>): Builder
        fun firstName(firstName: String?): Builder
        fun lastName(lastName: String?): Builder
        fun displayName(displayName: String?): Builder
        override fun build(): User
      }
    }
    ```

=== "Java"

    ```java
    package viaduct.java.grts;

    // Simplified signatures; generated implementations also provide alias overloads.
    public class User implements NodeObject {
      public GlobalID<User> getIdOrThrow();
      public GlobalID<User> getId();
      public String getFirstNameOrThrow();
      public String getFirstName();
      public String getLastNameOrThrow();
      public String getLastName();
      public String getDisplayNameOrThrow();
      public String getDisplayName();

      public Builder toBuilder();
      public static Builder builder(ExecutionContext ctx);

      public static class Builder {
        public Builder id(GlobalID<User> id);
        public Builder firstName(String firstName);
        public Builder lastName(String lastName);
        public Builder displayName(String displayName);
        public User build();
      }
    }
    ```


`NodeObject` marks GRTs for object types implementing GraphQL `Node`: {{ kdoc("viaduct.api.types.NodeObject") }} in Kotlin and [`viaduct.java.api.types.NodeObject`](https://github.com/airbnb/viaduct/blob/main/core/javaapi/api/src/main/java/viaduct/java/api/types/NodeObject.java) in Java. Kotlin builders implement `DynamicValueOutputBuilder<T>`. Java uses a generated nested `Builder`, created with `User.builder(ctx)`, whose `build()` returns a `User`. Java getters are synchronous and have a zero-argument overload plus an overload accepting a `String` alias, such as `user.getDisplayNameOrThrow("label")`.

The values from a fragment on `User` (for example) are accessed through the GRT for `User`.  As a result, the Viaduct GRTs for object types distinguish fields that are "not set," because they haven’t been requested for in the fragment, from fields that are in the fragment and thus are "set."  Accessing a field that has not been set raises a tenant usage error even if the field is nullable. Kotlin throws `UnsetFieldException`. Java normally throws the same exception, but can fall back to its parent `TenantUsageException` when GraphQL type metadata is unavailable; see the [Java accessor implementation](https://github.com/airbnb/viaduct/blob/main/core/javaapi/api/src/main/java/viaduct/java/api/internal/ObjectBase.java).  Also, when you build an object-type value, you do *not* have to set all fields, even if those fields are non-nullable.

### Set or override fields with `toBuilder()`

Object and input GRTs have a `toBuilder()` function that creates a builder containing their existing fields. Use it to set additional fields or override values, then call `build()` to create a new GRT. The original GRT is unchanged.

For example:

=== "Kotlin"

    ```kotlin
    val updatedAddress = address.toBuilder()
        .zip(fullZip)
        .build()
    ```

=== "Java"

    ```java
    var updatedAddress = address.toBuilder()
        .zip(fullZip)
        .build();
    ```


An unresolved node or root-field reference returned by `ctx.ref(...)` cannot be copied: `ctx.ref(...).toBuilder()` throws because the reference does not contain resolved object data. Fetch the fields you need through required selections or `ctx.query()` before calling `toBuilder()` on the result.

### Strict and soft-failing accessors

For every output field, Viaduct generates two accessors: `getX()` and `getXOrThrow()`. The distinction is about how *errors* are surfaced, not about whether the field is nullable in the schema.

`getXOrThrow()` is the strict accessor: it throws on any failure, and its return type is non-null when the schema field is non-null. `getX()` is the soft accessor: it returns `null` for data-side failures (upstream resolver errors, field values stored as errors) so callers can degrade gracefully when a dependency fails, and its return type is always nullable. Tenant misuse (such as accessing a field that was not selected), framework bugs (`FrameworkException`), and `CancellationException` still propagate from **both**, so real bugs and coroutine cancellation remain visible. In particular, the soft accessor does not return `null` for a field that was left out of the selection set — it throws, just as the strict accessor does.

=== "Kotlin"

    ```kotlin
    // Strict: throws on any failure.
    val name: String? = user.getDisplayNameOrThrow()

    // Soft: returns null on data-side failures; tenant/framework bugs still throw.
    val displayName: String? = user.getDisplayName()
    ```

=== "Java"

    ```java
    // Strict: throws on any failure.
    String name = user.getDisplayNameOrThrow();

    // Soft: returns null on data-side failures; tenant/framework bugs still throw.
    String displayName = user.getDisplayName();
    ```


Because the soft accessor discards the underlying data-side error, a caller that needs to distinguish "field is genuinely null" from "field errored and was swallowed" should use `getXOrThrow()` and handle the exception explicitly.

=== "Kotlin"

    !!! warning "`getX()` changed behavior and `getXOrNull()` was removed"

        `getX()` used to be a second name for `getXOrThrow()`. It is now the soft accessor: it returns
        `null` on a data-side failure instead of throwing. `getXOrNull()`, which was a second name for
        the soft accessor, has been removed — replace those call sites with `getX()`. Both are breaking
        changes to generated code, and call sites that relied on the throwing behavior need
        `getXOrThrow()`.

        How the `getX()` change surfaces depends on the field:

        - **A non-null field whose type maps to a Kotlin primitive** — `Boolean!`, `Int!`, `Float!` —
          changes its JVM signature, because the primitive becomes its boxed form: `boolean` becomes
          `java.lang.Boolean`, `int` becomes `java.lang.Integer`. Kotlin call sites that use the value in a
          boolean or arithmetic context stop compiling, and a caller compiled against the old signature
          fails with `NoSuchMethodError`.
        - **A non-null field of any other type** becomes `T?`. Kotlin call sites that use the value
          non-nullably stop compiling; Java call sites see only a changed nullability annotation. The JVM
          signature is unchanged here, so a caller compiled against the previous release keeps linking and
          propagates an unchecked `null` rather than failing at the call. `Node.id` is in this group:
          `getId()` now returns `GlobalID<T>?`.
        - **A field that is already nullable in the schema** keeps its exact signature. These call sites
          keep compiling and switch from throwing to returning `null` with no diagnostic, so they are the
          ones to audit by hand.

=== "Java"

    Java generates the same strict and soft accessor pair. `getXOrThrow()` propagates failures; `getX()` returns `null` for data-side failures. Access to an unselected field still throws from both.

    For a non-null primitive field, the strict accessor may return a primitive, such as `boolean`, while the soft accessor returns a boxed type, such as `Boolean`, so it can return `null`. Resolver results and list elements always use boxed types because they appear as generic type arguments.

    Audit code that assumes `getX()` throws or unboxes its result without checking for `null`. Use `getXOrThrow()` when a dependency failure must fail the current resolver.

Interface, union, and input GRTs follow these language-specific patterns:

=== "Kotlin"

    The GRTs for interface types are Kotlin interfaces with suspending getters (but no builders), while the GRTs for union types are simply Kotlin "tagging" interfaces (i.e., Kotlin interfaces with no members).

    For GraphQL input-object types, the pattern for GRTs is similar to that for output-object types illustrated by `User`.  However, instead of suspending getter functions, the GRTs for input-object types use Kotlin properties for accessing fields.  "Partial" input-object types are not possible: every field of an input-object GRT instance is defined (thus, `UnsetFieldException` is never thrown when accessing their fields).  To achieve this invariant, builders for input-object types are stricter than those for object types: if you call `build` on an `InputType.Builder` instance without having set all required fields of that type, then `build` will raise a runtime error.  A field is "required" if it’s defined with the \`\!\` (non-null) wrapper *and* it has no default value.

    Input and argument GRTs expose `isPresent` for distinguishing omitted fields from fields whose value is `null`. Presence is evaluated after GraphQL defaults are applied: an explicit value or explicit `null` is present, an omitted field with a schema default is present, and an omitted field without a default is absent.

=== "Java"

    Java GRTs for GraphQL interfaces are Java interfaces with synchronous getters; union GRTs are tagging interfaces. Neither has a builder. Build a concrete object GRT implementing the interface or union.

    Input-object GRTs have ordinary Java getters such as `input.getName()` and generated builders such as `UpdateUserInput.builder(ctx)`. Their builders validate required fields and apply GraphQL defaults when `build()` is called. A required field is non-null in the schema and has no default. Input values are not partial output selection sets, so getters do not have strict/soft pairs.

    Input and argument GRTs provide `isPresent` to distinguish omission from explicit `null`. Pass a generated field descriptor, for example:

    ```java
    boolean supplied = input.isPresent(UpdateUserInput.Fields.name);
    ```

    Presence is evaluated after GraphQL defaults: explicit values and explicit `null` are present, an omitted field with a default is present, and an omitted field without a default is absent. Use `toBuilder()` to copy an input before changing it.

Viaduct is what is known as a "schema-first" GraphQL server: developers write GraphQL schema directly, and Viaduct generates "GraphQL representational types (GRTs)" to allow developers to read and write the types expressed by the schema.

=== "Kotlin"

    Any single tenant module consumes only a small fraction of the central schema, so building representational types for the entire schema for every tenant is wasteful. Instead, Viaduct uses "compilation schemas", a per-tenant-module, private view of the central schema consisting of only the schema elements used by a tenant module. This makes Viaduct builds fast and scalable by ensuring that tenant modules are built in parallel and are only rebuilt when needed.

    The tenant module compilation schema is used to generate the GRTs described above. The compilation schema is a *subset* of the total schema visible to a tenant module, a subset generated by looking at the import statements in the tenant module’s source code. Tenant compilation schemas are always valid, self-contained GraphQL schemas.

=== "Java"

    The Java Gradle integration generates a shared GRT artifact from the application's merged schema, including the root types. Each Java tenant module consumes that artifact and generates its own resolver base classes. It does not use Kotlin's per-module import-based compilation-schema pruning. Adding a Java import is therefore not a way to change which GRTs are generated.

    Apply `com.airbnb.viaduct.module-java-gradle-plugin` to the Java tenant module. Register the application with `includeViaductApplication { project(":") ... }` and its modules with nested `includeModule { project(":modules:filmography") ... }` blocks in `settings.gradle.kts`, as shown in the [Java demo settings](https://github.com/airbnb/viaduct/blob/main/demoapps/starwars-java/settings.gradle.kts). The plugin adds generated resolver sources and the shared GRT artifact to the Java source set; do not edit the generated files.

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

Java GRT generation currently excludes fields whose base type is `BackingData`. Do not expect the Kotlin guide's typed backing-data getters or builders in Java; see [Schema Reference](../schema_reference/index.md#backingdata).

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

The generated connection GRT implements the language-specific `Connection<E, N>` interface ({{ kdoc("viaduct.api.types.Connection") }} in Kotlin, `viaduct.java.api.types.Connection` in Java):

=== "Kotlin"

    ```kotlin
    class UserConnection private constructor(...): Connection<UserEdge, User> {
      suspend fun getEdges(alias: String? = null): List<UserEdge>?
      suspend fun getPageInfo(alias: String? = null): PageInfo?
    }
    ```

=== "Java"

    ```java
    // Simplified getter signatures; alias overloads are also generated.
    public class UserConnection implements Connection<UserEdge, User> {
      public List<UserEdge> getEdges();
      public PageInfo getPageInfo();
    }
    ```


The generated edge GRT implements the language-specific `Edge<N>` interface ({{ kdoc("viaduct.api.types.Edge") }} in Kotlin, `viaduct.java.api.types.Edge` in Java):

=== "Kotlin"

    ```kotlin
    class UserEdge private constructor(...): Edge<User> {
      suspend fun getNode(alias: String? = null): User?
      suspend fun getCursor(alias: String? = null): String?
    }
    ```

=== "Java"

    ```java
    // Simplified getter signatures; alias overloads are also generated.
    public class UserEdge implements Edge<User> {
      public User getNode();
      public String getCursor();
    }
    ```


The Java `Connection` and `Edge` interfaces are experimental.

See [Pagination](../pagination/index.md) for details on building connection responses.

## PageInfo

`PageInfo` is a built-in type that provides pagination metadata for connections. Unlike other types that are generated per-tenant, `PageInfo` is part of Viaduct's default schema and is automatically available to all connection types.

When a custom `PageInfo` type is defined in the schema, Viaduct validates that it conforms exactly to the [Relay Connection specification](https://relay.dev/graphql/connections.htm){:target="_blank"}. Custom fields and directives are not permitted on the `PageInfo` type.

See [PageInfo in the Pagination guide](../pagination/index.md#pageinfo) for details on automatic handling and validation rules.
