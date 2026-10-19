---
title: Node Resolvers
description: Implementing node resolvers in Viaduct for type-safe, opaque Global ID lookups.
---


Node resolvers provide **typed lookups by Global ID**. Any entity that must be fetched individually through the GraphQL `node(id:)` entry point should have a corresponding node resolver. Viaduct decodes the incoming Global ID, hands your resolver the **internal ID** via `ctx.id.internalID` (Kotlin) or `context.getId().getInternalID()` (Java), and expects you to return the typed GraphQL object. Keep node resolvers tiny: _lookup → build → return_.

## Request lifecycle (node)

1. Client calls `node(id: ID!)`.
2. Viaduct decodes the Global ID into its type and internal ID components.
3. Viaduct uses the type component to find the corresponding node resolver and call it passing the decoded Global ID as `Context.id`.
4. Your resolver loads the record and builds the response using a generated builder (`Character.of(ctx) { ... }` in Kotlin, `Character.builder(context)` in Java) or a dedicated builder class (for example, `CharacterBuilder(ctx).build(entity)`).

## Implementation

The single-node Java resolver returns `CompletableFuture<Film>`. The batched character resolver is covered in [Batch Resolvers](batch_resolvers.md); it returns results keyed by their input context.



=== "Kotlin"

    {{ codetag("demoapps/starwars/modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/films/resolvers/FilmNodeResolver.kt", "node_resolver_example", lang="kotlin") | indent(4) }}

=== "Java"

    {{ codefile("demoapps/starwars-java/modules/filmography/src/main/java/com/example/starwars/modules/filmography/films/resolvers/FilmNodeResolver.java", lang="java") | indent(4) }}


### Handling "not found"

A node resolver must return an object for its ID. If the record is missing, an unbatched resolver should throw an exception; a Java resolver may also return a failed `CompletableFuture`. A batched node resolver returns `FieldValue.ofError(...)` for that input context. The Star Wars demo uses `IllegalArgumentException` for missing records.

The client-visible `node` field is nullable, so GraphQL can return `null` at that path together with an error; this does not make `null` a valid node-resolver result. For an expected absent relationship, a field resolver may return `null` when that relationship field is nullable. See the [node resolver contract](../../../docs/developers/resolvers/node_resolvers.md#output-selection-set).

## Common patterns that pair with node resolvers

### Lightweight builders
Populate only intrinsic, low-cost fields in the node resolver. Related entities (homeworld, species, films) should be resolved via **field resolvers** — which Viaduct can batch efficiently per request.

### Stable IDs
Generate IDs with `ctx.globalIDFor(<Type>.Reflection, internalId)` in Kotlin or `context.globalIDFor(Type.ofClass(Character.class), internalId)` in Java to keep them **opaque and stable** across module or storage backends.

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
- **Not-found behavior:** a missing ID produces a GraphQL error and no node value; batch failures remain associated with their input context.

> Kotlin examples are in `ResolverIntegrationTest.kt` and `StarWarsNodeResolversTest.kt`. Java covers character and film lookups in `StarWarsJavaIntegrationTest.resolvesCharacterAndFilmNodes()`; see [Testing](../testing/index.md).

## Do and don’t

- **Do** keep node resolvers tiny: _lookup → build → return_.
- **Do** load a single entity by its internal ID and attach a Global ID using the language-specific `globalIDFor` call shown above.
- **Do** lean on field resolvers for relationships and heavy logic.
- **Don’t** perform per-request joins here; you'll lose batching opportunities.
- **Don’t** leak internal IDs — always emit typed Global IDs in the `id` field.

> See [Best Practices](../../../docs/developers/best_practices/index.md) for the consolidated reference. For the complete node-resolver API and generated base-class reference, see the [Node Resolvers developer reference](../../../docs/developers/resolvers/node_resolvers.md).
