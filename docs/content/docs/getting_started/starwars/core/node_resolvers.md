---
title: Node Resolvers
description: Implementing node resolvers in Viaduct for type-safe, opaque Global ID lookups.
layout: docs/single
weight: 3
linkTitle: Node Resolvers
---


Node resolvers provide **typed lookups by Global ID**. Any entity that must be fetched individually through the
GraphQL `node(id:)` entry point should have a corresponding node resolver. Viaduct parses the incoming Global ID,
hands your resolver the **internal ID** via `ctx.id.internalID`, and expects you to return the typed GraphQL object
(or `null` if it does not exist).

## Responsibilities and boundaries

- **Do:** load a single entity by its internal ID.
- **Do:** build the GraphQL object and attach a Global ID with `ctx.globalIDFor(<Type>.Reflection, internalId)`.
- **Do:** return `null` when the entity does not exist.
- **Don’t:** perform cross-entity joins or heavy business logic here — use field resolvers for that.
- **Don’t:** throw for “not found”; prefer `null` and let GraphQL shape the result.

## Request lifecycle (node)

1. Client calls `node(id: ID!)`.
2. Viaduct decodes the Global ID (base64 of `"<Type>:<InternalID>"`).
3. The matching `NodeResolvers.<Type>` implementation receives a typed `Context` with `ctx.id.internalID`.
4. Your resolver loads the record and returns a **typed builder** (for example, `Character.Builder(ctx)`).

## Implementation

{{< codetag path="demoapps/starwars/modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/films/resolvers/FilmNodeResolver.kt" tag="node_resolver_example" >}}

### Why return `null` instead of throwing?
GraphQL treats `null` as an expected outcome for missing nodes, avoiding unnecessary query failures and allowing the
client to branch on presence. Reserve exceptions for **unexpected** conditions (I/O errors, decoding failures, etc.).

## Common patterns that pair with node resolvers

### Lightweight builders
Populate only intrinsic, low-cost fields in the node resolver. Related entities (homeworld, species, films) should be
resolved via **field resolvers** — which Viaduct can batch efficiently per request.

### Stable IDs
Always generate IDs with `ctx.globalIDFor(<Type>.Reflection, internalId)` to keep them **opaque and stable** across
module or storage backends.

### Query example (client)

```graphql
query GetNode($id: ID!) {
  node(id: $id) {
    ... on Character {
      id
      name
      homeworld { name }   # resolved later via a field resolver (can batch)
      species { name }     # resolved later via a field resolver (can batch)
    }
  }
}
```

## Testing checklist (in this repo)

- **Happy path:** returns a fully built `Character` when the internal ID exists.
- **Missing entity:** returns `null` without throwing.
- **ID shape:** `id` field is the typed Global ID you emitted from `ctx.globalIDFor(...)`.
- **Composability:** related fields (homeworld, species, film counts) resolve via field resolvers.

> See the integration tests such as `ResolverIntegrationTest.kt` and `StarWarsNodeResolversTest.kt` for examples of
> end-to-end node behavior in this demo.

## Do and don’t

- **Do** keep node resolvers tiny: _lookup → build → return_.
- **Do** lean on field resolvers for relationships and heavy logic.
- **Don’t** perform per-request joins here; you’ll lose batching opportunities.
- **Don’t** leak internal IDs — always emit typed Global IDs in the `id` field.

{{< prevnext >}}
