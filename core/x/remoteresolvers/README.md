# Remote Resolvers (Experimental)

Run Viaduct node and field resolvers in a separate process from the engine, talking
to it over gRPC. This is useful when a tenant's resolvers are heavy or unreliable, or
when you want to deploy them on their own cadence — moving them out of the host
JVM gives you runtime isolation without changing how resolvers are written.

> **Status:** experimental. APIs may change without notice.

## How it works

A small proxy on the engine side intercepts node and field resolution and forwards it
to a `RemoteResolverService` over gRPC. That service runs the resolver, and if the
resolver needs to fan back out (`ctx.query(...)`) it does so through a callback
service over the same channel — so resolvers behave identically whether they
run locally or remotely.

The module ships with two transports:

- **`IN_PROCESS`** (default) — both ends in one JVM over gRPC's in-memory channel.
  Useful for development and testing the wiring without a separate process.
- **`NETWORK`** — the main server dials a separate remote server process over a
  (shaded) Netty gRPC channel; the remote server calls back to the main server
  through a plaintext server bound on the host.

## Configuration

| Env var | Default | Side | Description |
| --- | --- | --- | --- |
| `VIADUCT_REMOTE_RESOLVER_MODE` | `in_process` | main | `in_process` or `network` (case-insensitive). |
| `VIADUCT_REMOTE_RESOLVER_TYPES` | _empty_ | main | Comma-separated GraphQL type names whose node resolvers to proxy. Empty means all node types. |
| `VIADUCT_REMOTE_RESOLVER_FIELDS` | _empty_ | main | Comma-separated field coordinates (`Type.field`) whose field resolvers to proxy. Empty means **all** field resolvers except the engine's built-ins (`Query.node`/`nodes`, `@namespaceType`) and selective resolvers; a listed coordinate is proxied even if it's a built-in. Set to `none` (or `off`/`-`) to disable field proxying entirely while leaving node proxying on. |
| `VIADUCT_RRS_HOST` | `localhost` | main | Hostname the main server dials when `mode=network`. |
| `VIADUCT_RRS_PORT` | `50051` | both | Remote server gRPC port — the main server dials it, the remote server binds it. |
| `VIADUCT_RRS_CALLBACK_HOST` | `localhost` | remote | Hostname the remote server reaches the main server callback at. |
| `VIADUCT_RRS_CALLBACK_PORT` | `50052` | both | Callback port — the main server binds it, the remote server dials it. |

The same `VIADUCT_RRS_PORT` / `VIADUCT_RRS_CALLBACK_PORT` values must be configured on
both the main server and the remote server process so they meet on the wire.

## Wiring it in

Build a `RemoteResolverInitializer` from a `RemoteResolverConfig`, call
`initialize()` once at startup to get a `ProxyResolverFactory`, hand that
factory to `BasicViaductFactory.create`, and call `close()` on the initializer
at shutdown.

For a worked example with Micronaut beans, see
[`main-server/.../ViaductConfiguration.kt`](starwars/main-server/src/main/kotlin/com/example/main/service/viaduct/ViaductConfiguration.kt).

If you route config through your own layer instead of process env vars, pass
an `EnvLookup` to `RemoteResolverConfig.fromEnvironment(env)` — the default is
`EnvLookup.SYSTEM`.

## Running the demo

Run these commands from this directory (`core/x/remoteresolvers`).

### In-process (default)

`main-server` is a Micronaut + Viaduct application with the proxy wired in. It
serves the StarWars schema and routes node resolution through the in-process
proxy by default — no mode env var needed:

```bash
./gradlew :main-server:run
```

When the proxy comes up you'll see these lines in the log:

```
INFO  viaduct.remote.config.RemoteResolverInitializer - Remote resolver execution enabled for all node types (IN_PROCESS)
INFO  viaduct.remote.config.RemoteResolverInitializer - Remote resolver execution enabled for all field resolvers by default (IN_PROCESS); built-ins and selective resolvers excluded (set VIADUCT_REMOTE_RESOLVER_FIELDS to narrow or 'none' to disable)
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

### Network transport (two processes)

Run the remote server in one terminal and the main server (in network mode) in another:

```bash
# Terminal A — remote server (runs the resolvers, binds gRPC on :50051)
./gradlew :remote-server:run

# Terminal B — main server in network mode (dials :50051, binds callback :50052)
VIADUCT_REMOTE_RESOLVER_MODE=network ./gradlew :main-server:run
```

The same `curl` from the in-process walkthrough returns the same JSON — the
resolver ran in the remote server process and the result was serialized back over gRPC.

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

Like node resolvers, **every field resolver is proxied by default**. The engine resolves each field's
required selection set on the main server and ships the resolved object/query values — and the
field's own sub-selection set — to the remote service, which runs the resolver and returns the value.
Every return kind round-trips in either transport: scalars, `null`, node references
(`Character.species`, `Character.homeworld`), resolved objects, and lists of any of these.

To restrict proxying to specific fields, set `VIADUCT_REMOTE_RESOLVER_FIELDS` to a comma-separated
list of `Type.field` coordinates (the empty default proxies them all). For example, to run *only*
`Character.isAdult` remotely:

```bash
VIADUCT_REMOTE_RESOLVER_FIELDS=Character.isAdult ./gradlew :main-server:run
```

Either way, a query spanning multiple field kinds resolves end-to-end through the remote service — in
both `in_process` and `network` mode (scalars, node references, and their sub-selections all cross
the wire):

```bash
curl -X POST http://localhost:8080/graphql -H "Content-Type: application/json" \
  -d '{"query":"{ allCharacters(limit: 5) { name birthYear isAdult species { name } homeworld { name } } }"}'
```

`VIADUCT_REMOTE_RESOLVER_TYPES` (nodes) and `VIADUCT_REMOTE_RESOLVER_FIELDS` (fields) are
independent — each defaults to *all* and can be narrowed on its own.

## Limitations

- **Default-on (semantic note):** an empty `VIADUCT_REMOTE_RESOLVER_FIELDS` now means *all* field
  resolvers — it previously meant *none*. To turn field proxying off while keeping node proxying, set
  `VIADUCT_REMOTE_RESOLVER_FIELDS=none` (or `off`/`-`); to disable the whole feature (nodes too) use
  `VIADUCT_REMOTE_RESOLVER_ENABLED=false`. Or list only the coordinates you want.
- Node and field resolvers are both proxied by default. A proxied field result carries scalars,
  `null`, node references, resolved objects, and lists thereof; a field returning an arbitrary
  non-JSON, non-`EngineObjectData` value (or a `Map`/list-leaf containing one) is rejected at
  serialize time.
- Every proxied field is a gRPC hop (batched per field coordinate) — fields are finer-grained than
  nodes, so proxying *all* of them has a much larger call surface; narrow
  `VIADUCT_REMOTE_RESOLVER_FIELDS` for latency-sensitive paths. The engine's built-in resolvers
  (`Query.node`/`nodes`, `@namespaceType`) are excluded from the default for this reason — proxy
  them only by listing them explicitly.
- Proxying the built-in `Query.node` / `Query.nodes` resolvers over `network` requires the remote
  server to run with the *same* `GlobalIDCodec` as the caller: the remote decodes global ids with its
  own codec, so a custom codec on only one side mis-resolves the node type. With the default codec on
  both sides (as in the demo) they work as-is.
- Selective resolvers (`isSelective = true`) are never proxied — they run locally (the proxy factory
  skips them); constructing a proxy for one directly still throws.
- Wire format only handles JSON-friendly engine values. Custom scalars with bespoke coercers, and
  JSR-310 types without a configured `ObjectMapper`, will fail at serialize time; likewise a field's
  sub-selection set travels over the wire, so its variable values must be JSON-friendly.
- Nested objects are reconstructed under a placeholder type — code that walks
  the result via `EngineObjectData.fetchOrNull` works at any depth, but code
  that inspects type identity does not. A field's returned object (and its required-selection-set
  object/query values) is reconstructed against its real schema type (so typed accessors like
  `ctx.getObjectValue().getBirthYear()` work), but only at the top level; deeply nested objects fall
  back to the placeholder type.
