---
title: Global IDs
description: Are Base64-encoded, type-safe identifiers for node-based entity retrieval in Viaduct.
layout: docs/single
weight: 2
linkTitle: Nodes and Global ID
---

Global IDs in Viaduct combines two pieces of information:

- **Type:** the GraphQL type name (for example, "Character", "Film", "Planet").
- **Internal ID:** your application's internal identifier for that entity.

## Format and encoding

The raw form is `"<Type>:<InternalID>"`, which is then base64-encoded.

```kotlin
// Encoded form for Character with internal ID "1":
val gid: String = Character.Reflection.globalId("1") // "Q2hhcmFjdGVyOjE="
```

When building objects in resolvers, use the execution context helper to attach a typed Global ID:

{{< codetag path="demoapps/starwars/modules/universe/src/main/kotlin/viaduct/demoapp/universe/starships/viaduct/mappers/StarshipBuilder.kt" tag="global_id_example"  >}}

> Treat Global IDs as **opaque**. They are intended for retrieval via `node` queries, not as human-facing identifiers.

## Using Global IDs in node resolvers

Node resolvers receive a parsed Global ID; use the internal ID to load the entity:

{{< codetag path="demoapps/starwars/modules/filmography/src/main/kotlin/viaduct/demoapp/characters/viaduct/queryresolvers/CharacterNodeResolver.kt" tag="node_resolver_example"  >}}

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

Annotate `ID` fields and arguments with `@idOf` to bind them to a concrete GraphQL type, enabling type-safe handling in
resolvers and tooling:

{{< codetag path="demoapps/starwars/modules/filmography/src/main/viaduct/schema/Character.graphqls" tag="id_example" lang="graphql" >}}

{{< codetag path="demoapps/starwars/modules/filmography/src/main/viaduct/schema/Character.graphqls" tag="character_type" lang="graphql" >}}

## Do and don’t

- **Do** treat Global IDs as opaque and stable across the API surface.
- **Do** generate them in resolvers using `ctx.globalIDFor` or `<Type>.Reflection.globalId(...)`.
- **Do** use `@idOf` on schema fields/arguments carrying Global IDs.
- **Don’t** expose internal IDs or rely on clients decoding base64.
- **Don’t** embed business logic or access control information in IDs.

{{< prevnext >}}
