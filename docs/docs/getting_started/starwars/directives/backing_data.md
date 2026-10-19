---
title: backingData Directive
description: Share pre-fetched data between sibling field resolvers to avoid duplicate calls.
---


The `@backingData` directive declares a field whose sole purpose is to fetch data once and share it with other resolvers on the same object. Viaduct guarantees the backing resolver runs **at most once** per parent object, regardless of how many sibling fields consume it.

Use `@backingData` when two or more field resolvers on the same type need the same upstream data and you want to avoid duplicate calls.

## Schema declaration

Apply `@backingData` on a field typed as `BackingData` (a Viaduct built-in marker type, never exposed to clients). The `class` argument points to the application class that holds the fetched data:


{{ codetag("demoapps/starwars/modules/filmography/src/main/viaduct/schema/Film.graphqls", "backing_data_schema", lang="graphql") }}


The field must be declared directly on an object type. `BackingData` fields cannot be declared on interfaces, inherited from interfaces, or used as input fields. The `BackingData` type and `@backingData` directive must always appear together.

## Schema visibility

Every field whose base type is `BackingData` is tenant-local automatically. Viaduct keeps it in the internal Full schema so resolvers and generated code can use it, but removes it from Base and Scoped executable schemas. Clients therefore cannot query backing-data fields.

Do not add `@tenantLocal` to a backing-data field. Its `BackingData` type already supplies the same visibility behavior.


## The backing data class

A Kotlin data class or Java record holds the pre-fetched data. Keep it minimal, shared state only:


=== "Kotlin"

    {{ codetag("demoapps/starwars/modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/films/models/FilmCastData.kt", "backing_data_class", lang="kotlin") | indent(4) }}

=== "Java"

    {{ codefile("demoapps/starwars-java/modules/filmography/src/main/java/com/example/starwars/modules/filmography/films/models/FilmCastData.java", lang="java") | indent(4) }}


## The backing data resolver

A resolver that fetches the data once per parent object. It reads the parent's ID from the object value and calls the repository:


=== "Kotlin"

    {{ codetag("demoapps/starwars/modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/films/resolvers/FilmCastDataResolver.kt", "backing_data_resolver", lang="kotlin") | indent(4) }}

=== "Java"

    {{ codefile("demoapps/starwars-java/modules/filmography/src/main/java/com/example/starwars/modules/filmography/films/resolvers/FilmCastDataResolver.java", lang="java") | indent(4) }}


## Consuming the backing data

Java code generation excludes `BackingData` fields from generated GRT getters and builders. The demo accesses the engine-backed value through `getJavaEngineObjectData().getOrNull("castData")` and casts it to `FilmCastData`. The `objectValueFragment` dependency is still required. This uses internal engine APIs and is shown only to explain the demo implementation; it is not a supported public Java GRT accessor. A generated Java `getCastData()` method does not exist. For application code, keep shared data loading in a repository or service until a public typed Java accessor is available.

Other resolvers declare `castData` in their `objectValueFragment` to receive the pre-fetched data. Viaduct automatically ensures the backing resolver completes before these consumers run:


=== "Kotlin"

    {{ codetag("demoapps/starwars/modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/films/resolvers/FilmCharactersResolver.kt", "backing_data_consumer", lang="kotlin") | indent(4) }}

=== "Java"

    {{ codefile("demoapps/starwars-java/modules/filmography/src/main/java/com/example/starwars/modules/filmography/films/resolvers/FilmCharactersResolver.java", lang="java") | indent(4) }}


Multiple resolvers can share the same backing data, each declares it in their fragment, but the fetch happens only once:


=== "Kotlin"

    {{ codetag("demoapps/starwars/modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/films/resolvers/FilmCharacterCountSummaryResolver.kt", "backing_data_consumer_2", lang="kotlin") | indent(4) }}

=== "Java"

    {{ codefile("demoapps/starwars-java/modules/filmography/src/main/java/com/example/starwars/modules/filmography/films/resolvers/FilmCharacterCountSummaryResolver.java", lang="java") | indent(4) }}


## How it works at runtime

1. A client queries both `characters` and `characterCountSummary` on a Film.
2. Viaduct sees both resolvers need `castData` in their `objectValueFragment`.
3. `FilmCastDataResolver` runs **once**, fetching character IDs from the repository.
4. The result (`FilmCastData`) is injected into both consuming resolvers via `ctx.getObjectValue()` (Kotlin) or `context.getObjectValue().getJavaEngineObjectData().getOrNull("castData")` (Java).
5. Each consumer uses the shared data without any additional repository call.

## Design guidelines

- Use `@backingData` when two or more sibling resolvers need the same upstream data.
- Keep the data class minimal — only the shared state needed by consumers.
- The backing resolver should do I/O; consumers should do transformation only.
- The field type must be `BackingData` — this is a Viaduct marker type that is never exposed in the client schema.
- Do not declare backing-data fields on interfaces or fields inherited from interfaces.

## Related

- [Field resolvers](../core/field_resolvers.md) — `@backingData` fields always also carry `@resolver`; see field resolvers for the general resolver pattern
- [Schema Reference: BackingData](../../../docs/developers/schema_reference/index.md#backingdata) — covers the `BackingData` scalar and `@backingData` directive
