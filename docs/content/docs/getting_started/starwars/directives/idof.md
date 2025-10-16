---
title: idOf Directive
description: Bind ID fields to GraphQL types for type-safe Global ID handling in Viaduct.
layout: docs/single
weight: 4
---

The `@idOf` directive binds an `ID` field or argument to a **specific GraphQL type**, allowing Viaduct to perform
automatic type validation and Global ID decoding. It ensures that the ID belongs to the expected type before invoking
your resolver, preventing mismatched or malformed identifiers at runtime.

## Why it matters

In GraphQL, all `ID` values are strings. Without additional metadata, there’s no way to know which entity type an ID
represents. `@idOf` introduces **type awareness** by declaring that a given `ID` corresponds to a specific GraphQL type.

This allows Viaduct to:

- **Validate** incoming IDs before they reach resolver logic.
- **Decode** base64-encoded Global IDs automatically.
- **Reject** mismatched IDs (for example, passing a `Planet` ID to a `Character` resolver).
- **Generate type-safe schemas** that tools can reason about statically.

## Basic usage

Apply `@idOf` to any `ID` argument or field that represents a Global ID.

{{< codetag path="demoapps/starwars/modules/filmography/src/main/viaduct/schema/Character.graphqls" tag="id_example" lang="graphql" >}}

{{< codetag path="demoapps/starwars/modules/filmography/src/main/viaduct/schema/Character.graphqls" tag="character_type" lang="graphql" >}}

## How it works at runtime

When a client calls a query such as:

```graphql
query {
  character(id: "Q2hhcmFjdGVyOjE=") {
    id
    name
  }
}
```

Viaduct will:

1. Decode the base64 string `"Q2hhcmFjdGVyOjE="` → `"Character:1"`.
2. Validate that the declared type (`Character`) matches the type in the encoded ID.
3. Populate `ctx.id.internalID` with `"1"`.
4. Pass control to `CharacterNodeResolver`, where you can safely use the internal ID.

{{< codetag path="demoapps/starwars/modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/characters/resolvers/CharacterNodeResolver.kt" tag="node_resolver_example" >}}

This pattern ensures that only valid, correctly-typed IDs reach your business logic.

## Advantages

- Eliminates manual parsing of base64 Global IDs.
- Prevents runtime errors caused by type mismatches.
- Simplifies schema introspection and static analysis.
- Makes field-level validation explicit and discoverable in the schema.

## Common mistakes

### 1. Using `@idOf` on non-ID fields

The directive should only decorate `ID` fields or arguments. Applying it to `String` or `Int` fields has no effect and
may produce schema validation warnings.

### 2. Forgetting `@idOf` on inputs that expect Global IDs

If an argument or input field represents a Global ID but lacks `@idOf`, Viaduct treats it as a plain string, skipping
type validation and decoding. Always add `@idOf` when your resolvers depend on typed IDs.

### 3. Mixing raw IDs with Global IDs

All `ID` arguments using `@idOf` are expected to be **encoded Global IDs**, not raw database identifiers. Passing
unencoded values will fail decoding or validation.

### 4. Misdeclaring the target type

Ensure the type name in `@idOf(type: "X")` matches the GraphQL type exactly, including case. `"character"` and
`"Character"` are not equivalent.

## Do and don’t

- **Do** use `@idOf` on every `ID` field or argument that carries a Global ID.
- **Do** rely on `ctx.id.internalID` for the decoded internal ID in resolvers.
- **Don’t** attempt to parse or decode IDs manually.
- **Don’t** use `@idOf` on non-ID fields.

{{< prevnext >}}
