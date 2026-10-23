---
title: Scope Directive
description: Control schema visibility and multi-module access with @scope; the Star Wars demo serves each scoped schema on its own route.
---


The `@scope` directive is part of **Viaduct’s security and multi-tenancy model**. It restricts which GraphQL types, fields, or directives are visible and executable to a given request, depending on which **scoped schema** your application selects for that request.

## Purpose and context

`@scope` enforces **visibility boundaries** within a schema. A single service can present different data surfaces for different modules, users, or environments — all from the same runtime. It is central to the security model of Viaduct, ensuring that clients only see and execute what their scopes permit.

Typical use cases:

- Expose different schemas or fields to separate modules.
- Hide beta or experimental fields behind an “extras” scope.
- Limit sensitive data (like internal notes or metadata) to privileged scopes.
- Support gradual rollout of features per environment or user segment.

## Code definitions


{{ codetag("demoapps/starwars/modules/filmography/src/main/viaduct/schema/Character.graphqls", "scope_example", lang="graphql") }}


{{ codetag("demoapps/starwars/modules/universe/src/main/viaduct/schema/Species.graphqls", "schemas_extras_example", lang="graphql") }}


In this example, `searchCharacter` is in every scoped schema that includes the `default` scope. The `culturalNotes` is defined in `extras`.

This demo app hides `extras` from requests to `/graphql` and serves it on `/extras/graphql`.

## How scopes are evaluated

A request does not carry scopes into Viaduct. It selects a registered scoped schema by its `SchemaId`, and that schema contains only the fields visible to its scopes. **Your application** decides which scoped schema each request gets.

In the Star Wars demo, the route decides. Each declared scoped schema is served on its own path:

- `POST /graphql` runs against `publicSchema` (scope `default`).
- `POST /extras/graphql` runs against `publicSchemaWithExtras` (scopes `default` and `extras`).
- A field is in a scoped schema if **any** of the schema's scopes appears in the field's `to:` list. Fields outside it cannot be queried and are omitted from introspection.

Because each route serves one fixed schema, introspection on a route describes exactly what that route executes. The demo leaves both routes open to every caller; a real service authorizes callers per route.


## How the Star Wars demo routes requests to a scoped schema

`ViaductRestController` declares one route per scoped schema, and each passes its schema ID to a shared handler:


=== "Kotlin"

    {{ codetag("demoapps/starwars/src/main/kotlin/com/example/starwars/service/viaduct/ViaductRestController.kt", "schema_routes", lang="kotlin") | indent(4) }}

=== "Java"

    {{ codetag("demoapps/starwars-java/src/main/java/com/example/starwars/service/viaduct/ViaductRestController.java", "schema_routes", lang="java") | indent(4) }}


Those schemas are declared in `src/main/viaduct/scopes.yaml`:


{{ codefile("demoapps/starwars/src/main/viaduct/scopes.yaml") }}


`ViaductConfiguration.kt` (Kotlin) or `ViaductConfiguration.java` (Java) reads them back by id, so the controller never restates their scopes:


=== "Kotlin"

    {{ codetag("demoapps/starwars/src/main/kotlin/com/example/starwars/service/viaduct/ViaductConfiguration.kt", "declared_schemas", lang="kotlin") | indent(4) }}

=== "Java"

    {{ codetag("demoapps/starwars-java/src/main/java/com/example/starwars/service/viaduct/ViaductConfiguration.java", "declared_schemas", lang="java") | indent(4) }}


See [Scope Schemas](../../../docs/service_engineers/scope_schemas/index.md) for the syntax of `scopes.yaml`.


With the correct schema, the controller builds the `ExecutionInput` for Viaduct


=== "Kotlin"

    {{ codetag("demoapps/starwars/src/main/kotlin/com/example/starwars/service/viaduct/ViaductRestController.kt", "execution_input", lang="kotlin") | indent(4) }}

=== "Java"

    {{ codetag("demoapps/starwars-java/src/main/java/com/example/starwars/service/viaduct/ViaductRestController.java", "execution_input", lang="java") | indent(4) }}


And runs the query, this is the entire logic of the controller:


=== "Kotlin"

    {{ codetag("demoapps/starwars/src/main/kotlin/com/example/starwars/service/viaduct/ViaductRestController.kt", "run_query", lang="kotlin") | indent(4) }}

=== "Java"

    {{ codetag("demoapps/starwars-java/src/main/java/com/example/starwars/service/viaduct/ViaductRestController.java", "run_query", lang="java") | indent(4) }}


### Demo example (Star Wars)

| Route (demo)      | Schema ID                | Accessible types/fields |
|-------------------|--------------------------|-------------------------|
| `/graphql`        | `publicSchema`           | All default types and fields. |
| `/extras/graphql` | `publicSchemaWithExtras` | Default plus extras types and fields. |

## Multi-tenancy and security boundaries

`@scope` provides a **soft isolation layer** between modules or schema segments. Unlike role-based access control, it operates when each **scoped schema is built**: elements outside its scopes are absent from it, so they are never planned or run.

In the Star Wars demo:

- The **default** slice exposes public entities like `Character`, `Planet`, and `Film`.
- The **extras** slice adds extended metadata (for example, `Species.culturalNotes`) for internal users.
- Both slices share infrastructure and resolvers while seeing different schema surfaces.

## Best practices

- Define a `default` scope for general availability.
- Keep scopes orthogonal — avoid overlapping responsibilities.
- Apply `@scope` explicitly to sensitive fields.
- Decide from a single source of truth (JWT claims, session, config) which callers may reach each scoped schema's route.
- Log the route and `schemaId` each request runs against.
- Define other scopes like `internal`, `admin`, or `beta` as needed, and limit the usage as described above.

> See [Best Practices](../../../docs/developers/best_practices/index.md) for the consolidated reference.

## Common mistakes

### 1. Selecting the scoped schema with a request header
A header makes one URL answer with several schemas, so a tool that introspects the URL sees only one of them. Serve each scoped schema on its own route instead.

### 2. Missing `default` scope
If no scope is declared where you expected one, the field may be invisible to all requests.

### 3. Overlapping scopes without clear ownership
Prefer clear, non-overlapping boundaries to reduce confusion and accidental exposure.

### 4. Relying solely on @scope for authorization
`@scope` governs **schema visibility**. Complement it with application-level authorization for data-level controls.

## Query example

`culturalNotes` and `specialAbilities` are only visible on `/extras/graphql`:

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

The demo's GraphiQL page sends queries to `/graphql`, so run this one against the extras route directly:

```shell
curl -s http://localhost:8080/extras/graphql \
  -H 'Content-Type: application/json' \
  -d '{"query": "{ node(id: \"U3BlY2llczox\") { ... on Species { name culturalNotes specialAbilities } } }"}'
```

## Debugging and testing

- In the demo, send the same query to `/graphql` and `/extras/graphql` to confirm visibility.
- Verify that restricted fields disappear from both introspection and responses.
- Add integration tests per scope slice (Kotlin: `ExtrasScopeTest.kt`; Java: `StarWarsJavaIntegrationTest.honorsExtrasScope()`).

> For the full `@scope` API and multi-tenancy patterns, see the [Scopes developer reference](../../../docs/developers/scopes/index.md). For declaring scoped schemas in `scopes.yaml`, see [Scope Schemas](../../../docs/service_engineers/scope_schemas/index.md).
