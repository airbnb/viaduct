---
title: backingData Directive
description: Specify backing data classes for fields to separate transformation logic from retrieval.
layout: docs/single
weight: 1
---

The `@backingData` directive binds a **field** to a backing data class that performs transformation, shaping, or
preparation of data before it is exposed by GraphQL. It complements `@resolver`: the resolver wires the field into
execution, while the backing data class centralizes the mapping logic that would otherwise live inside the resolver.

## Basic usage

Apply `@backingData` on a field (often together with `@resolver`) and point to a Kotlin class that implements the
backing logic.

{{< codetag path="demoapps/starwars/modules/filmography/src/main/viaduct/schema/Character.graphqls" tag="all_characters" land="graphql" >}}

In this demo, the class
`com.example.starwars.modules.filmography.characters.queries.AllCharactersQueryResolver`
is responsible for shaping the list of `Character` items returned by `allCharacters`.

## How it integrates at runtime

1. **Execution plan:** Viaduct identifies that `allCharacters` is a resolver-backed field with a backing data class.
2. **Resolver step:** the resolver coordinates arguments and orchestration (for example, reads the `limit`).
3. **Backing step:** the backing class transforms raw domain models into GraphQL objects (builders), applying any
   mapping, filtering, or normalization rules required by the schema.
4. **Result:** the field returns objects that already match the schema’s expectations (IDs, formatting, minimal fields).

### With `@backingData` (mapping lives in a backing class)

{{< codetag path="demoapps/starwars/modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/characters/queries/AllCharactersQueryResolver.kt" tag="resolver_example"  >}}

Pros: fewer moving parts.
Cons: resolver grows, mapping is harder to share or test in isolation.

## Design guidelines

- Keep resolvers **thin**; put mapping/formatting in the backing data class.
- Generate `id` values with `ctx.globalIDFor(Type.Reflection, internalId)`.
- Request only the minimal fields you need; defer relationships to field/batch resolvers.
- Prefer immutable outputs from the backing class (builders with final values).


{{< prevnext >}}
