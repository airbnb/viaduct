# Star Wars Demo App

> Doc type: Reference

This is a sample GraphQL application built using [Viaduct](https://github.com/airbnb/viaduct), a composable GraphQL
server in Kotlin.

This app models the Star Wars universe with characters, films, species, planets, and starships. It demonstrates
how to implement resolvers, field-level context, pagination, fragments, and mock data using Viaduct’s conventions.

## Requirements

- Java JDK 21
- `JAVA_HOME` is correctly set, or `java` is available in your `PATH`

## Quick start

### Start the demo app

```bash
./gradlew run
```

This will start the server at `http://localhost:8080`.

### Access GraphiQL

Open your browser and go to [GraphiQL](http://localhost:8080/graphiql) interface:

```
http://localhost:8080/graphiql
```

### Try example queries

#### Basic characters query

```graphql
query {
  allCharacters(limit: 5) {
    id
    name
    homeworld {
      name
    }
  }
}
```

#### Scoped queries

The `ViaductGraphQLController.kt` applies scope metadata from the `X-Viaduct-Scopes` header
into the GraphQL context.

Some fields—like `Species.culturalNotes` are marked with `@scope(to: ["extras"])`. These will only resolve
if the `"extras"` scope is present in the context.

Use a query like:

```graphql
query {
  node(id: "U3BlY2llczox") {
    ... on Species {
      name
      culturalNotes
      specialAbilities
    }
  }
}
```

In GraphiQL, add this to the **Headers** tab below the query pane:

```json
{
  "X-Viaduct-Scopes": "extras"
}
```

#### Complex query with batch resolution

Run a complex character query with batch‑resolved fields.

```graphql
query {
  allCharacters(limit: 3) {
    name
    homeworld { name }
    species { name }
    filmCount
    richSummary
  }
}
```

> Fields such as `filmCount` and `richSummary` are computed by Character resolvers. @See `CharacterResolvers.kt`,
`SpeciesBatchResolver.kt` and `FilmCountBatchResolver.kt`.

Resolution is batched by Viaduct’s resolver execution model (and/or DataLoaders if configured), which helps avoid N+1
patterns when the query asks these derived fields across multiple characters.

#### Film query with characters

Query films with their main characters

```graphql
query {
  allFilms {
    title
    director
    mainCharacters {
      name
      homeworld { name }
    }
  }
}
```

## Nodes and global IDs

A central concept in Viaduct is the `Node`: any object retrievable by a globally unique `ID`. Every `Node` has
an `id` field.

Internally, a Viaduct `ID` consists of as base64-encoded string :

```
TypeName:LocalId
```

For example:

```
Character:5 → "Q2hhcmFjdGVyOjU="
```

In GraphiQL you can use the key icon in the toolbar (🔑) to encode or decode these IDs.

#### Example: look up a character by ID

```graphql
query {
  node(id: "Q2hhcmFjdGVyOjU=") {
    ... on Character {
      name
      homeworld {
        id
      }
    }
  }
}
```

Returns:

```json
{
  "data": {
    "node": {
      "name": "Obi-Wan Kenobi",
      "homeworld": {
        "id": "UGxhbmV0OjQ="
      }
    }
  }
}
```

You request the identifier `UGxhbmV0OjQ=` as a `node` query to retrieve the name of this planet:

```graphql
query {
  node(id: "UGxhbmV0OjQ=") {
    ... on Planet {
      name
    }
  }
}
```

> If you decode the id from base64, you can notice that the id `UGxhbmV0OjQ=` is decoded to `"Planet:4"`.

Which returns:

```json
{
  "data": {
    "node": {
      "name": "Stewjon"
    }
  }
}
```

## Technical deep dive

For a deeper technical explanation of how the system works, see the [Getting Started Guide](https://airbnb.io/viaduct/docs/getting_started/).
