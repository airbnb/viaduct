This is the Viaduct Open Source Software (OSS) root directory.

Viaduct is an opinionated GraphQL server.

A systems builder embeds [`viaduct.service.api.Viaduct`](core/service/api/src/main/kotlin/viaduct/service/api/Viaduct.kt) in their server and routes requests to `Viaduct.execute`, which delegates execution to [`viaduct.engine.api.Engine`](core/engine/api/src/main/kotlin/viaduct/engine/api/Engine.kt).

For an end-to-end example of a service that embeds Viaduct, see the demonstration applications in `demoapps`, especially `demoapps/starwars`.  For more on testing with the demoapps see `demoapps/AGENTS.md`

## Bootstrapping and service integration

Tenant bootstrapping uses generated module config JSON under `META-INF/viaduct/modules/`. The service supplies a `TenantModuleInjectorFactory`; the engine loads the configs and constructs executors. `TenantAPIBootstrapper` and runtime scanning for resolver classes have been removed.

- [Service guidance](core/service/AGENTS.md) — builder configuration, injector factory lifecycle, `@TenantBootstrapper`, and test setup.
- [Engine guidance](core/engine/AGENTS.md) — config discovery, executor loading, built-in resolvers, and startup validation.
- [Bootstrap identity](impldocs/execution-registry-bootstrap.md) — config keys, uniqueness, and source replacement during registry rebuilds.

## Navigating the Gradle build

- [`impldocs/gradle-build-architecture.md`](impldocs/gradle-build-architecture.md) - Documents Viaduct's included-build architecture.
- [`impldocs/e2e-snapshot-test.md`](impldocs/e2e-snapshot-test.md) - Test publication process using a snapshot (good to use when you've changes the Gradle artifact logic)
- [`impldocs/execution-registry-ksp-pipeline.md`](impldocs/execution-registry-ksp-pipeline.md) - KSP three-stage pipeline for generating the tenant module config: isolation mode, stale-output cleanup, and why assembly is non-incremental.

## Shell notes

- This workspace commonly runs commands under `zsh`; `status` is a readonly special parameter there. When wrapping Gradle commands and preserving exit codes, use a variable name like `rc` or `exit_code`, not `status`.

## Navigating the Shared Libraries

The `core/shared/` directory contains libraries used across the Viaduct engine and tenant APIs:

- [Codegen](core/shared/codegen/AGENTS.md) — Bytecode generation for invoking tenant field resolvers.
- [ViaductSchema](core/shared/viaductschema/AGENTS.md) — Unified abstraction over GraphQL schemas.
- **`core/shared/apiannotations/`** — Annotations used in the Viaduct public API.
- [Bootstrap data](core/shared/bootstrap/README.md) — The config model shared by build tooling and the engine; intentionally independent of the engine runtime.

The [Tenant API](core/tenant/api/module.md) provides the interfaces application developers use to write resolvers.

## Implementation Documentation

- [`core/x/remoteresolvers/impldocs/architecture.md`](core/x/remoteresolvers/impldocs/architecture.md) — Experimental remote resolver architecture: independent process bootstrap, proxy and callback RPC flows, wire formats, in-memory registries, lifecycle, error isolation, and current cross-process limitations.
- [`core/shared/errors/impldocs/executor-error-boundaries.md`](core/shared/errors/impldocs/executor-error-boundaries.md) — Exception hierarchy (`PassthroughException`, `TenantException`), the two-boundary wrapping pattern on executor SPI entry points, `InvocationTargetException` unwrapping, and how attributed exceptions surface in GraphQL error responses.
- [`impldocs/modern-access-check.md`](impldocs/modern-access-check.md) — Access check architecture: `CheckerExecutorFactory` SPI, QueryPlan RSS embedding, the OER multi-slot pattern, and how checker results flow through completion.
- [`impldocs/object-lifecycles.md`](impldocs/object-lifecycles.md) — Description of the "lifecycles" of major objects over the lifetime of a Viaduct runtime instance (related to injection scopes).
- [`impldocs/subquery-execution.md`](impldocs/subquery-execution.md) — Cross-cutting documentation about the `ExecutionHandle` abstraction and how `ctx.query()`/`ctx.mutation()` drive subquery execution across the engine.
- [`impldocs/exception-hierarchy.md`](impldocs/exception-hierarchy.md) — Exception hierarchy specification: `TenantException` and `PassthroughException` marker interfaces, error handler semantics.
- [`impldocs/testing-guidance.md`](impldocs/testing-guidance.md) — Assertion library guidance: when to use JUnit 5 vs Kotest, and which libraries are prohibited.
