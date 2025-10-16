---
title: Resolver Integration Patterns
description: How node and field resolvers work together in Viaduct.
layout: docs/single
weight: 6
linkTitle: Resolver Integration
---

Resolvers in Viaduct form a **layered model** that separates entity retrieval from per-field computation. Node, field,
and batch field resolvers each play a distinct role but integrate seamlessly during execution.

## Standard entity pattern

Each entity type can typically implement:

1. **Node resolver** for `GlobalID`-based retrieval via `node` queries.
{{< codetag path="demoapps/starwars/modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/characters/resolvers/CharacterNodeResolver.kt" tag="node_resolver_example" count="2">}}

2. **Batch field resolvers** for expensive computed fields that benefit from batching.
{{< codetag path="demoapps/starwars/modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/characters/resolvers/CharacterFilmCountResolver.kt" tag="film_count_batch_resolver" count="3">}}

3. **Single field resolvers** for lightweight computed or derived values.
{{< codetag path="demoapps/starwars/modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/characters/resolvers/CharacterDisplayNameResolver.kt" tag="resolver_example" count="2">}}

## The entity resolution flow

1. **Query parsing:** Viaduct analyzes the query and fragments to determine which types and fields are required.
2. **Node resolution:** node resolvers load entities by Global ID for any `node(id:)` or reference field.
3. **Field resolution:** Viaduct invokes field resolvers for each selected field, batching them when possible.
4. **Result assembly:** all results are merged into a single GraphQL response.

This design isolates entity loading from field logic, ensures predictable performance, and enables resolver reuse across
schemas and scopes.

## Integration example

```graphql
query {
  node(id: "Q2hhcmFjdGVyOjE=") {
    ... on Character {
      id
      name                # from Field Resolver
      homeworld { name }  # via batched field resolver
      filmCount           # aggregated by batch resolver
    }
  }
}
```

Execution flow for this query:

| Step | Resolver | Responsibility |
|------|-----------|----------------|
| 1 | `CharacterNodeResolver` | Retrieve Character entity by internal ID. |
| 2 | `DisplayNameResolver` | Compute or format the `name` field. |
| 3 | `HomeworldResolver` | Fetch related Planet; batched across all Characters. |
| 4 | `FilmCountBatchResolver` | Compute film counts for all Characters in one call. |
| 5 | Viaduct runtime | Assemble and serialize the final result tree. |

## Common integration pitfalls

### 1. Duplicated lookups

Avoid fetching the same entity in multiple resolvers. Node resolvers should load once, and related lookups should use
field resolvers (batched when necessary).

### 2. Overfetching fragments

Limit `objectValueFragment` to only the fields your resolver actually uses. Overly broad fragments increase query cost
and memory use.

### 3. Missing batching opportunities

If a field resolver executes the same repository call per parent, migrate it to a batch resolver.

### 4. Misaligned ID handling

Ensure all resolvers use `ctx.globalIDFor(Type.Reflection, internalId)` and consume IDs via `ctx.id.internalID`. Mixing
raw IDs with Global IDs can cause type mismatches in queries.

### 5. Incorrect nullability handling

Return `null` for missing relationships when the schema field is nullable, rather than throwing exceptions.

## Do and don’t

- **Do** separate responsibilities: nodes fetch, fields compute, batch resolvers aggregate.
- **Do** test integration flows end-to-end with actual queries.
- **Don’t** mix loading logic inside field resolvers.
- **Don’t** assume execution order between independent resolvers — rely on field dependencies, not sequencing.

{{< prevnext >}}
