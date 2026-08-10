# Remote Resolvers (Experimental)

Run Viaduct node and field resolvers in a separate process from the engine, talking
to it over gRPC. This is useful when a tenant's resolvers are heavy or unreliable, or
when you want to deploy them on their own cadence — moving them out of the host
JVM gives you runtime isolation without changing how resolvers are written.

> **Status:** experimental. APIs may change without notice.

For implementation details, process boundaries, wire formats, and current callback
constraints, see [`impldocs/architecture.md`](impldocs/architecture.md).

## How it works

A small proxy on the engine side intercepts node and field resolution and forwards it
to a `RemoteResolverService` over gRPC. That service runs the resolver, and if the
resolver needs to fan back out (`ctx.query(...)`) it uses a callback service to
re-enter the main engine. The callback transport is wired, but re-entrant selection
sets are still referenced through a process-local handle; see the limitation below.
The main server dials a separate remote server process over a shaded Netty gRPC
channel; the remote server calls back through a plaintext server bound by the main server.

## Configuration

| Env var | Default | Side | Description |
| --- | --- | --- | --- |
| `VIADUCT_RRS_HOST` | `localhost` | main | Hostname the main server dials for the remote resolver service. |
| `VIADUCT_RRS_PORT` | `50051` | both | Remote server gRPC port — the main server dials it, the remote server binds it. |
| `VIADUCT_RRS_CALLBACK_HOST` | `localhost` | remote | Hostname the remote server reaches the main server callback at. |
| `VIADUCT_RRS_CALLBACK_PORT` | `50052` | both | Callback port — the main server binds it, the remote server dials it. |

The same `VIADUCT_RRS_PORT` / `VIADUCT_RRS_CALLBACK_PORT` values must be configured on
both the main server and the remote server process so they meet on the wire.

## Wiring it in

Build a `RemoteResolverInitializer` from a `RemoteResolverConfig` and an exact
`RemoteResolverSelection`, call
`initialize()` once at startup to get a `ProxyResolverFactory`, hand that
factory to `BasicViaductFactory.create`, and call `close()` on the initializer
at shutdown. If `RemoteResolverConfig.enabled` is false, `initialize()` returns
a no-op factory.
Viaduct uses the `viaduct_remote_resolver` AirParam to gate the rollout.

For a worked example with Micronaut beans, see
[`main-server/.../ViaductConfiguration.kt`](starwars/main-server/src/main/kotlin/com/example/main/service/viaduct/ViaductConfiguration.kt).

If you route config through your own layer instead of process env vars, pass
an `EnvLookup` to `RemoteResolverConfig.fromEnvironment(env)` — the default is
`EnvLookup.SYSTEM`.

## Running the demo

Run these commands from this directory (`core/x/remoteresolvers`).

Run the remote server and main server in separate terminals:

```bash
# Terminal A — remote server (runs the resolvers, binds gRPC on :50051)
./gradlew :remote-server:run

# Terminal B — main server (dials :50051, binds callback :50052)
./gradlew :main-server:run
```

When the proxy comes up, the main-server log includes:

```
INFO  viaduct.remote.config.RemoteResolverInitializer - Remote resolver execution selected by tenants [filmography, universe]: <node-count> node types, <field-count> fields
INFO  viaduct.remote.config.RemoteResolverInitializer - Connecting to remote RRS at localhost:50051
INFO  viaduct.remote.config.RemoteResolverInitializer - Starting callback server on port 50052
INFO  viaduct.remote.config.RemoteResolverInitializer - Remote resolver execution initialized
```

Issue a node query in another shell:

```bash
curl -X POST http://localhost:8080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query":"{ node(id:\"RmlsbTox\") { ... on Film { title director } } }"}'
```

The resolver runs in the remote server process and returns:

```json
{"data":{"node":{"title":"A New Hope","director":"George Lucas"}}}
```

The remote server builds its node and field resolvers from the tenant-module manifests on its classpath
(`META-INF/viaduct/modules/<pkg>.json`) via Viaduct's file-based bootstrapper — the
manifest entries carry the resolver wiring, so no SDL parsing is needed to construct
the executors (the schema, loaded from `.graphqls`, is used only to validate them).
Those manifests are generated at build time and bundled in each module jar; inspect
one (paths relative to the OSS root) with:

```bash
unzip -p demoapps/starwars/modules/filmography/build/libs/filmography.jar \
  META-INF/viaduct/modules/com.example.starwars.filmography.json | jq .
```

## Proxying field resolvers

The engine proxies the field coordinates in `RemoteResolverSelection`. It resolves each field's
required selection set on the main server and ships the resolved object/query values — and the
field's own sub-selection set — to the remote service, which runs the resolver and returns the value.
Every return kind round-trips over the remote service: scalars, `null`, node references
(`Character.species`, `Character.homeworld`), resolved objects, and lists of any of these.

A query spanning multiple field kinds resolves end-to-end through the remote service; scalars,
node references, and their sub-selections all cross the wire:

```bash
curl -X POST http://localhost:8080/graphql -H "Content-Type: application/json" \
  -d '{"query":"{ allCharacters(limit: 5) { name birthYear isAdult species { name } homeworld { name } } }"}'
```

## Limitations

- **Re-entrant selection execution is not yet cross-process:** `ctx.query()` and
  `ctx.mutation()` register their selection set in the remote JVM and send only that
  process-local handle to the main callback service. In-process integration tests share
  the registry and reach the callback, but a separate remote server gets
  `selections handle not registered` until the callback RPC carries a serialized
  selection set (or another cross-process representation).
- A proxied field result carries scalars,
  `null`, node references, resolved objects, and lists thereof; a field returning an arbitrary
  non-JSON, non-`EngineObjectData` value (or a `Map`/list-leaf containing one) is rejected at
  serialize time.
- Every proxied field is a gRPC hop (batched per field coordinate) — fields are finer-grained than
  nodes, so selecting all of them has a much larger call surface.
- Proxying the built-in `Query.node` / `Query.nodes` resolvers remotely requires the remote
  server to run with the *same* `GlobalIDCodec` as the caller: the remote decodes global ids with its
  own codec, so a custom codec on only one side mis-resolves the node type. With the default codec on
  both sides (as in the demo) they work as-is.
- Selective resolvers (`isSelective = true`) are not supported and remain local. Registry-backed
  selection excludes them while still proxying the tenant's non-selective resolvers.
- Wire format only handles JSON-friendly engine values. Custom scalars with bespoke coercers, and
  JSR-310 types without a configured `ObjectMapper`, will fail at serialize time; likewise a field's
  sub-selection set travels over the wire, so its variable values must be JSON-friendly.
- **Both sides must run compatible builds.** Every object carries its concrete GraphQL type name and
  the engine-value payload root carries a format version, so a mismatch on those payloads fails loudly
  rather than silently decoding into a wrong value — but there is no dual-read path, and argument /
  variable maps and the proto shape itself are not versioned. Deploy the main and remote servers
  together; with the per-tenant selection empty, nothing is proxied and the transport is inert.
- The two sides' schemas must agree on the types that cross the wire. An unknown type name is
  rejected, but a *changed* same-named type is not detected.
- Scalar fidelity is JSON's, not the JVM's: a `Long` comes back as an `Int` when it fits, a
  `BigDecimal` as a `Double`, and `NaN`/`Infinity` serialize but fail to parse. Tenant code that casts
  a remotely-resolved scalar to a specific numeric type can therefore behave differently than it does
  locally.
- A re-entrant `ctx.query()` result is serialized in one pass, so a per-field error stored in it (a
  denied access check, a failed nested field) aborts the whole callback response and is reported as
  `INTERNAL: Failed to serialize callback result`. The engine's partial-error semantics do not survive
  the hop, and the error is attributed to the codec rather than to the tenant.
- Selections dropped by `@skip`/`@include` do not survive the hop. The engine's own
  `EngineObjectData` distinguishes "excluded by a directive" (reads as null) from "never set" (throws
  `UnsetFieldException`); the wire format carries only the latter, so a directive-excluded selection
  reads as unset on the far side.
