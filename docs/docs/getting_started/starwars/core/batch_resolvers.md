---
title: Batch Field Resolvers
description: Implementing batch field resolvers in Viaduct.
---


Batch field resolvers process multiple field requests **in one pass**, dramatically improving performance when the same field is selected across many parent objects. Kotlin returns results in the **same order** as its input contexts. Java identifies results by their original context keys in a map.

## Schema declaration

To opt into batching, add `isBatching: true` to the `@resolver` directive. This tells codegen to generate a `batchResolve` method instead of `resolve`:

```graphql
type Character implements Node @scope(to: ["default"]) @resolver(isBatching: true) {
  id: ID!
  name: String
  filmCount: Int @resolver(isBatching: true)
}
```

## Where batching fits in the execution flow

For field batches, Kotlin returns a list aligned with the input list; Java returns a map whose keys are the original `Context` objects. Put an entry for every context in the batch. Java field batches return `CompletableFuture<Map<Context, T>>`. Node batches in both languages use context-keyed maps of `FieldValue<T>`, with Java wrapping the map in a `CompletableFuture`. Do not copy the Kotlin field batch's list-returning signature into a Java resolver.


1. The planner groups identical field selections across all matching parent objects in the operation.
2. Viaduct calls your `batchResolve(contexts: List<Context>)`.
3. You perform **one** data fetch per unique key set (for example, character IDs).
4. You map results **back to each context**: return the ordered result list in Kotlin or a map keyed by the original contexts in Java.

## Minimal example (counts per character)


=== "Kotlin"

    {{ codetag("demoapps/starwars/modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/characters/resolvers/CharacterFilmCountResolver.kt", "film_count_batch_resolver", lang="kotlin") | indent(4) }}

=== "Java"

    {{ codefile("demoapps/starwars-java/modules/filmography/src/main/java/com/example/starwars/modules/filmography/characters/resolvers/CharacterFilmCountResolver.java", lang="java") | indent(4) }}


## Choosing the fragment

The `objectValueFragment` declares the parent fields your resolver needs. Keep it **minimal** — requesting only `id` is typical for lookup scenarios. In the example above, the `CharacterFilmCountResolver` only needs the character's internal ID, so its fragment is `fragment _ on Character { id }`. If you require additional, cheap fields (for example, `name` for formatting), add them here so they are available on `ctx.getObjectValue()` without extra work.

## Implementing batch resolvers in node resolvers

Node resolvers can also be batched by declaring `@resolver(isBatching: true)` on the type. Both languages receive a list of contexts: Kotlin reads each ID with `ctx.id.internalID`, and Java uses `context.getId().getInternalID()`. Return a map keyed by these original contexts, using `FieldValue.ofValue(...)` for a resolved node or `FieldValue.ofError(...)` for a missing node or other per-node failure.


=== "Kotlin"

    {{ codetag("demoapps/starwars/modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/characters/resolvers/CharacterNodeResolver.kt", "node_batch_resolver_example", lang="kotlin") | indent(4) }}

=== "Java"

    {{ codefile("demoapps/starwars-java/modules/filmography/src/main/java/com/example/starwars/modules/filmography/characters/resolvers/CharacterNodeResolver.java", lang="java") | indent(4) }}

## Error handling and nullability

- For nullable field results, Kotlin wrappers can use `FieldValue.ofValue(null)` and Java field-result maps can contain `null`. Use a Java map implementation that accepts null values when needed.
- Node resolvers must produce a node or an error. Report a missing node with `FieldValue.ofError(...)` rather than a null node value.
- Return one outcome per input: a Kotlin field-result list must match `contexts.size`; context-keyed result maps must contain every original context key.

## When to batch (and when not to)

**Batch when:**

- The same field is selected for **many** parent objects in a single operation.
- The data access layer supports bulk retrieval by keys (IDs).
- You would otherwise repeat the same lookup per parent (N + 1 pattern).

**Prefer single resolvers when:**

- Only a handful of parents are involved.
- The logic is strictly local and cheap for each parent.

## Example query that benefits from batching

Schema definition:


{{ codetag("demoapps/starwars/modules/filmography/src/main/viaduct/schema/Character.graphqls", "all_characters", lang="graphql") }}


Executed query:

```graphql
query {
  allCharacters(limit: 100) {
    filmCount  # resolved by FilmCountBatchResolver in one grouped call
  }
}
```

## Do and don’t

- **Do** request only the parent fields you need in `objectValueFragment`.
- **Do** deduplicate keys before hitting the data layer.
- **Do** preserve input order for Kotlin field batches and original context keys for node batches and Java field batches.
- **Don’t** perform per-context DB calls inside `batchResolve`.
- **Don’t** allocate large intermediate structures unnecessarily — map directly back to contexts.

> See [Best Practices](../../../docs/developers/best_practices/index.md) for the consolidated reference. For the complete batch-resolution API and advanced strategies, see the [Batch Resolution developer reference](../../../docs/developers/resolvers/batch_resolution.md).
