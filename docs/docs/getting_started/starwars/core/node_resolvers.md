---
title: Node Resolvers
description: Implementing node resolvers in Viaduct for type-safe, opaque Global ID lookups.
---


Node resolvers provide **typed lookups by Global ID**. Any entity that must be fetched individually through the GraphQL `node(id:)` entry point should have a corresponding node resolver. Viaduct decodes the incoming Global ID, hands your resolver the **internal ID** via `ctx.id.internalID`, and expects you to return the typed GraphQL object. Keep node resolvers tiny: _lookup → build → return_.

## Request lifecycle (node)

1. Client calls `node(id: ID!)`.
2. Viaduct decodes the Global ID (base64 of `"<Type>:<InternalID>"`).
3. The matching `NodeResolvers.<Type>` implementation receives a typed `Context` with `ctx.id.internalID`.
4. Your resolver loads the record and builds the response using `Character.of(ctx) { ... }` or a dedicated builder class (for example, `CharacterBuilder(ctx).build(entity)`).

## Implementation


{{ codetag("demoapps/starwars/modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/films/resolvers/FilmNodeResolver.kt", "node_resolver_example", lang="kotlin") }}


### Handling "not found"

GraphQL's [error specification](https://spec.graphql.org/draft/#sec-Errors) gives you two reasonable choices when an entity isn't found: return `null` (and let `node` show the absence to the client) or surface a typed error. The Star Wars demo throws an `IllegalArgumentException` because, in this demo, an unknown ID is treated as a programming error. Most production node resolvers prefer `null` so that callers can branch on presence without a query failure. Whichever you pick, reserve exceptions for **unexpected** conditions (I/O errors, decoding failures, programming bugs).

## Common patterns that pair with node resolvers

### Lightweight builders
Populate only intrinsic, low-cost fields in the node resolver. Related entities (homeworld, species, films) should be resolved via **field resolvers** — which Viaduct can batch efficiently per request.

### Stable IDs
Always generate IDs with `ctx.globalIDFor(<Type>.Reflection, internalId)` to keep them **opaque and stable** across module or storage backends.

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

## Integration testing

Node resolvers are most usefully exercised by integration tests that issue real `node(id:)` queries against a configured Viaduct instance. Verify:

- **ID shape:** the `id` returned in the response is the typed Global ID you emitted from `ctx.globalIDFor(...)`.
- **Composability:** related fields (homeworld, species, film counts) resolve via field resolvers, not by extra work inside the node resolver.
- **Not-found behavior:** the resolver behaves consistently with whatever convention you chose (`null` or a typed error).

> See `ResolverIntegrationTest.kt` and `StarWarsNodeResolversTest.kt` in the demo for examples of end-to-end node behavior.

## Do and don’t

- **Do** keep node resolvers tiny: _lookup → build → return_.
- **Do** load a single entity by its internal ID and attach a Global ID with `ctx.globalIDFor(<Type>.Reflection, internalId)`.
- **Do** lean on field resolvers for relationships and heavy logic.
- **Don’t** perform per-request joins here; you'll lose batching opportunities.
- **Don’t** leak internal IDs — always emit typed Global IDs in the `id` field.

> See [Best Practices](../../../docs/developers/best_practices/index.md) for the consolidated reference. For the complete node-resolver API and generated base-class reference, see the [Node Resolvers developer reference](../../../docs/developers/resolvers/node_resolvers.md).
