---
title: Module
description: What a module is in this demo, how modules are laid out, and how schemas/scopes do the heavy lifting.
---


A module:

- Encapsulates its **GraphQL SDL** (`*.graphqls`) under a fixed directory.
- Owns the **resolvers** that implement the fields declared in that SDL.
- Contributes its slice of the overall app surface, which is later **assembled** into a central schema.

> The real “visibility” and “security” behavior comes from **schemas** and **scopes**. Modules help you **organize** business subdomains cleanly; schemas/scopes decide what is exposed at runtime.

## StarWars modules

### universe

**Focus:** planets, species, starships, vehicles, and their relationships.

**Common fields and patterns:**

- `Planet.id`, `Planet.name`, `Species.id`, `Species.name`, and the starship and vehicle fields.

### filmography

**Focus:** characters, the catalog of films, and character appearances. The character resolvers also refer to types owned by `universe`, such as a character's homeworld or species.

**Common fields and patterns:**

- `Film.id`, `Film.title`, `Film.releaseDate`.
- `Film.characters` (list of appearing characters).
- `Character.id`, `Character.name`, `Character.homeworld`, `Character.species`.

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

Resolvers for each module are implemented in the module’s language-specific source directory.

Concrete examples (filmography):

=== "Kotlin"

    ```
    modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/films/queries/AllFilmsQueryResolver.kt
    modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/films/resolvers/FilmNodeResolver.kt
    modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/films/resolvers/CharactersFieldResolver.kt
    modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/films/resolvers/PlanetsFieldResolver.kt
    modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/characters/resolvers/FilmCountBatchResolver.kt
    ```

=== "Java"

    ```text
    modules/filmography/src/main/java/com/example/starwars/modules/filmography/films/queries/AllFilmsQueryResolver.java
    modules/filmography/src/main/java/com/example/starwars/modules/filmography/films/resolvers/FilmNodeResolver.java
    modules/filmography/src/main/java/com/example/starwars/modules/filmography/films/resolvers/FilmCharactersResolver.java
    modules/filmography/src/main/java/com/example/starwars/modules/filmography/films/resolvers/FilmPlanetsResolver.java
    modules/filmography/src/main/java/com/example/starwars/modules/filmography/characters/resolvers/CharacterFilmCountResolver.java
    ```

    The Java module plugin generates Java GRTs and resolver base classes before Java compilation. Its annotation processor records resolver and document metadata for runtime discovery.

The Kotlin module plugin generates **resolver base classes** you extend, under:

```
modules/<module>/build/generated-sources/viaduct/resolverBases/...
```

For example, Kotlin generates `CharacterResolvers.kt`, `FilmResolvers.kt`, `QueryResolvers.kt`, and `NodeResolvers.kt`. Java generates corresponding `.java` base classes in each configured module package, such as `com.example.starwars.filmography.resolverbases`.

## How modules are assembled

The **builder** :

1. Discovers all SDL files per module (schema partitions).
2. Registers **schema IDs** that point to a concrete surface (public vs extras).
3. At runtime, the **controller** serves each `schemaId` on its own route and builds an `ExecutionInput`.
4. Viaduct executes the request against that schema, which contains only the fields its scopes allow.

See the **Schemas** and **Scope** pages for runtime selection and visibility rules.


## What belongs in a module

- **SDL files** (`*.graphqls`) for the domain’s types, queries, and directives (use `@scope`, `@idOf`, `@oneOf` where appropriate).
- **Resolvers** implementing the domain’s behavior (`queryresolvers`, `fieldresolvers`, `batchresolvers`, `mutations`).
- **Tests** providing integration coverage for the module’s surface.

> For the full multi-tenancy model — how tenant modules register schemas and resolvers in a shared Viaduct instance — see the [Multi-tenancy service engineer docs](../../../docs/service_engineers/multi_tenancy/index.md).
