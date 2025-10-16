---
title: Module
description: What a module is in this demo, how modules are laid out, and how schemas/scopes do the heavy lifting.
layout: docs/single
weight: 1
linkTitle: Modules
---

A module:
- Encapsulates its **GraphQL SDL** (`*.graphqls`) under a fixed directory.
- Owns the **resolvers** that implement the fields declared in that SDL.
- Contributes its slice of the overall app surface, which is later **assembled** into a central schema.

> The real “visibility” and “security” behavior comes from **schemas** and **scopes**. Modules help you **organize**
> business subdomains cleanly; schemas/scopes decide what is exposed at runtime.

## StarWars modules

### universe

**Focus:** the in-universe graph of entities and their relationships. Typical modules include `characters`, `planets`,
`species`, and cross-entity links (for example, a character’s homeworld or species).

**Common fields and patterns:**

- `Character.id`, `Character.name`, `Character.homeworld`, `Character.species`.

### filmography

**Focus:** the catalog of films and character appearances. Typical modules cover `films` and derived relationships such
as “which characters appear in which film”.

**Common fields and patterns:**

- `Film.id`, `Film.title`, `Film.releaseDate`.
- `Film.characters` (list of appearing characters).

## Where module SDL lives

Each module keeps its schema files in a stable location so the build can find them:

```
/<modules>/
  └─ src/main/viaduct/schema/*.graphqls
```

Concrete examples from this repo:

```
modules/filmography/src/main/viaduct/schema/Character.graphqls
modules/filmography/src/main/viaduct/schema/Film.graphqls
modules/universe/src/main/viaduct/schema/Planet.graphqls
modules/universe/src/main/viaduct/schema/Species.graphqls
modules/universe/src/main/viaduct/schema/Starship.graphqls
modules/universe/src/main/viaduct/schema/Vehicle.graphqls
```

During the build, these are transformed into a **central schema** that merges all partitions.

## Where module resolvers live

Resolvers for each module are implemented in the module’s Kotlin sources.

Concrete examples (filmography):

```
modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/films/queries/AllFilmsQueryResolver.kt
modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/films/resolvers/FilmNodeResolver.kt
modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/films/resolvers/CharactersFieldResolver.kt
modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/films/resolvers/PlanetsFieldResolver.kt
modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/characters/resolvers/FilmCountBatchResolver.kt
```

The `com.airbnb.viaduct.module-gradle-plugin` also generates **resolver base classes** you extend, under:

```
modules/<module>/build/generated-sources/viaduct/resolverBases/...
```

(For example: `CharacterResolvers.kt`, `FilmResolvers.kt`, `QueryResolvers.kt`, `NodeResolvers.kt`.)

## How modules are assembled

The **builder** :
1. Discovers all SDL files per module (schema partitions).
2. Registers **schema IDs** that point to a concrete surface (public vs extras).
3. At runtime, the **controller** chooses a `schemaId` (see `determineSchemaId(scopes)`) and builds an `ExecutionInput`.
4. Viaduct executes the request against that schema, applying `@scope` visibility during planning.

See the **Schemas** and **Scope** pages for runtime selection and visibility rules.


## What belongs in a module

- **SDL files** (`*.graphqls`) for the domain’s types, queries, and directives (use `@scope`, `@idOf`, `@oneOf` where appropriate).
- **Resolvers** implementing the domain’s behavior (`queryresolvers`, `fieldresolvers`, `batchresolvers`, `mutations`).
- **Tests** providing integration coverage for the module’s surface.

{{< prevnext >}}
