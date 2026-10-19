---
title: Named Fragments
description: Sharing reusable selection sets across resolvers in the Star Wars demo app.
---


Two resolvers on the `Character` type in the `filmography` tenant need the same fields: `CharacterDisplaySummaryResolver` builds a `"name (birthYear)"` string, and `CharacterRichSummaryResolver` combines `name` and `birthYear` with batch-loaded relationship data. Rather than repeat `name birthYear` in both resolvers' fragments, the demo declares a **named fragment** once and spreads it in both.

!!! warning "Experimental API"
    Named fragments are an experimental Viaduct API and may change in a future release.

## Declaring the fragment

A named fragment is annotated with `@GraphQLFragment` and extends `FragmentFromAnnotation<T>` for the type it selects on — here, `Character`. Kotlin uses a singleton `object`; Java uses a class with the Java annotation and document base class:

=== "Kotlin"

    {{ codetag("demoapps/starwars/modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/characters/fragments/CharacterIdentityFieldsFragment.kt", "named_fragment_example", lang="kotlin") | indent(4) }}

=== "Java"

    {{ codefile("demoapps/starwars-java/modules/filmography/src/main/java/com/example/starwars/modules/filmography/characters/fragments/CharacterIdentityFieldsFragment.java", lang="java") | indent(4) }}


Viaduct discovers `@GraphQLFragment` declarations at build time and validates them against the schema. The fragment name (`CharacterIdentityFields`) must be unique within the tenant module.

## Spreading it in a resolver

Spread the fragment with standard `...CharacterIdentityFields` syntax inside a resolver's `objectValueFragment`. `CharacterDisplaySummaryResolver` reads exactly the two fields the fragment provides:

=== "Kotlin"

    {{ codetag("demoapps/starwars/modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/characters/resolvers/CharacterDisplaySummaryResolver.kt", "named_fragment_consumer", lang="kotlin") | indent(4) }}

=== "Java"

    {{ codefile("demoapps/starwars-java/modules/filmography/src/main/java/com/example/starwars/modules/filmography/characters/resolvers/CharacterDisplaySummaryResolver.java", lang="java") | indent(4) }}


## Reusing it alongside inline selections

A spread can be combined with inline fields. `CharacterRichSummaryResolver` needs the same identity fields **plus** the character's `id` for its batch lookups, so it spreads the shared fragment and adds `id` inline:

=== "Kotlin"

    ```kotlin
    @Resolver(objectValueFragment = "fragment _ on Character { id ...CharacterIdentityFields }")
    class CharacterRichSummaryResolver(/* ... */) : CharacterResolvers.RichSummary() {
        override suspend fun batchResolve(contexts: List<Context>): List<FieldValue<String>> {
            // ctx.getObjectValue().getNameOrThrow() / .getBirthYearOrThrow() come from the shared fragment,
            // ctx.getObjectValue().getIdOrThrow() comes from the inline selection.
        }
    }
    ```

=== "Java"

    {{ codefile("demoapps/starwars-java/modules/filmography/src/main/java/com/example/starwars/modules/filmography/characters/resolvers/CharacterRichSummaryResolver.java", lang="java") | indent(4) }}

Because both resolvers now depend on the same fragment, adding a field to `CharacterIdentityFields` makes it available in both places at once — keeping the two resolvers in sync.

## Spreading multiple fragments

A selection set can spread **more than one** named fragment — that's standard GraphQL, and Viaduct merges all the spreads into the resolver's required selection set. The demo declares a second fragment for appearance fields:

=== "Kotlin"

    {{ codetag("demoapps/starwars/modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/characters/fragments/CharacterAppearanceFieldsFragment.kt", "appearance_fragment_example", lang="kotlin") | indent(4) }}

=== "Java"

    {{ codefile("demoapps/starwars-java/modules/filmography/src/main/java/com/example/starwars/modules/filmography/characters/fragments/CharacterAppearanceFieldsFragment.java", lang="java") | indent(4) }}


`CharacterFormattedDescriptionResolver` needs identity **and** appearance fields, so it spreads both fragments in one selection set:

=== "Kotlin"

    ```kotlin
    @Resolver("fragment _ on Character { ...CharacterIdentityFields ...CharacterAppearanceFields }")
    class CharacterFormattedDescriptionResolver : CharacterResolvers.FormattedDescription() {
        override suspend fun resolve(ctx: Context): String? {
            val character = ctx.getObjectValue()
            // getNameOrThrow()/getBirthYearOrThrow() come from CharacterIdentityFields,
            // getEyeColorOrThrow()/getHairColorOrThrow() come from CharacterAppearanceFields.
            ...
        }
    }
    ```

=== "Java"

    {{ codefile("demoapps/starwars-java/modules/filmography/src/main/java/com/example/starwars/modules/filmography/characters/resolvers/CharacterFormattedDescriptionResolver.java", lang="java") | indent(4) }}


You can spread any number of fragments, mix them with inline fields (as the `id ...CharacterIdentityFields` example above does), and even spread fragments that themselves spread other fragments — Viaduct resolves the spreads transitively. The only constraints: each spread must resolve to a `@GraphQLFragment` declared in the same tenant module, and the fragment names must be distinct.

## When to reach for a named fragment

Use a named fragment when **more than one resolver in the same module needs the same selection set**. For a one-off selection used by a single resolver, prefer an inline fragment — a named fragment only pays off when it's reused.

> For the full API reference and scoping rules, see the [Named Fragments developer reference](../../../docs/developers/resolvers/named_fragments.md). See [Best Practices](../../../docs/developers/best_practices/index.md) for the consolidated fragment guidance.
