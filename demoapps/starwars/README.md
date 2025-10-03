# Star Wars Demo App

This is an example GraphQL application built using [Viaduct](https://github.com/airbnb/viaduct), a composable GraphQL server in Kotlin.

This demo is intended as a reference implementation for developers exploring Viaduct or building their own GraphQL services with strongly typed, Kotlin-based infrastructure.

The app models the Star Wars universe with characters, films, species, planets, and starships and demonstrates how to implement resolvers, field-level context, pagination, fragments, and mock data using Viaduct’s conventions.

## Requirements

- Java JDK 21 is installed
- The `JAVA_HOME` environment variable is set correctly, or `java` is in the classpath

## Quick Start

For more information, check out the [Viaduct Getting Started](https://airbnb.io/viaduct/docs/getting_started/) docs.

### 1. Start the demo app

```bash
./gradlew bootRun
```

The server will start on `http://localhost:8080`.

### 2. Access GraphiQL

Open your browser and go to [http://localhost:8080/graphiql](http://localhost:8080/graphiql).

### 3. Try Example Queries

#### Basic Characters Query
```graphql
query {
  allCharacters(limit: 5) {
    id
    name
    homeworld { name }
  }
}
```

#### Scoped Queries

To perform a query that needs some scope field like `culturalNotes` on Species, that needs the "extras" scope, include the `X-Viaduct-Scope` header in your request:

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

And don't forget to add the correct scope header:

```json
{
  "X-Viaduct-Scope": "extras"
}
```

Add this JSON document to the `Headers` tab you'll notice _below_ the text-pane where you edit your query.

#### Complex Query with Batch Resolution
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

#### Film Query with Characters
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

### 4. Nodes and Node IDs

A central concept in Viaduct is that of a `Node`: an entity that can be fetched by an "identifier" (i.e., a primary key). In GraphQL these identifiers have the type `ID`, and `Node` types always have an `id` field containing the `Node`'s identifier.

Internally, an `ID` consists of two components: a `Node` type name, and a "local" identifier of the particular `Node` instance. For example, in the StarWars application, the `Character` with "local id" 5 happens to be Obi-Wan Kenobi.

To prevent developers from "hardwiring" any particular representation of identifiers into their code, Viaduct uses an encoding of `ID`s that obscures their content a bit. However, in GraphiQL, you do need to provide encoded `ID`s to fields like `Query.node`. To help you navigate the data set, we've incorporated an encoder (and decoder) for IDs. Along the left-hand side of GraphiQL, you'll see a "key" icon (🔑). If you press that icon, a panel will come up for encoding IDs. Type into the top text box a string of the form `TypeName:LocalId`. So, for example, if you enter "Character:5" into that box, the string "Q2hhcmFjdGVyOjU=" will be displayed. In the query panel of GraphiQL, you can use this ID in a query like:

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

In your result, you should see:

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

You can put this "UGxhbmV0OjQ=" ID into the decoder tool, which will tell you this is the ID for "Planet:4". You can also drop this identifier into a `node` query to retrieve the name of this planet:

```graphql
query {
  node(id: "UGxhbmV0OjQ=") {
    ... on Planet {
      name
    }
  }
}
```

In your result, you should see:

```json
{
  "data": {
    "node": {
      "name": "Stewjon"
    }
  }
}
```

## Technical Deep Dive

For comprehensive technical details - especially if you want to write your own application, see [DEEPDIVE.md](DEEPDIVE.md).
