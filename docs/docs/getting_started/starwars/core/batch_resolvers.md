---
title: Batch Field Resolvers
description: Implementing batch field resolvers in Viaduct.
---


Batch field resolvers process multiple field requests **in one pass**, dramatically improving performance when the same
field is selected across many parent objects. Viaduct guarantees the **input order** of contexts and expects you to
return results in the **same order**.

## Schema declaration

To opt into batching, add `isBatching: true` to the `@resolver` directive. This tells codegen to generate a
`batchResolve` method instead of `resolve`:

```graphql
type Character implements Node @scope(to: ["default"]) @resolver(isBatching: true) {
  id: ID!
  name: String
  filmCount: Int @resolver(isBatching: true)
}
```

## Where batching fits in the execution flow

1. The planner groups identical field selections across all matching parent objects in the operation.
2. Viaduct calls your `batchResolve(contexts: List<Context>)`.
3. You perform **one** data fetch per unique key set (for example, character IDs).
4. You map results **back to each context** and return a `List<FieldValue<T>>` **aligned with the input order**.

## Minimal example (counts per character)


{{ codetag("demoapps/starwars/modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/characters/resolvers/CharacterFilmCountResolver.kt", "film_count_batch_resolver", lang="kotlin") }}


## Choosing the fragment

The `objectValueFragment` declares the parent fields your resolver needs. Keep it **minimal** — requesting only `id`
is typical for lookup scenarios. If you require additional, cheap fields (for example, `name` for formatting), add them
here so they are available on `ctx.objectValue` without extra work.


{{ codetag("demoapps/starwars/modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/characters/resolvers/CharacterFilmCountResolver.kt", "film_count_batch_resolver", lang="kotlin") }}


## Implementing batch resolvers in node resolvers

Node resolvers can also be batched by declaring `@resolver(isBatching: true)` on the type. The pattern is similar,
but you receive a list of `GlobalID`s instead of `Context`s. You can use `GlobalID.toInternalID()` to extract your
internal ID


{{ codetag("demoapps/starwars/modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/characters/resolvers/CharacterNodeResolver.kt", "node_batch_resolver_example", lang="kotlin") }}

## Error handling and nullability

- Return a sensible default or `FieldValue.ofNull()` for missing items (match schema nullability).
- Avoid throwing for “not found” cases — reserve exceptions for **unexpected** failures.
- Ensure the size of the returned list matches `contexts.size` exactly.

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


{{ codetag("demoapps/starwars/modules/filmography/src/main/viaduct/schema/Character.graphqls", "all_characters", lang="kotlin") }}


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
- **Do** return results in the same order as the input contexts.
- **Don’t** perform per-context DB calls inside `batchResolve`.
- **Don’t** allocate large intermediate structures unnecessarily — map directly back to contexts.


