---
title: Global IDs
description: Type-safe, opaque identifiers for objects that implement Node — Viaduct's identity model.
---


Viaduct's identity model assumes that every reference between objects flows through a **Global ID**. Fields don't carry raw foreign keys — they carry typed Global IDs, and any object that needs to be fetched on its own implements the `Node` interface and is retrieved via the `node(id:)` entry point. Adopting this model is what unlocks Viaduct's batching, type-safe references, and storage-independent client contracts.

A Global ID combines two pieces of information:

- **Type:** the GraphQL type name (for example, "Character", "Film", "Planet").
- **Internal ID:** your application's internal identifier for that entity.

## Format and encoding

The raw form is `"<Type>:<InternalID>"`, which is then base64-encoded.

=== "Kotlin"

    ```kotlin
    // Encoded form for Character with internal ID "1":
    val gid: String = Character.Reflection.globalId("1") // "Q2hhcmFjdGVyOjE="
    ```

=== "Java"

    ```java
    GlobalID<Character> id = context.globalIDFor(Type.ofClass(Character.class), "1");
    ```

    Use the typed value when building a GRT. Viaduct serializes it as an opaque string in the GraphQL response; `id.getInternalID()` is for server-side lookup.

When building objects in resolvers, use the execution context helper to attach a typed Global ID:


=== "Kotlin"

    {{ codetag("demoapps/starwars/modules/universe/src/main/kotlin/com/example/starwars/modules/universe/starships/models/StarshipBuilder.kt", "global_id_example", lang="kotlin") | indent(4) }}

=== "Java"

    {{ codefile("demoapps/starwars-java/modules/universe/src/main/java/com/example/starwars/modules/universe/starships/models/StarshipBuilder.java", lang="java") | indent(4) }}


> Treat Global IDs as **opaque at the network boundary**. Clients pass them around — in `node(id:)` queries, in input arguments, in cached responses — but should not parse them. Inside resolvers you can decode them through `ctx.id.internalID` in Kotlin or `context.getId().getInternalID()` in Java to recover the type and internal ID; just don't ask clients to do the same.

## Using Global IDs in node resolvers

Node resolvers receive a decoded Global ID; use the internal ID to load the entity:


=== "Kotlin"

    {{ codetag("demoapps/starwars/modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/characters/resolvers/CharacterNodeResolver.kt", "node_resolver_example", lang="kotlin") | indent(4) }}

=== "Java"

    {{ codefile("demoapps/starwars-java/modules/filmography/src/main/java/com/example/starwars/modules/filmography/characters/resolvers/CharacterNodeResolver.java", lang="java") | indent(4) }}


## Client usage via `node(id:)`

Clients pass a Global ID to retrieve a specific entity, independent of the underlying storage key format:

```graphql
query ($id: ID!) {
  node(id: $id) {
    ... on Character {
      id
      name
    }
  }
}
```

## Schema hinting with `@idOf`

Annotate `ID` field arguments and input fields with `@idOf` to bind them to a concrete GraphQL type, enabling type-safe handling in resolvers and tooling:


{{ codetag("demoapps/starwars/modules/filmography/src/main/viaduct/schema/Character.graphqls", "id_example", lang="graphql") }}


{{ codetag("demoapps/starwars/modules/filmography/src/main/viaduct/schema/Character.graphqls", "character_type", lang="graphql") }}


## Do and don’t

- **Do** treat Global IDs as opaque and stable across the API surface.
- **Do** generate them in resolvers using `ctx.globalIDFor` or `<Type>.Reflection.globalId(...)`.
- **Do** use `@idOf` on schema field arguments and input fields carrying Global IDs.
- **Don’t** expose internal IDs at the network boundary or ask clients to decode Global IDs. Encoding and decoding happen inside Viaduct on both ends; clients treat them as opaque tokens.
- **Don’t** embed business logic or access control information in IDs.

> See [Best Practices](../../../docs/developers/best_practices/index.md) for the consolidated reference. For the encoding format, how to generate and consume `GlobalID` values in resolvers, and schema hints with `@idOf`, see the [Global IDs developer reference](../../../docs/developers/globalids/index.md).

