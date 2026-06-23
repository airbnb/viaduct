# Remote Resolvers (Experimental)

Run Viaduct node resolvers in a separate process from the engine, talking to it
over gRPC. This is useful when a tenant's resolvers are heavy or unreliable, or
when you want to deploy them on their own cadence — moving them out of the host
JVM gives you runtime isolation without changing how resolvers are written.

> **Status:** experimental. APIs may change without notice.

## How it works

A small proxy on the engine side intercepts node resolution and forwards it to
a `RemoteResolverService` over gRPC. That service runs the resolver, and if the
resolver needs to fan back out (`ctx.query(...)`) it does so through a callback
service over the same channel — so resolvers behave identically whether they
run locally or remotely.

The module ships with two transports:

- **`IN_PROCESS`** (default) — both ends in one JVM over gRPC's in-memory channel.
  Useful for development and testing the wiring without a separate process.
- **`NETWORK`** — RRP dials a separate RRS process over a (shaded) Netty gRPC
  channel; RRS calls back to RRP through a plaintext server bound on the host.

## Configuration

| Env var | Default | Side | Description |
| --- | --- | --- | --- |
| `VIADUCT_REMOTE_RESOLVER_MODE` | `in_process` | proxy | `in_process` or `network` (case-insensitive). |
| `VIADUCT_REMOTE_RESOLVER_TYPES` | _empty_ | proxy | Comma-separated GraphQL type names to proxy. Empty means all node types. |
| `VIADUCT_RRS_HOST` | `localhost` | proxy | Hostname the proxy dials when `mode=network`. |
| `VIADUCT_RRS_PORT` | `50051` | both | RRS gRPC port — proxy dials it, RRS binds it. |
| `VIADUCT_RRS_CALLBACK_HOST` | `localhost` | rrs | Hostname the RRS reaches the proxy callback at. |
| `VIADUCT_RRS_CALLBACK_PORT` | `50052` | both | Callback port — proxy binds it, RRS dials it. |

The same `VIADUCT_RRS_PORT` / `VIADUCT_RRS_CALLBACK_PORT` values must be configured on
both the proxy and the RRS process so they meet on the wire.

## Wiring it in

Build a `RemoteResolverInitializer` from a `RemoteResolverConfig`, call
`initialize()` once at startup to get a `ProxyResolverFactory`, hand that
factory to `BasicViaductFactory.create`, and call `close()` on the initializer
at shutdown.

For a worked example with Micronaut beans, see
[`rrp-server/.../ViaductConfiguration.kt`](rrp-server/src/main/kotlin/com/example/rrp/service/viaduct/ViaductConfiguration.kt).

If you route config through your own layer instead of process env vars, pass
an `EnvLookup` to `RemoteResolverConfig.fromEnvironment(env)` — the default is
`EnvLookup.SYSTEM`.

## Running rrp-server (in-process)

`rrp-server` is a Micronaut + Viaduct application with the proxy wired in. It
serves the StarWars schema and routes node resolution through the in-process
proxy by default — no env var needed:

```bash
./gradlew :remoteresolvers:rrp-server:run
```

When the proxy comes up you'll see these lines in the log:

```
INFO  viaduct.remote.config.RemoteResolverInitializer - Remote resolver execution enabled for all types (IN_PROCESS)
INFO  viaduct.remote.config.RemoteResolverInitializer - Starting in-process gRPC server: viaduct-rrs-inprocess
INFO  viaduct.remote.config.RemoteResolverInitializer - Starting in-process gRPC server: viaduct-rrp-callback
INFO  viaduct.remote.config.RemoteResolverInitializer - Remote resolver execution initialized (IN_PROCESS)
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

## Running rrp-server + rrs-server (network)

Run the RRS in one terminal and rrp-server in another:

```bash
# Terminal A
./gradlew :remoteresolvers:rrs-server:run

# Terminal B
VIADUCT_REMOTE_RESOLVER_MODE=network ./gradlew :remoteresolvers:rrp-server:run
```

The same `curl` from the in-process walkthrough returns the same JSON — the
resolver ran in the RRS process and the result was serialized back over gRPC.

The RRS builds its node resolvers from the tenant-module manifests on its classpath
(`META-INF/viaduct/modules/<pkg>.json`) via Viaduct's file-based bootstrapper — the
manifest entries carry the resolver wiring, so no SDL parsing is needed to construct
the executors (the schema, loaded from `.graphqls`, is used only to validate them).
Those manifests are generated at build time and bundled in each module jar; inspect
one with:

```bash
unzip -p demoapps/starwars/modules/filmography/build/libs/filmography.jar \
  META-INF/viaduct/modules/com.example.starwars.filmography.json | jq .
```

## Limitations

- Only node resolvers are proxied; field resolvers fall through to local execution.
- Selective resolvers (`isSelective = true`) are rejected at construction.
- Wire format only handles JSON-friendly engine values. Engine-internal Kotlin
  types, custom scalars with bespoke coercers, and JSR-310 types without a
  configured `ObjectMapper` will fail at serialize time.
- Nested objects are reconstructed under a placeholder type — code that walks
  the result via `EngineObjectData.fetchOrNull` works at any depth, but code
  that inspects type identity does not.
