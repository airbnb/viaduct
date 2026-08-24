---
title: Pagination
description: Relay-compliant pagination support in Viaduct.
---

# Pagination

Viaduct implements the [Relay Connection specification](https://relay.dev/graphql/connections.htm), providing cursor-based pagination with built-in builder utilities.

## Schema

Define your connection and edge types using the [`@connection`](../directives/connection.md) and [`@edge`](../directives/edge.md) directives:

```graphqls
type FilmConnection @connection {
  edges: [FilmEdge!]!
  pageInfo: PageInfo!
}

type FilmEdge @edge {
  node: Film!
  cursor: String!
}
```

Fields that return a connection accept standard Relay pagination arguments:

```graphqls
type Character implements Node {
  id: ID!
  name: String!
  films(first: Int, after: String): FilmConnection!
}
```

## Building Connection Responses

Connection builders provide three strategies depending on your backend:

### `fromList` — in-memory list

Use when your resolver has the full dataset. Viaduct handles slicing and cursor encoding automatically:

```kotlin
@Resolver
class CharacterFilmsResolver : CharacterResolvers.Films() {
  override suspend fun resolve(ctx: Context): FilmConnection {
    val allFilms = filmRepository.getFilmsForCharacter(ctx.getObjectValue().getIdOrThrow().internalID)
    return FilmConnection.Builder(ctx)
      .fromList(allFilms) { film -> Film.Builder(ctx).title(film.title).build() }
      .build()
  }
}
```

### `fromSlice` — offset/limit backend

Use when your backend accepts offset and limit. Over-fetch by one to detect `hasNextPage`:

```kotlin
@Resolver
class CharacterFilmsResolver : CharacterResolvers.Films() {
  override suspend fun resolve(ctx: Context): FilmConnection {
    val (offset, limit) = ctx.arguments.toOffsetLimit()
    val fetched = filmService.getFilms(offset, limit + 1)
    return FilmConnection.Builder(ctx)
      .fromSlice(fetched.take(limit), hasNextPage = fetched.size > limit) { film ->
        Film.Builder(ctx).title(film.title).build()
      }
      .build()
  }
}
```

### `fromEdges` — native cursors or edge metadata

Use when your backend returns native cursors, or when your edge type carries extra fields beyond `node` and `cursor`:

```kotlin
@Resolver
class CharacterFilmsResolver : CharacterResolvers.Films() {
  override suspend fun resolve(ctx: Context): FilmConnection {
    val response = filmService.getFilms(cursor = ctx.arguments.after, limit = ctx.arguments.first ?: 20)
    return FilmConnection.Builder(ctx)
      .fromEdges(
        edges = response.films.map { film ->
          FilmEdge.Builder(ctx)
            .node(Film.Builder(ctx).title(film.title).build())
            .cursor(film.cursor)
            .build()
        },
        hasNextPage = response.hasMore
      )
      .build()
  }
}
```

## Full Documentation

For the complete pagination reference — argument types, `toOffsetLimit()`, cursor encoding, and `PageInfo` handling — see the [Pagination developer docs](../../../docs/developers/pagination/index.md).

## Resources

- [Relay Connection Specification](https://relay.dev/graphql/connections.htm)
- [`@connection` directive](../directives/connection.md)
- [`@edge` directive](../directives/edge.md)
