---
title: Named Fragments
description: Declaring reusable GraphQL fragments with @GraphQLFragment and spreading them in resolvers
---


Named fragments let you declare a GraphQL fragment **once** and reuse it across multiple resolvers in the same tenant module, instead of repeating the same selections in every `objectValueFragment` or `queryValueFragment`.

!!! warning "Experimental API"
    Named fragments are an [experimental API](../api_stability/index.md) (`@ExperimentalApi`). The API surface may change in a future release.

## Declaring a named fragment

Annotate a declaration with `@GraphQLFragment` and extend `FragmentFromAnnotation<T>`, where `T` is the GraphQL composite type the fragment selects on. Kotlin uses a singleton `object`; Java uses a class:

=== "Kotlin"

    ```kotlin
    @GraphQLFragment("fragment UserCoreFields on User { id name email }")
    object UserCoreFieldsFragment : FragmentFromAnnotation<User>()
    ```

=== "Java"

    ```java
    @GraphQLFragment("fragment UserCoreFields on User { id name email }")
    public final class UserCoreFieldsFragment extends FragmentFromAnnotation<User> {}
    ```


Rules:

* Kotlin declarations must be singleton `object` declarations. Java declarations must extend `viaduct.java.api.documents.FragmentFromAnnotation<T>` and use `viaduct.java.api.annotations.GraphQLFragment`; they do not need a singleton instance.
* The fragment text must contain **exactly one** fragment definition.
* Fragment names must be unique within a tenant module — two `@GraphQLFragment` declarations declaring the same fragment name is a build error.
* Named fragments are **scoped to the tenant module** in which they are declared. Spreading a fragment from a different module is a compile error.

Fragments are discovered at build time (KSP for Kotlin, annotation processing for Java) and validated against the schema as part of the tenant module's required-selection-set checks. Java examples below use `viaduct.java.api.annotations.Resolver` and `java.util.concurrent.CompletableFuture`. Each public Java class belongs in its own file.

## Spreading a named fragment in a resolver

Once declared, spread the fragment with standard GraphQL spread syntax (`...FragmentName`) inside a resolver's `objectValueFragment` or `queryValueFragment`:

=== "Kotlin"

    ```kotlin
    // Read fields from the object being resolved
    @Resolver(objectValueFragment = "fragment _ on User { ...UserCoreFields }")
    class UserLabelResolver : UserResolvers.Label() {
        override suspend fun resolve(ctx: Context): String {
            val user = ctx.getObjectValue()
            return "${user.getIdOrThrow()}:${user.getNameOrThrow()}"
        }
    }
    ```

=== "Java"

    ```java
    @Resolver(objectValueFragment = "fragment _ on User { ...UserCoreFields }")
    public final class UserLabelResolver extends UserResolvers.Label {
      @Override
      public CompletableFuture<String> resolve(Context ctx) {
        User user = ctx.getObjectValue();
        return CompletableFuture.completedFuture(user.getId() + ":" + user.getName());
      }
    }
    ```


You can combine a spread with inline selections, and you can spread the same fragment from several resolvers:

=== "Kotlin"

    ```kotlin
    @Resolver(objectValueFragment = "fragment _ on User { id ...UserCoreFields }")
    class UserRichSummaryResolver : UserResolvers.RichSummary() { /* ... */ }
    ```

=== "Java"

    ```java
    @Resolver(objectValueFragment = "fragment _ on User { id ...UserCoreFields }")
    public final class UserRichSummaryResolver extends UserResolvers.RichSummary {
      // Implement resolve(Context) using the selected User getters.
    }
    ```


A single selection set may spread **multiple** named fragments — this is standard GraphQL, and Viaduct merges all the spreads into the resolver's required selection set:

=== "Kotlin"

    ```kotlin
    @GraphQLFragment("fragment UserContactFields on User { email phone }")
    object UserContactFieldsFragment : FragmentFromAnnotation<User>()

    @Resolver(objectValueFragment = "fragment _ on User { ...UserCoreFields ...UserContactFields }")
    class UserCardResolver : UserResolvers.Card() { /* ... */ }
    ```

=== "Java"

    ```java
    @GraphQLFragment("fragment UserContactFields on User { email phone }")
    public final class UserContactFieldsFragment extends FragmentFromAnnotation<User> {}

    @Resolver(objectValueFragment = "fragment _ on User { ...UserCoreFields ...UserContactFields }")
    public final class UserCardResolver extends UserResolvers.Card {
      // Implement resolve(Context) using the selected User getters.
    }
    ```


Spreads also nest: a named fragment may itself spread other named fragments, and Viaduct resolves them transitively. Each spread must resolve to a `@GraphQLFragment` declared in the same tenant module, and fragment names must be distinct (a name collision is a build error).

Spreads also work in a `queryValueFragment` when the fragment is defined on the root `Query` type:

=== "Kotlin"

    ```kotlin
    @GraphQLFragment("fragment ViewerNameFields on Query { viewer { name } }")
    object ViewerNameFieldsFragment : FragmentFromAnnotation<Query>()

    @Resolver(queryValueFragment = "fragment _ on Query { ...ViewerNameFields }")
    class UserGreetingResolver : UserResolvers.Greeting() {
        override suspend fun resolve(ctx: Context): String {
            val viewerName = ctx.getQueryValue().getViewerOrThrow()?.getNameOrThrow()
            return "$viewerName-greeting"
        }
    }
    ```

=== "Java"

    ```java
    @GraphQLFragment("fragment ViewerNameFields on Query { viewer { name } }")
    public final class ViewerNameFieldsFragment extends FragmentFromAnnotation<Query> {}

    @Resolver(queryValueFragment = "fragment _ on Query { ...ViewerNameFields }")
    public final class UserGreetingResolver extends UserResolvers.Greeting {
      @Override
      public CompletableFuture<String> resolve(Context ctx) {
        var viewer = ctx.getQueryValue().getViewer();
        String name = viewer == null ? null : viewer.getName();
        return CompletableFuture.completedFuture(name + "-greeting");
      }
    }
    ```


Accessing the resolved data works exactly as it does for any other required selection set: the getters on `ctx.getObjectValue()` / `ctx.getQueryValue()` correspond to the **schema** types, not the fragment structure. If you access a field outside the expanded selection set, Kotlin reports `UnsetFieldException` and Java reports `TenantUsageException`. See [Resolver Annotation](resolver_annotation.md#accessing-required-selection-set-values) for details.

## When to use named fragments

Use a named fragment when **multiple resolvers in the same module need the same set of fields**. Declaring the selection once keeps the resolvers in sync — adding a field to the fragment makes it available everywhere the fragment is spread.

If a selection set is used by only a single resolver, prefer declaring it inline (shorthand or `fragment _ on Type { ... }`); a named fragment adds indirection without a payoff.

For a worked, runnable example in the Star Wars demo app, see the [Named Fragments tutorial](../../../getting_started/starwars/core/named_fragments.md).

For the Java implementation, see [CharacterIdentityFieldsFragment](https://github.com/airbnb/viaduct/blob/main/demoapps/starwars-java/modules/filmography/src/main/java/com/example/starwars/modules/filmography/characters/fragments/CharacterIdentityFieldsFragment.java) and [CharacterDisplaySummaryResolver](https://github.com/airbnb/viaduct/blob/main/demoapps/starwars-java/modules/filmography/src/main/java/com/example/starwars/modules/filmography/characters/resolvers/CharacterDisplaySummaryResolver.java). Java GRT getters are synchronous; a missing selection still differs from a selected nullable field.
