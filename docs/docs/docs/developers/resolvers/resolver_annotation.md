---
title: Resolver Annotation
description: Using the @Resolver annotation
---


Both field resolvers and node resolvers must be annotated with `@Resolver` to be registered. For node resolvers, `@Resolver` must use default (empty) parameters since node resolvers do not yet support required selection sets. This annotation class also allows field resolvers to declare data dependencies in the form of *required selection sets* via `objectValueFragment` and `queryValueFragment`:

```kotlin
annotation class Resolver(
  @Language("GraphQL") val objectValueFragment: String = "",
  @Language("GraphQL") val queryValueFragment: String = "",
  val variables: Array<Variable> = []
)
```

`@Resolver` is a Kotlin annotation class, so you use its constructor like any other Kotlin class constructor — you can specify parameter names explicitly, or omit them and pass arguments positionally in the declared parameter order. For example, `@Resolver("firstName lastName")` is equivalent to `@Resolver(objectValueFragment = "firstName lastName")` because `objectValueFragment` is the first declared parameter.

**objectValueFragment**: a GraphQL fragment on the object type that contains the field being resolved. In the `User.displayName` example below, the fragment must be on the `User` type.

**queryValueFragment**: a GraphQL fragment on the root query type.

**variables**: values of variables used in `objectValueFragment` or `queryValueFragment`.

## Required selection set syntax

A resolver can optionally specify one or both of `objectValueFragment` and `queryValueFragment` using either the shorthand fragment syntax, or full fragment syntax. Values can be accessed using `ctx.getObjectValue()` and `ctx.getQueryValue()`.

### Shorthand syntax

The shorthand fragment syntax omits the `fragment ... on Type { }` declaration and just includes the selections within the fragment body.

Here's an example of what this looks like for a `User.displayName` field. The selections must be on the `User` type:

```kotlin
@Resolver("firstName lastName")
class UserDisplayNameResolver : UserResolvers.DisplayName()
```
The shorthand fragment syntax can also be used for `queryValueFragment`. The selections must be on the root query type:

```kotlin
@Resolver(
  queryValueFragment = "node(id: \$userId) { ... on User { firstName lastName } }",
  variables = [Variable("userId", fromArgument = "userId")]
)
```

### Full fragment syntax

The full fragment syntax is the regular GraphQL fragment syntax. You can name the fragment whatever you'd like, although we typically use `_` for the fragment name when there's only a single fragment to indicate that the name isn't used anywhere:

```kotlin
@Resolver("fragment _ on User { firstName lastName }")
```

You can define multiple named fragments and reference them within your main fragment using the standard GraphQL fragment spread syntax (`...FragmentName`):
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

The fragments shown above are defined inline within a single resolver. To share a fragment across **multiple** resolvers in the same tenant module, declare it once as a [named fragment](named_fragments.md) and spread it with `...FragmentName`.

Note that if you have multiple fragments on the type of the main fragment (either the object type or the query type), the primary one needs to be named `Main`:

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

## Accessing required selection set values
You can access the required selection set values via the [`Context` object](field_resolvers.md#context) given as input to the field resolver. `ctx.getObjectValue()` and `ctx.getQueryValue()` return [GRTs](../generated_code/index.md) of the object and Query types respectively, with all selections eagerly pre-resolved.

The GRT getter methods correspond to the **schema types**, not the fragment structure. For example, given the listing `queryValueFragment` above:

```kotlin
// Query.node` field:
val q = ctx.getQueryValue()
val listing = q.getNode() as? Listing

// Get Listing.coverImage, aliased as "cover" in the fragment:
val coverImage = listing?.getCoverImage(alias = "cover")

// Get the caption field (since GRTs are based on schema types, fragments are irrelevant):
val coverCaption = coverImage?.getCaption()

// Access room images
val roomImages = listing?.getRooms()?.flatMap { it.getImages() }
```

If the resolver tries to access a field not included within its required selection set, it results in an `UnsetFieldException` at runtime.

With `getObjectValue()`/`getQueryValue()`, all selections are eagerly pre-resolved before the returned GRT is accessible, so individual field getters on the GRT do not suspend.

## Variables

Any of the fragments in `@Resolver` annotations may use GraphQL variables. These variables can be bound to values in one of 4 ways:

1. Via the `variables` parameter of `@Resolver`, using `fromArgument`
1. Via the `variables` parameter of `@Resolver`, using `fromObjectField`
1. Via the `variables` parameter of `@Resolver`, using `fromQueryField`
1. Via the resolver’s VariableProvider

Combined, these variable sources establish a pool of variables, any of which may be used in either the `objectValueFragment` or the `queryValueFragment` of a resolver.

#### @Resolver variables parameter

Variables may be bound using the `variables` parameter of `@Resolver`, which is an array of `@Variable` annotations. For example, consider this resolver configuration for a field on `MyType` that conditionally includes a field based on a value from the object:

```kotlin
@Resolver(
  objectValueFragment = """
    fragment _ on MyType {
      settings {
        isActive
      }
      field @include(if: ${'$'}shouldInclude)
    }
  """,
  variables = [Variable("shouldInclude", fromObjectField = "settings.isActive")]
)
```

This resolver fragment uses a `shouldInclude` variable. At runtime, the value for this variable will be determined by the value of `settings.isActive` on `MyType`'s object value. The `fromObjectField` parameter takes a dot-separated path relative to the object value, and the referenced path must be a selection defined in the resolver's `objectValueFragment`.

There are three mutually-exclusive parameters to the `@Variable` class that can be used to set the value of a variable:

1. the `fromArgument` parameter, which binds the variable to a field argument value (i.e. from `ctx.arguments`). To support nested GraphQL input types, the `fromArgument` string can contain a dot-separated path.
1. the `fromObjectField` parameter just illustrated, which takes a dot-separated path relative to the `objectValue` of an execution. If used, the path must be a selection defined in the resolver's `objectValueFragment`.
1. the `fromQueryField` parameter. This parameter is analogous to `fromObjectField`, but the path describes a selection in the resolver's `queryValueFragment`.

#### VariablesProvider

The `variables` parameter does not allow arbitrarily-computed values to be used as variables. To support dynamic use cases, a {{ kdoc("viaduct.api.resolver.VariablesProvider") }} can be used.

For example, consider a resolver for `MyType.foo` whose required selection set uses variables named `startDate` and `endDate`. To provide dynamically-computed values for these variables, the implementation for `MyTypeResolvers.Foo` may nest a class that implements the `VariablesProvider` interface:

```kotlin
@Variables(types = "startDate: Date, endDate: Date")
class Vars : VariablesProvider<MyType_Foo_Arguments> {
    override suspend fun provide(args: MyType_Foo_Arguments) =
        LocalDate.now().let {
            mapOf(
                "startDate" to it,
                "endDate" to it.plusDays(7)
            )
        }
    }
}
```

The value of the `types` parameter to `@Variables` must conform to *VariableDefinitionlist* from [GraphQL Spec](https://spec.graphql.org/draft/#sec-Language.Variables). The `args` parameter to the `provide` function is the arguments of the field whose resolver class defines this variable provider, or `NoArguments` if the field takes no arguments.
