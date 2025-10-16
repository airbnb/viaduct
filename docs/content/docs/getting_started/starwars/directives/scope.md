---
title: Scope Directive
description: Control schema visibility and multi-module access with @scope; Star Wars demo uses an app-level header.
layout: docs/single
weight: 3
---

The `@scope` directive is part of **Viaduct’s security and multi-tenancy model**. It restricts which GraphQL types,
fields, or directives are visible and executable to a given request, depending on the **active scopes** provided by
your application to the Viaduct runtime.

## Purpose and context

`@scope` enforces **visibility boundaries** within a schema. A single service can present different data surfaces for
different modules, users, or environments — all from the same runtime. It is central to the security model of Viaduct,
ensuring that clients only see and execute what their scopes permit.

Typical use cases:

- Expose different schemas or fields to separate modules.
- Hide beta or experimental fields behind an “extras” scope.
- Limit sensitive data (like internal notes or metadata) to privileged scopes.
- Support gradual rollout of features per environment or user segment.

## Code definitions

{{< codetag path="demoapps/starwars/modules/filmography/src/main/viaduct/schema/Character.graphqls" tag="scope_example" lang="graphql">}}

{{< codetag path="demoapps/starwars/modules/universe/src/main/viaduct/schema/Species.graphqls" tag="schemas_extras_example" lang="graphql">}}

In this example, `searchCharacter` is available to any request with the `default` scope. The `culturalNotes` is defined in `extras`.

This demo apps will hide `extras` for queries that does not include the header `X-StarWars-Scopes: extras`.

## How scopes are evaluated

At runtime, **your application** should determine which scopes apply to the request and passes them to Viaduct’s execution
context. The framework then includes or excludes schema elements accordingly **at planning time**.

In the **Star Wars demo only**, scopes are provided via this header:

```json
{
  "X-StarWars-Scopes": "default,extras"
}
```

- Multiple scopes can be supplied (comma-separated in the demo).
- Access is granted if **any** of the active scopes match the element’s `to:` list.
- Non-matching fields are not planned or executed and are omitted from introspection.

> If the header is absent in the demo, the app assumes `default`. In your service, choose the convention that fits
> your auth model (for example, from JWT claims or request context).


## How the Star Wars passes scopes to Viaduct

In the file `ViaductGraphQLController`, the controller reads scopes from the header in the function

{{< codetag path="demoapps/starwars/src/main/kotlin/com/example/starwars/service/viaduct/ViaductRestController.kt" tag="parse_scopes">}}

From those scopes the app extracts the specific schema limited to those scopes only :

{{< codetag path="demoapps/starwars/src/main/kotlin/com/example/starwars/service/viaduct/ViaductRestController.kt" tag="determine_schema">}}

Those schemas are defined in the `ViaductConfiguration.kt` file as:

{{< codetag path="demoapps/starwars/src/main/kotlin/com/example/starwars/service/viaduct/ViaductConfiguration.kt" tag="schema_registration">}}

With the correct schema, the controller builds the `ExecutionInput` for Viaduct

{{< codetag path="demoapps/starwars/src/main/kotlin/com/example/starwars/service/viaduct/ViaductRestController.kt" tag="execution_input">}}

And runs the query, this is the entire logic of the controller:

{{< codetag path="demoapps/starwars/src/main/kotlin/com/example/starwars/service/viaduct/ViaductRestController.kt" tag="run_query">}}

### Demo example (Star Wars)

| Active scopes (demo) | Accessible types/fields |
|----------------------|-------------------------|
| `default`            | All default types and fields. |
| `extras`             | Extras-only fields; your app can also include default by convention. |
| `default,extras`     | Union of default plus extras fields. |

## Multi-tenancy and security boundaries

`@scope` provides a **soft isolation layer** between modules or schema segments. Unlike role-based access control, it
operates at **schema compilation** and **execution-planning** levels — unauthorized elements are not planned nor run.

In the Star Wars demo:

- The **default** slice exposes public entities like `Character`, `Planet`, and `Film`.
- The **extras** slice adds extended metadata (for example, `Species.culturalNotes`) for internal users.
- Both slices share infrastructure and resolvers while seeing different schema surfaces.

## Best practices

- Define a `default` scope for general availability.
- Keep scopes orthogonal — avoid overlapping responsibilities.
- Apply `@scope` explicitly to sensitive fields.
- Choose a single source of truth for scopes (JWT claims, session, config) and pass it to Viaduct consistently.
- Log active scopes per request for auditability.
- Define other scopes like `internal`, `admin`, or `beta` as needed, and limit the usage as described above.

## Common mistakes

### 1. Treating the demo header as a platform requirement
Viaduct does not require an HTTP header. The Star Wars header is **demo-specific**. Pick a mechanism aligned with your
auth stack and bind it to the request context.

### 2. Missing `default` scope
If no scope is declared where you expected one, the field may be invisible to all requests.

### 3. Overlapping scopes without clear ownership
Prefer clear, non-overlapping boundaries to reduce confusion and accidental exposure.

### 4. Relying solely on @scope for authorization
`@scope` governs **schema visibility**. Complement it with application-level authorization for data-level controls.

## Query example

culturalNotes and specialAbilities are only visible when the "extras" scope is provided :

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

## Debugging and testing

- In the demo, vary the header to confirm visibility; in your app, vary the scope source (claims/context).
- Verify that restricted fields disappear from both introspection and responses.
- Add integration tests per scope slice (see `ExtrasScopeTest.kt` in the demo).

{{< prevnext >}}
