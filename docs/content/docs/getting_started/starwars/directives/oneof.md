---
title: OneOf Directive
description: Ensure exactly one non-null field in input objects; works well with @idOf for typed Global IDs.
layout: docs/single
weight: 5
---

The `@oneOf` directive enforces that **exactly one** field of an input object is non-null. This is useful for
union-like inputs where callers choose one of several ways to identify or filter an entity.

## Basic usage

```graphql
input CharacterSearchInput @oneOf {
  byName: String
  byBirthYear: String
  byId: ID
}

type Query {
  searchCharacter(search: CharacterSearchInput!): Character
}
```

If the client provides zero or more than one field, the request is rejected during validation.

## Combining @oneOf with @idOf

When one of the alternatives is a Global ID, add `@idOf` to make the ID **typed** and to enable automatic decoding.

{{< codetag path="demoapps/starwars/modules/filmography/src/main/viaduct/schema/Character.graphqls" tag="one_of_example" lang="graphql" >}}

Client query examples:

```graphql
# Option A: by name
query {
  searchCharacter(search: { byName: "Luke Skywalker" }) {
    id
    name
  }
}

# Option B: by Global ID (typed via @idOf)
query {
  searchCharacter(search: { byId: "Q2hhcmFjdGVyOjE=" }) {
    id
    name
  }
}
```

With `@idOf`, Viaduct will validate that the ID is a `Character` Global ID and decode it before your resolver runs.

## Resolver pattern

Inside the resolver, inspect which field was set and branch accordingly. When the ID path is used, the internal ID is
already validated and decoded by Viaduct.

{{< codetag path="demoapps/starwars/modules/filmography/src/main/kotlin/viaduct/demoapp/characters/viaduct/queryresolvers/SearchCharacterResolver.kt" tag="id_of_example" >}}

## Error behavior

- **Zero fields provided:** validation error (input must include exactly one field).
- **Multiple fields provided:** validation error.
- **Wrong ID type:** validation error before the resolver runs (thanks to `@idOf`).

## Design guidelines

- Keep input alternatives **orthogonal** (avoid overlapping semantics).
- Use `@idOf` on the ID branch so the resolver receives a **typed** and **decoded** internal ID.
- Return `null` for “not found” results; keep exceptions for unexpected failures.

{{< prevnext >}}
