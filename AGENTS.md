This is the Viaduct Open Source Software (OSS) root directory.

Viaduct is an opinionated GraphQL server.

A systems builder wanting to embed Viaduct into their web server would create an instance of [`viaduct.service.api.Viaduct`](service/api/src/main/kotlin/viaduct/service/api/Viaduct.kt) and create a route that would call the `Viaduct.execute` method.  This method, under the covers, calls [`viaduct.engine.api.Engine.execute`](engine/api/src/main/kotlin/viaduct/engine/api/Engine.kt).

For an end-to-end example of a service that embeds Viaduct, see the demonstration applications in `demoapps`, especially `demoapps/starwars`.

For more information on constructing a `Viaduct` object see `service/AGENTS.md`.

For more information about the details of how Viaduct executes GraphQL operations, you can look at `engine/AGENTS.md`, although it is often helpful to start with `service/AGENTS.md` to understand how `viaduct.engine.api.Engine` instances get configured.

## Navigating the Shared Libraries

The `shared/` directory contains libraries used across the Viaduct engine and tenant APIs:

- **`shared/codegen/`** — Bytecode generation library used to compile tenant field resolvers into JVM bytecode at startup. See `shared/codegen/AGENTS.md` for details.
- **`shared/viaductschema/`** — Unified abstraction layer for working with GraphQL schemas. See `shared/viaductschema/AGENTS.md` for details.
- **`shared/apiannotations/`** — Annotations used in the Viaduct public API.
- **`tenant/`** — The Tenant API, which application developers use to write resolvers. See `tenant/api/module.md` for package-level descriptions.

## Implementation Documentation

- [`core/errors/impldocs/executor-error-boundaries.md`](core/errors/impldocs/executor-error-boundaries.md) — Exception hierarchy (`PassthroughException`, `TenantException`), the two-boundary wrapping pattern on executor SPI entry points, `InvocationTargetException` unwrapping, and how attributed exceptions surface in GraphQL error responses.
- [`impldocs/modern-access-check.md`](impldocs/modern-access-check.md) — Access check architecture: `CheckerExecutorFactory` SPI, QueryPlan RSS embedding, the OER multi-slot pattern, and how checker results flow through completion.
- [`impldocs/subquery-execution.md`](impldocs/subquery-execution.md) — Cross-cutting documentation about the `ExecutionHandle` abstraction and how `ctx.query()`/`ctx.mutation()` drive subquery execution across the engine.
