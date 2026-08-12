This is the Viaduct Open Source Software (OSS) root directory.

Viaduct is an opinionated GraphQL server.

A systems builder wanting to embed Viaduct into their web server would create an instance of [`viaduct.service.api.Viaduct`](service/api/src/main/kotlin/viaduct/service/api/Viaduct.kt) and create a route that would call the `Viaduct.execute` method.  This method, under the covers, calls [`viaduct.engine.api.Engine.execute`](engine/api/src/main/kotlin/viaduct/engine/api/Engine.kt).

For an end-to-end example of a service that embeds Viaduct, see the demonstration applications in `demoapps`, especially `demoapps/starwars`.  For more on testing with the demoapps see `demoapps/AGENTS.md`

For more information on constructing a `Viaduct` object see `service/AGENTS.md`.

For more information about the details of how Viaduct executes GraphQL operations, you can look at `engine/AGENTS.md`, although it is often helpful to start with `service/AGENTS.md` to understand how `viaduct.engine.api.Engine` instances get configured.

## Navigating the Gradle build

- [`impldocs/gradle-build-architecture.md`](impldocs/gradle-build-architecture.md) - Documents Viaduct's included-build architecture.
- [`impldocs/e2e-snapshot-test.md`](impldocs/e2e-snapshot-test.md) - Test publication process using a snapshot (good to use when you've changes the Gradle artifact logic)
- [`impldocs/execution-registry-ksp-pipeline.md`](impldocs/execution-registry-ksp-pipeline.md) - KSP three-stage pipeline for generating the tenant module config: isolation mode, stale-output cleanup, and why assembly is non-incremental.
- [`impldocs/execution-registry-bootstrap.md`](impldocs/execution-registry-bootstrap.md) - Execution-registry bootstrap identity: the `<tenantName, apiName>` config key, the one-config-per-key build invariant, `ModuleConfigSource` semantics, and the right-biased hotswap overlay.

## Shell notes

- This workspace commonly runs commands under `zsh`; `status` is a readonly special parameter there. When wrapping Gradle commands and preserving exit codes, use a variable name like `rc` or `exit_code`, not `status`.

## Navigating the Shared Libraries

The `shared/` directory contains libraries used across the Viaduct engine and tenant APIs:

- **`shared/codegen/`** — Bytecode generation library used to compile tenant field resolvers into JVM bytecode at startup. See `shared/codegen/AGENTS.md` for details.
- **`shared/viaductschema/`** — Unified abstraction layer for working with GraphQL schemas. See `shared/viaductschema/AGENTS.md` for details.
- **`shared/apiannotations/`** — Annotations used in the Viaduct public API.
- **`tenant/`** — The Tenant API, which application developers use to write resolvers. See `tenant/api/module.md` for package-level descriptions.

## Implementation Documentation

- [`core/x/remoteresolvers/impldocs/architecture.md`](core/x/remoteresolvers/impldocs/architecture.md) — Experimental remote resolver architecture: independent process bootstrap, proxy and callback RPC flows, wire formats, in-memory registries, lifecycle, error isolation, and current cross-process limitations.
- [`core/shared/errors/impldocs/executor-error-boundaries.md`](core/shared/errors/impldocs/executor-error-boundaries.md) — Exception hierarchy (`PassthroughException`, `TenantException`), the two-boundary wrapping pattern on executor SPI entry points, `InvocationTargetException` unwrapping, and how attributed exceptions surface in GraphQL error responses.
- [`impldocs/modern-access-check.md`](impldocs/modern-access-check.md) — Access check architecture: `CheckerExecutorFactory` SPI, QueryPlan RSS embedding, the OER multi-slot pattern, and how checker results flow through completion.
- [`impldocs/object-lifecycles.md`](impldocs/object-lifecycles.md) — Description of the "lifecycles" of major objects over the lifetime of a Viaduct runtime instance (related to injection scopes).
- [`impldocs/subquery-execution.md`](impldocs/subquery-execution.md) — Cross-cutting documentation about the `ExecutionHandle` abstraction and how `ctx.query()`/`ctx.mutation()` drive subquery execution across the engine.
- [`impldocs/exception-hierarchy.md`](impldocs/exception-hierarchy.md) — Exception hierarchy specification: `TenantException` and `PassthroughException` marker interfaces, error handler semantics.
- [`impldocs/testing-guidance.md`](impldocs/testing-guidance.md) — Assertion library guidance: when to use JUnit 5 vs Kotest, and which libraries are prohibited.
