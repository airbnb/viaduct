---
title: Resolver Annotation
description: Using the @Resolver annotation
---


Both field resolvers and node resolvers must be annotated with `@Resolver` to be registered. For node resolvers, `@Resolver` must use default (empty) parameters since node resolvers do not yet support required selection sets. This annotation class also allows field resolvers to declare data dependencies in the form of *required selection sets* via `objectValueFragment` and `queryValueFragment`:

=== "Kotlin"

    ```kotlin
    annotation class Resolver(
      @Language("GraphQL") val objectValueFragment: String = "",
      @Language("GraphQL") val queryValueFragment: String = "",
      val variables: Array<Variable> = []
    )
    ```

=== "Java"

    ```java
    public @interface Resolver {
      String objectValueFragment() default "";
      String queryValueFragment() default "";
      Variable[] variables() default {};
    }
    ```


Use the annotation syntax for your resolver language:

=== "Kotlin"

    `@Resolver` is a Kotlin annotation class, so you use its constructor like any other Kotlin class constructor — you can specify parameter names explicitly, or omit them and pass arguments positionally in the declared parameter order. For example, `@Resolver("firstName lastName")` is equivalent to `@Resolver(objectValueFragment = "firstName lastName")` because `objectValueFragment` is the first declared parameter.

=== "Java"

    Import `viaduct.java.api.annotations.Resolver` and `viaduct.java.api.annotations.Variable`.
    Java annotation members must be named: use `@Resolver(objectValueFragment = "firstName lastName")`.
    Use `{...}` for annotation arrays and `@Variable(name = "userId", ...)` for each binding.
    GraphQL `$variable` references need no escaping in Java strings or text blocks.


**objectValueFragment**: a GraphQL fragment on the object type that contains the field being resolved. In the `User.displayName` example below, the fragment must be on the `User` type.

**queryValueFragment**: a GraphQL fragment on the root query type.

**variables**: values of variables used in `objectValueFragment` or `queryValueFragment`.

## Required selection set syntax

A resolver can optionally specify one or both of `objectValueFragment` and `queryValueFragment` using either the shorthand fragment syntax, or full fragment syntax. Values can be accessed using `ctx.getObjectValue()` and `ctx.getQueryValue()`.

### Shorthand syntax

The shorthand fragment syntax omits the `fragment ... on Type { }` declaration and just includes the selections within the fragment body.

Here's an example of what this looks like for a `User.displayName` field. The selections must be on the `User` type:

=== "Kotlin"

    ```kotlin
    @Resolver("firstName lastName")
    class UserDisplayNameResolver : UserResolvers.DisplayName()
    ```

=== "Java"

    ```java
    @Resolver(objectValueFragment = "firstName lastName")
    public final class UserDisplayNameResolver extends UserResolvers.DisplayName {
      @Override
      public CompletableFuture<String> resolve(Context ctx) {
        var user = ctx.getObjectValue();
        String firstName = user.getFirstNameOrThrow();
        String lastName = user.getLastNameOrThrow();
        String name = firstName == null ? lastName
            : lastName == null ? firstName : firstName + " " + lastName;
        return CompletableFuture.completedFuture(name);
      }
    }
    ```

The shorthand fragment syntax can also be used for `queryValueFragment`. The selections must be on the root query type:

=== "Kotlin"

    ```kotlin
    @Resolver(
      queryValueFragment = "node(id: \$userId) { ... on User { firstName lastName } }",
      variables = [Variable("userId", fromArgument = "userId")]
    )
    ```

=== "Java"

    ```java
    @Resolver(
        queryValueFragment = "node(id: $userId) { ... on User { firstName lastName } }",
        variables = {@Variable(name = "userId", fromArgument = "userId")})
    ```


### Full fragment syntax

The full fragment syntax is the regular GraphQL fragment syntax. You can name the fragment whatever you'd like, although we typically use `_` for the fragment name when there's only a single fragment to indicate that the name isn't used anywhere:

=== "Kotlin"

    ```kotlin
    @Resolver("fragment _ on User { firstName lastName }")
    ```

=== "Java"

    ```java
    @Resolver(objectValueFragment = "fragment _ on User { firstName lastName }")
    ```


You can define multiple named fragments and reference them within your main fragment using the standard GraphQL fragment spread syntax (`...FragmentName`):
=== "Kotlin"

    ```kotlin
    @Resolver(
      queryValueFragment = """
      fragment _ on Query {
        node(id: ${'$'}listingId) {
          ... on Listing {
            cover: coverImage {
              ...ImageDetails
            }
            rooms {
              images {
                ...ImageDetails
              }
            }
          }
        }
      }
      fragment ImageDetails on Image {
        url
        caption
      }
      """,
      variables = [Variable("listingId", fromArgument = "listingId")]
    )
    ```

=== "Java"

    ```java
    @Resolver(
        queryValueFragment = """
            fragment _ on Query {
              node(id: $listingId) {
                ... on Listing {
                  cover: coverImage {
                    ...ImageDetails
                  }
                  rooms {
                    images {
                      ...ImageDetails
                    }
                  }
                }
              }
            }
            fragment ImageDetails on Image {
              url
              caption
            }
            """,
        variables = {@Variable(name = "listingId", fromArgument = "listingId")})
    ```


The fragments shown above are defined inline within a single resolver. To share a fragment across **multiple** resolvers in the same tenant module, declare it once as a [named fragment](named_fragments.md) and spread it with `...FragmentName`.

Note that if you have multiple fragments on the type of the main fragment (either the object type or the query type), the primary one needs to be named `Main`:

=== "Kotlin"

    ```kotlin
    @Resolver(
      """
      fragment Main on User {
        firstName
        lastName
        ...UserProfilePhoto
      }
      fragment UserProfilePhoto on User {
        profilePhoto {
          url
          caption
        }
      }
      """
    )
    ```

=== "Java"

    ```java
    @Resolver(objectValueFragment = """
        fragment Main on User {
          firstName
          lastName
          ...UserProfilePhoto
        }
        fragment UserProfilePhoto on User {
          profilePhoto {
            url
            caption
          }
        }
        """)
    ```


## Accessing required selection set values
You can access the required selection set values via the [`Context` object](field_resolvers.md#context) given as input to the field resolver. `ctx.getObjectValue()` and `ctx.getQueryValue()` return [GRTs](../generated_code/index.md) of the object and Query types respectively, with all selections eagerly pre-resolved.

The GRT getter methods correspond to the **schema types**, not the fragment structure. For example, given the listing `queryValueFragment` above:

=== "Kotlin"

    ```kotlin
    // Query.node` field:
    val q = ctx.getQueryValue()
    val listing = q.getNodeOrThrow() as? Listing

    // Get Listing.coverImage, aliased as "cover" in the fragment:
    val coverImage = listing?.getCoverImageOrThrow(alias = "cover")

    // Get the caption field (since GRTs are based on schema types, fragments are irrelevant):
    val coverCaption = coverImage?.getCaptionOrThrow()

    // Access room images
    val roomImages = listing?.getRoomsOrThrow()?.flatMap { it.getImagesOrThrow() }
    ```

=== "Java"

    ```java
    var node = ctx.getQueryValue().getNodeOrThrow();
    if (node instanceof Listing listing) {
      var coverImage = listing.getCoverImageOrThrow("cover");
      String coverCaption = coverImage == null ? null : coverImage.getCaptionOrThrow();
      var rooms = listing.getRoomsOrThrow();
      if (rooms != null) {
        for (var room : rooms) {
          var images = room.getImagesOrThrow();
          // Use the selected image fields here.
        }
      }
    }
    ```


If the resolver tries to access a field not included within its required selection set, it results in an `UnsetFieldException` at runtime.

With `getObjectValue()`/`getQueryValue()`, all selections are eagerly pre-resolved before the returned GRT is accessible, so individual field getters on the returned GRT do not perform further asynchronous work.

## Variables

Any of the fragments in `@Resolver` annotations may use GraphQL variables. These variables can be bound to values in one of 4 ways:

1. Via the `variables` parameter of `@Resolver`, using `fromArgument`
1. Via the `variables` parameter of `@Resolver`, using `fromObjectField`
1. Via the `variables` parameter of `@Resolver`, using `fromQueryField`
1. Via the resolver’s VariableProvider

Combined, these variable sources establish a pool of variables, any of which may be used in either the `objectValueFragment` or the `queryValueFragment` of a resolver.

#### @Resolver variables parameter

Variables may be bound using the `variables` parameter of `@Resolver`, which is an array of `@Variable` annotations. For example, consider this resolver configuration for a field on `MyType` that passes a value from the object as an argument to another field:

=== "Kotlin"

    ```kotlin
    @Resolver(
      objectValueFragment = """
        fragment _ on MyType {
          settings {
            pageSize
          }
          items(first: ${'$'}pageSize)
        }
      """,
      variables = [Variable("pageSize", fromObjectField = "settings.pageSize")]
    )
    ```

=== "Java"

    ```java
    @Resolver(
        objectValueFragment = """
            fragment _ on MyType {
              settings {
                pageSize
              }
              items(first: $pageSize)
            }
            """,
        variables = {
          @Variable(name = "pageSize", fromObjectField = "settings.pageSize")
        })
    ```


This resolver fragment uses a `pageSize` variable. At runtime, the value for this variable will be determined by the value of `settings.pageSize` on `MyType`'s object value. The `fromObjectField` parameter takes a dot-separated path relative to the object value, and the referenced path must be a selection defined in the resolver's `objectValueFragment`.

There are three mutually-exclusive parameters to the `@Variable` class that can be used to set the value of a variable:

1. the `fromArgument` parameter, which binds the variable to a field argument value (`ctx.arguments` in Kotlin or `ctx.getArguments()` in Java). To support nested GraphQL input types, the `fromArgument` string can contain a dot-separated path.
1. the `fromObjectField` parameter just illustrated, which takes a dot-separated path relative to the `objectValue` of an execution. If used, the path must be a selection defined in the resolver's `objectValueFragment`.
1. the `fromQueryField` parameter. This parameter is analogous to `fromObjectField`, but the path describes a selection in the resolver's `queryValueFragment`.

Variables bound with `fromObjectField` or `fromQueryField` cannot be used in the `if` argument of `@skip` or `@include`; such resolvers fail at startup. To select data conditionally on a value from the object or query, select the value in the fragment and fetch the conditional data with `ctx.query()` in the resolver body. Variables bound with `fromArgument` or a `VariablesProvider` may be used in conditional directives.

#### VariablesProvider

The `variables` parameter does not allow arbitrarily-computed values to be used as variables. To support dynamic use cases, use a variables provider. Kotlin uses {{ kdoc("viaduct.api.resolver.VariablesProvider") }}; Java uses [`viaduct.java.api.variables.VariablesProvider`](https://github.com/airbnb/viaduct/blob/main/core/javaapi/api/src/main/java/viaduct/java/api/variables/VariablesProvider.java).

For example, consider a resolver for `MyType.foo` whose required selection set uses variables named `startDate` and `endDate`. To provide dynamically-computed values for these variables, the implementation for `MyTypeResolvers.Foo` may nest a class that implements the `VariablesProvider` interface:

=== "Kotlin"

    ```kotlin
    @Variables(types = "startDate: Date, endDate: Date")
    class Vars : VariablesProvider<MyType_Foo_Arguments> {
        override suspend fun provide(context: VariablesProviderContext<MyType_Foo_Arguments>) =
            LocalDate.now().let {
                mapOf(
                    "startDate" to it,
                    "endDate" to it.plusDays(7)
                )
            }
    }
    ```

=== "Java"

    ```java
    @Variables(types = "startDate: Date, endDate: Date")
    public static final class Vars implements VariablesProvider<MyType_Foo_Arguments> {
      @Override
      public CompletableFuture<Map<String, Object>> provide(
          VariablesProviderContext<MyType_Foo_Arguments> context) {
        LocalDate start = LocalDate.now();
        return CompletableFuture.completedFuture(
            Map.of("startDate", start, "endDate", start.plusDays(7)));
      }
    }
    ```


The value of the `types` parameter to `@Variables` must conform to *VariableDefinitionlist* from [GraphQL Spec](https://spec.graphql.org/draft/#sec-Language.Variables). Both languages pass a `VariablesProviderContext<A>` to `provide`, with access to the field arguments and request context. In Kotlin, read `context.arguments`. In Java, place `Vars` inside the resolver as a `public static` nested class. Its `VariablesProviderContext<A>` exposes `getArguments()` and `getRequestContext()`, and `provide` returns a `CompletableFuture<Map<String, Object>>`. Use `Arguments.NoArguments` for an argument-free field. Import `Variables`, `VariablesProvider`, and `VariablesProviderContext` from the corresponding `viaduct.java.api` packages, plus the standard `java.time`, `java.util`, and `java.util.concurrent` classes used above.
