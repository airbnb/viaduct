---
title: idOf Directive
description: Bind ID fields to GraphQL types for type-safe Global ID handling in Viaduct.
---


The `@idOf` directive binds an `ID` field or argument to a **specific GraphQL type**, allowing Viaduct to perform automatic type validation and Global ID decoding. It ensures that the ID belongs to the expected type before invoking your resolver, preventing mismatched or malformed identifiers at runtime.

## Why it matters

In GraphQL, all `ID` values are strings. Without additional metadata, there’s no way to know which entity type an ID represents. `@idOf` introduces **type awareness** by declaring that a given `ID` corresponds to a specific GraphQL type.

This allows Viaduct to:

- **Validate** incoming IDs before they reach resolver logic.
- **Decode** Global IDs on behalf of application code, keeping their serialization format encapsulated.
- **Reject** mismatched IDs (for example, passing a `Planet` ID to a `Character` resolver).
- **Generate type-safe schemas** that tools can reason about statically.

## Basic usage

Apply `@idOf` to any `ID` argument or field that represents a Global ID.


{{ codetag("demoapps/starwars/modules/filmography/src/main/viaduct/schema/Character.graphqls", "id_example", lang="kotlin") }}


{{ codetag("demoapps/starwars/modules/filmography/src/main/viaduct/schema/Character.graphqls", "character_type", lang="kotlin") }}


## How it works at runtime

When a client calls a query such as:

```graphql
query {
  searchCharacter(search: "Q2hhcmFjdGVyOjE=") {
    id
    name
  }
}
```

Viaduct will:

1. Decode the (intentionally opaque) string `"Q2hhcmFjdGVyOjE="` into its global ID components, which happens to be  type `Character` and internal id `1`.
2. Validate that the type for the argument to `Query.character`'s argument (which `Character`) matches the type in the encoded ID (which it does).
3. Pass the decoded `GlobalID` value to `CharacterNodeResolver`, where you can access the `id` field type-safely as a Kotlin `GlobalID`.

{{ codetag("demoapps/starwars/modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/characters/resolvers/CharacterNodeResolver.kt", "node_resolver_example", lang="kotlin") }}


This pattern helps ensure that only valid, correctly-typed IDs reach your business logic.

> Viaduct has no way to prevent malicious clients from manufacturing global ID strings that conform to its serialization format but contain arbitrary internal id values. Be sure to code defensively.


## Advantages

- Encapsulates the decoding of serialized global IDs.
- Prevents runtime errors caused by type mismatches.
- Simplifies schema introspection and static analysis.
- Makes field-level validation explicit and discoverable in the schema.

## Common mistakes

### 1. Forgetting `@idOf` on inputs that expect Global IDs

If an argument or input field represents a Global ID but lacks `@idOf`, Viaduct treats it as a plain string, skipping type validation and decoding. Always add `@idOf` when your resolvers depend on typed IDs.

### 2. Mixing raw IDs with Global IDs

All `ID` arguments using `@idOf` are expected to be **encoded Global IDs**, not raw database identifiers. Passing unencoded values will fail decoding or validation.

### 3. Misdeclaring the target type

Ensure the type name in `@idOf(type: "X")` matches the GraphQL type exactly, including case. `"character"` and `"Character"` are not equivalent.

## Do and don’t

- **Do** use `@idOf` on every `ID` field or argument that carries a Global ID.
- **Do** rely on `ctx.id.internalID` for the decoded internal ID in resolvers.
- **Don’t** attempt to parse or decode IDs manually.
- **Don’t** use `@idOf` on non-ID fields.

> For more on Global IDs and `@idOf`, see the [Global IDs developer reference](../../../docs/developers/globalids/index.md).
