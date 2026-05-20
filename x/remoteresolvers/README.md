# Remote Resolvers (Experimental)

Run Viaduct node resolvers in a separate process from the engine, talking to it over gRPC. This is useful when a tenant's resolvers are heavy or unreliable, or when you want to deploy them on their own cadence — moving them out of the host JVM gives you runtime isolation without changing how resolvers are written.

> **Status:** experimental. APIs may change without notice.

## How it works

A small proxy on the engine side intercepts node resolution and forwards it to a `RemoteResolverService` over gRPC. That service runs the resolver, and if the resolver needs to fan back out (`ctx.query(...)`) it does so through a callback service over the same channel — so resolvers behave identically whether they run locally or remotely.

This module currently ships only the in-process transport (both ends in one JVM, over gRPC's in-memory channel).

## Configuration

| Env var | Default | Description |
| --- | --- | --- |
| `VIADUCT_REMOTE_RESOLVER_TYPES` | _empty_ | Comma-separated GraphQL type names to proxy. Empty means all node types. |

## Wiring it in

Build a `RemoteResolverInitializer` from a `RemoteResolverConfig`, call `initialize()` once at startup to get a `ProxyResolverFactory`, hand that factory to `BasicViaductFactory.create`, and call `close()` on the initializer at shutdown.

For a worked example with Micronaut beans, see [`rrp-server/.../ViaductConfiguration.kt`](rrp-server/src/main/kotlin/com/example/rrp/service/viaduct/ViaductConfiguration.kt).

If you route config through your own layer instead of process env vars, pass an `EnvLookup` to `RemoteResolverConfig.fromEnvironment(env)` — the default is `EnvLookup.SYSTEM`.

## Running rrp-server

`rrp-server` is a Micronaut + Viaduct application with the proxy wired in. It serves the StarWars schema and routes node resolution through the in-process proxy by default — no env var needed:

```bash
./gradlew :rrp-server:run
```

When the proxy comes up you'll see these lines in the log:

```
INFO  viaduct.remote.config.RemoteResolverInitializer - Remote resolver execution enabled for all types
INFO  viaduct.remote.config.RemoteResolverInitializer - Starting in-process gRPC server: viaduct-rrs-inprocess
INFO  viaduct.remote.config.RemoteResolverInitializer - Starting in-process gRPC server: viaduct-rrp-callback
INFO  viaduct.remote.config.RemoteResolverInitializer - Remote resolver execution initialized
```

Then issue a node query in another shell:

```bash
curl -X POST http://localhost:8080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query":"{ node(id:\"RmlsbTox\") { ... on Film { id title director } } }"}'
```

You'll get back:

```json
{"data":{"node":{"id":"RmlsbTox","title":"A New Hope","director":"George Lucas"}}}
```

## Limitations

- Only node resolvers are proxied; field resolvers fall through to local execution.
- Selective resolvers (`isSelective = true`) are rejected at construction — `RemoteNodeProxyExecutor` throws `IllegalArgumentException`.
- Wire format only handles JSON-friendly engine values. Engine-internal Kotlin types, custom scalars with bespoke coercers, and JSR-310 types without a configured `ObjectMapper` will fail at serialize time.
- Nested objects are reconstructed under a placeholder type — code that walks the result via `EngineObjectData.fetchOrNull` works at any depth, but code that inspects type identity does not.
- No authentication, retries, request deadlines, or metrics out of the box. Intended for trusted-network / sidecar / in-process deployments only.
