---
title: Named Fragments
description: Declaring reusable GraphQL fragments with @GraphQLFragment and spreading them in resolvers
---


Named fragments let you declare a GraphQL fragment **once** and reuse it across multiple resolvers in the same tenant module, instead of repeating the same selections in every `objectValueFragment` or `queryValueFragment`.

!!! warning "Experimental API"
    Named fragments are an [experimental API](../api_stability/index.md) (`@ExperimentalApi`). The API surface may change in a future release.

## Declaring a named fragment

Annotate a Kotlin singleton `object` with `@GraphQLFragment` and have it extend `FragmentFromAnnotation<T>`, where `T` is the GraphQL composite type the fragment selects on:

```kotlin
@GraphQLFragment("fragment UserCoreFields on User { id name email }")
object UserCoreFieldsFragment : FragmentFromAnnotation<User>()
```

Rules:

* The annotated declaration must be a Kotlin singleton `object`.
* The fragment text must contain **exactly one** fragment definition.
* Fragment names must be unique within a tenant module — two `@GraphQLFragment` objects declaring the same fragment name is a build error.
* Named fragments are **scoped to the tenant module** in which they are declared. Spreading a fragment from a different module is a compile error.

Fragments are discovered at build time by scanning for `@GraphQLFragment` (via KSP) and validated against the schema as part of the tenant module's required-selection-set checks.

## Spreading a named fragment in a resolver

Once declared, spread the fragment with standard GraphQL spread syntax (`...FragmentName`) inside a resolver's `objectValueFragment` or `queryValueFragment`:

```kotlin
// Read fields from the object being resolved
@Resolver(objectValueFragment = "fragment _ on User { ...UserCoreFields }")
class UserLabelResolver : UserResolvers.Label() {
    override suspend fun resolve(ctx: Context): String {
        val user = ctx.getObjectValue()
        return "${user.getId()}:${user.getName()}"
    }
}
```

You can combine a spread with inline selections, and you can spread the same fragment from several resolvers:

```kotlin
@Resolver(objectValueFragment = "fragment _ on User { id ...UserCoreFields }")
class UserRichSummaryResolver : UserResolvers.RichSummary() { /* ... */ }
```

A single selection set may spread **multiple** named fragments — this is standard GraphQL, and Viaduct merges all the spreads into the resolver's required selection set:

```kotlin
@GraphQLFragment("fragment UserContactFields on User { email phone }")
object UserContactFieldsFragment : FragmentFromAnnotation<User>()

@Resolver(objectValueFragment = "fragment _ on User { ...UserCoreFields ...UserContactFields }")
class UserCardResolver : UserResolvers.Card() { /* ... */ }
```

Spreads also nest: a named fragment may itself spread other named fragments, and Viaduct resolves them transitively. Each spread must resolve to a `@GraphQLFragment` declared in the same tenant module, and fragment names must be distinct (a name collision is a build error).

Spreads also work in a `queryValueFragment` when the fragment is defined on the root `Query` type:

```kotlin
@GraphQLFragment("fragment ViewerNameFields on Query { viewer { name } }")
object ViewerNameFieldsFragment : FragmentFromAnnotation<Query>()

@Resolver(queryValueFragment = "fragment _ on Query { ...ViewerNameFields }")
class UserGreetingResolver : UserResolvers.Greeting() {
    override suspend fun resolve(ctx: Context): String {
        val viewerName = ctx.getQueryValue().getViewer()?.getName()
        return "$viewerName-greeting"
    }
}
```

Accessing the resolved data works exactly as it does for any other required selection set: the getters on `ctx.getObjectValue()` / `ctx.getQueryValue()` correspond to the **schema** types, not the fragment structure. If you access a field that wasn't part of the (expanded) selection set, you get an `UnsetFieldException` at runtime. See [Resolver Annotation](resolver_annotation.md#accessing-required-selection-set-values) for details.

## When to use named fragments

Use a named fragment when **multiple resolvers in the same module need the same set of fields**. Declaring the selection once keeps the resolvers in sync — adding a field to the fragment makes it available everywhere the fragment is spread.

If a selection set is used by only a single resolver, prefer declaring it inline (shorthand or `fragment _ on Type { ... }`); a named fragment adds indirection without a payoff.

For a worked, runnable example in the Star Wars demo app, see the [Named Fragments tutorial](../../../getting_started/starwars/core/named_fragments.md).
