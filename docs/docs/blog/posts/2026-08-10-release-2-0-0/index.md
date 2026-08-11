---
date: 2026-08-10
categories:
  - Releases
description: Release notes for Viaduct 2.0.0.
---

# Release 2.0.0

See [GitHub Release](https://github.com/airbnb/viaduct/releases/tag/v2.0.0).

Viaduct 2.0.0 is a major release. The headline work is **named fragments and pre-validated GraphQL operations**, **tenant-local fields** supporting better encapsulation, **`@parent`** supporting access to fields of parent object, and a **settings-topology Gradle model** for multi-project builds (as well as on-going work on selective resolvers and the Java Tenant API).

Two changes affect every consumer's build regardless of what else they use: the published artifact set is now fat JARs only (no BOM) and Gradle project plugins now require explicit import of Viaduct modules. Start with Breaking Changes below.

<!-- more -->

## Breaking Changes

### Build and packaging — affects every consumer

- **Thin JARs and the BOM are no longer published; all consumers move to fat JARs** ([364f479b](https://github.com/airbnb/viaduct/commit/364f479b)) by @nmarsollier — `com.airbnb.viaduct:bom` and the 22 `core/*` thin-JAR coordinates no longer resolve.
- **The five `com.airbnb.viaduct.shared:*` thin JARs are no longer published** (`graphql`, `viaductschema`, `apiannotations`, `invariants`, `utils`) — they are bundled inside the fat JARs and plugin JARs instead; `service:api`'s POM was cleaned up ([4e59880f](https://github.com/airbnb/viaduct/commit/4e59880f)) by @nmarsollier — a build naming these fails at dependency resolution, not compilation.
- **Gradle project plugins now require a settings-declared topology** ([6aa74cad](https://github.com/airbnb/viaduct/commit/6aa74cad)) by @rstata — module plugins validate that the project is declared as a module in topology before wiring.
- **The project-level package DSL was removed** ([442c8816](https://github.com/airbnb/viaduct/commit/442c8816)) by @rstata
- **Schema extension correctness is now enforced, and `validateViaductSchemaExtensions` was added** ([a675d8d7](https://github.com/airbnb/viaduct/commit/a675d8d7)) by @gokhan-ozgozen — previously-tolerated schema extensions now fail the build.

### Service and tenant API

- **The Viaduct execution API was redesigned around Kotlin and Java idioms** ([0b6914f9](https://github.com/airbnb/viaduct/commit/0b6914f9)) by @geovannefduarte — `Viaduct.execute` / `executeAsync` signatures changed; `SchemaId.Full` was removed.
- **Tenant bootstrapper APIs were renamed** ([c0cdf7a9](https://github.com/airbnb/viaduct/commit/c0cdf7a9)) by @andimarek — `TenantAPIBootstrapper`, `TenantAPIBootstrapperBuilder`, `TenantModuleBootstrapper`, `SharedTenantModuleBootstrapper` and `NaiveTenantModuleBootstrapper` are all gone from the public surface.
- **Scanner-based tenant bootstrappers were removed** ([6145a783](https://github.com/airbnb/viaduct/commit/6145a783)) by @junjinp
- **The Java tenant API replaced classpath-scanning bootstrap with the file-based execution registry** ([74b8b7af](https://github.com/airbnb/viaduct/commit/74b8b7af)) by @catacraciun
- **`ViaductBuilder.withScopedSchemasFromSdl` replaces the public `withSchemaConfiguration`** ([14548217](https://github.com/airbnb/viaduct/commit/14548217)) by @geovannefduarte — `withSchemaConfiguration` and `withTenantModuleBootstrapper` were removed.
- **Schema scoping configuration is now explicit** ([8421bae3](https://github.com/airbnb/viaduct/commit/8421bae3)) by @andimarek — `SchemaScopeInfo` was removed.
- **The `Selections` annotation moved to the new `viaduct.api.documents` package** ([981db90b](https://github.com/airbnb/viaduct/commit/981db90b)) by @kristileka — `viaduct.api.select.Selections` no longer exists. Part of the 2.0 API package reorganization.
- **`GlobalIDCodec.deserialize` now has a named return type instead of `Pair`** ([e35fe079](https://github.com/airbnb/viaduct/commit/e35fe079)) by @geovannefduarte
- **`FlagManager` singleton objects were renamed to UpperCamelCase** ([13234296](https://github.com/airbnb/viaduct/commit/13234296)) by @geovannefduarte — `FlagManager.default` / `FlagManager.disabled` are now `Default` / `Disabled`.
- **Service factories and constants are exposed via `@JvmStatic`, and the service API was reshaped for Java callers** ([9ba69a36](https://github.com/airbnb/viaduct/commit/9ba69a36), [228fa352](https://github.com/airbnb/viaduct/commit/228fa352)) by @geovannefduarte — note for Java callers: the GraphiQL helper's JVM class is now `GraphiQLHtml`, not `GraphiQLHtmlKt`. The functions themselves are unchanged; Kotlin callers are unaffected.
- **`TemporaryBypassAccessCheck` SPI was retired in favour of a static engine config flag** ([834e009e](https://github.com/airbnb/viaduct/commit/834e009e)) by @amity177
- **`FlagManager.Flags.EXECUTE_ACCESS_CHECKS` was removed; access checks now run unconditionally** ([2fed1507](https://github.com/airbnb/viaduct/commit/2fed1507)) by @amity177

### Engine SPI — affects advanced integrators

- **`resolveSelectionSetSync` was renamed to `resolveSelectionSet`**, the backward-compatibility shim was deleted, and the async path plus `ProxyEngineObjectData` were removed ([108134ad](https://github.com/airbnb/viaduct/commit/108134ad), [c72ecde1](https://github.com/airbnb/viaduct/commit/c72ecde1), [42ace572](https://github.com/airbnb/viaduct/commit/42ace572)) by @vickeyyeh
- **`DataFetchingEnvironment` was removed from node instrumentation params** ([b991d9e4](https://github.com/airbnb/viaduct/commit/b991d9e4)) by @jbellenger
- **`instrumentFetchSelection` and `shouldInstrumentFetchSelections` were removed** ([d8bd388d](https://github.com/airbnb/viaduct/commit/d8bd388d)) by @vickeyyeh
- **`objectValue` / `queryValue` were removed from `FieldResolverExecutor.Selector`** ([b6a4e36c](https://github.com/airbnb/viaduct/commit/b6a4e36c)) by @vickeyyeh
- **Selective OER keys were removed** ([6e9e0b8e](https://github.com/airbnb/viaduct/commit/6e9e0b8e)) by @jbellenger

### Experimental — `x/remoteresolvers`, not covered by the API stability contract

- Explicit resolver selection is now required ([10d44e93](https://github.com/airbnb/viaduct/commit/10d44e93)) by @njlynch
- Remote-resolver enablement is now explicit ([87741205](https://github.com/airbnb/viaduct/commit/87741205)) by @junjinp
- The in-process remote resolver transport was removed ([236a5efc](https://github.com/airbnb/viaduct/commit/236a5efc)) by @cetinsahin

---

## Features

### Named fragments and pre-validated GraphQL operations

- Tenant-facing API surface for named GraphQL fragments and pre-validated operations — first phase of the Named Fragments RFC ([ff51065e](https://github.com/airbnb/viaduct/commit/ff51065e)) by @kristileka
- `@GraphQLOperation` strings are executable at the tenant boundary with no engine change ([f66a3c23](https://github.com/airbnb/viaduct/commit/f66a3c23)) by @kristileka
- Every declared `@GraphQLOperation` is schema-validated at assembly time, so an invalid operation fails the build instead of failing at runtime ([1cdeb38b](https://github.com/airbnb/viaduct/commit/1cdeb38b)) by @kristileka
- KSP codegen plumbing for `@GraphQLOperation` (`QueryFromAnnotation` / `MutationFromAnnotation`) ([82b76bc2](https://github.com/airbnb/viaduct/commit/82b76bc2)) by @kristileka
- Propagation and validation layer for `@GraphQLFragment` ([f4b00368](https://github.com/airbnb/viaduct/commit/f4b00368)) by @kristileka
- Two assembly-time validations for `@GraphQLFragment`, closing a gap where an invalid fragment could reach runtime undetected ([9978d5c2](https://github.com/airbnb/viaduct/commit/9978d5c2)) by @kristileka
- End-to-end contract tests for `@GraphQLOperation` ([1362350a](https://github.com/airbnb/viaduct/commit/1362350a)) by @kristileka
- `ctx.query`/`ctx.mutation` taking a selection string are now deprecated — they accept a string that can be invalid and unsafe. The overloads taking an `OperationFromAnnotation` are the new stable API ([aaa3dc7c](https://github.com/airbnb/viaduct/commit/aaa3dc7c)) by @kristileka
- `@GraphQLOperation` docs, Star Wars demoapp examples and example-tenant examples ([27b719d6](https://github.com/airbnb/viaduct/commit/27b719d6)) by @kristileka
- A type-safe public way for tenants to check whether an input or argument field was explicitly provided rather than left unset ([c8cf50ef](https://github.com/airbnb/viaduct/commit/c8cf50ef)) by @kristileka
- `@oneOf` (exactly one field set) is enforced at the builder level in both the Java and Kotlin tenant APIs ([022cdccd](https://github.com/airbnb/viaduct/commit/022cdccd)) by @catacraciun
- Two new Viaduct tenant tutorials, continuing the series after tutorial 13 ([887dd86c](https://github.com/airbnb/viaduct/commit/887dd86c)) by @kristileka

### Tenant-local fields, `@parent` fields and schema scoping

- Tenant-local base schema support ([0e074c46](https://github.com/airbnb/viaduct/commit/0e074c46)) by @andimarek
- Schema-backed `@parent` fields in resolver and checker required selection sets ([574d19af](https://github.com/airbnb/viaduct/commit/574d19af)) by @andimarek
- Build-time validation for schema-backed `@parent` fields ([b75aa2d6](https://github.com/airbnb/viaduct/commit/b75aa2d6)) by @andimarek
- Enforce tenant-local RSS ownership ([7ecddce6](https://github.com/airbnb/viaduct/commit/7ecddce6)) by @andimarek
- `SchemaScoping` data class and DSL accumulation surface ([#367](https://github.com/airbnb/viaduct/pull/367)) by @183565386+xyu307
- Validate the `viaductApplication` schema-scoping DSL ([#393](https://github.com/airbnb/viaduct/pull/393)) by @183565386+xyu307
- `NodeInterfaceIdConsistencyRule` for schema validation ([369c21ff](https://github.com/airbnb/viaduct/commit/369c21ff)) by @gokhan-ozgozen
- Require resolvers for argument fields ([fbbef961](https://github.com/airbnb/viaduct/commit/fbbef961)) by @andimarek
- Make base schema registration explicit ([c7af237b](https://github.com/airbnb/viaduct/commit/c7af237b)) by @andimarek

### Gradle settings topology and build plugins

- Settings topology plugin ([7c174a5f](https://github.com/airbnb/viaduct/commit/7c174a5f)) by @rstata
- Wire projects through topology ([9a1499a0](https://github.com/airbnb/viaduct/commit/9a1499a0)) by @rstata
- Derive module packages from topology ([04797a96](https://github.com/airbnb/viaduct/commit/04797a96)) by @rstata
- Viaduct modules bucket ([c526268e](https://github.com/airbnb/viaduct/commit/c526268e)) by @rstata
- Module Viaduct application bucket ([b8606d27](https://github.com/airbnb/viaduct/commit/b8606d27)) by @rstata
- Support application projects below the Gradle root ([#383](https://github.com/airbnb/viaduct/pull/383)) by @rstata
- Allow modern resolver generation to use a custom Kotlin package ([8646fcdc](https://github.com/airbnb/viaduct/commit/8646fcdc)) by @gummybug

### Selective resolvers

Background: [discussion #399](https://github.com/airbnb/viaduct/discussions/399). Gated behind `ENABLE_MAT_RESOLUTION` and expected to have no production impact until enabled.

- Selective field resolvers ([cfdcf463](https://github.com/airbnb/viaduct/commit/cfdcf463)) by @jbellenger
- Selective node resolvers ([63e78019](https://github.com/airbnb/viaduct/commit/63e78019)) by @jbellenger
- An SPI for field selectivity, integrated into how selective fields are discovered at runtime ([ebdd3be0](https://github.com/airbnb/viaduct/commit/ebdd3be0)) by @jbellenger
- `or` combinator for `FieldSelectivityProvider` ([46df1ab7](https://github.com/airbnb/viaduct/commit/46df1ab7)) by @alexanderuv
- Materialization layer interfaces, including the mergeable `KeyTree` selection-set representation ([9581ebb7](https://github.com/airbnb/viaduct/commit/9581ebb7)) by @jbellenger
- `MatLedger` implementation, tracking which parts of a `KeyTree` have been materialized ([7f867555](https://github.com/airbnb/viaduct/commit/7f867555)) by @viaduct-maintainers
- QueryPlan-to-KeyTree projection and filtering ([9b2939ac](https://github.com/airbnb/viaduct/commit/9b2939ac)) by @jbellenger
- `ExecutionSelectionSet`, an `EngineSelectionSet` backed directly by the executing QueryPlan ([4f199fe6](https://github.com/airbnb/viaduct/commit/4f199fe6)) by @jbellenger
- Preserve child-plan execution context and keep selection traversal within its concrete parent scope — two correctness fixes needed for selective resolvers ([1aa154bd](https://github.com/airbnb/viaduct/commit/1aa154bd)) by @jbellenger
- Configurable batching and determinism ([89446479](https://github.com/airbnb/viaduct/commit/89446479)) by @jbellenger
- Normalized child plans ([b9a45eea](https://github.com/airbnb/viaduct/commit/b9a45eea)) by @jbellenger
- Even lazier field-type child plans ([36de3259](https://github.com/airbnb/viaduct/commit/36de3259)) by @jbellenger

### Java tenant API

- Connections and pagination for the Java tenant API ([e6be8ad0](https://github.com/airbnb/viaduct/commit/e6be8ad0)) by @catacraciun
- Named fragments and typed GraphQL operations in the Java tenant API ([06b86521](https://github.com/airbnb/viaduct/commit/06b86521)) by @catacraciun
- Root field references in the Java tenant API ([b60f3806](https://github.com/airbnb/viaduct/commit/b60f3806)) by @catacraciun
- Type-safe Java input-field presence checks ([f7799fac](https://github.com/airbnb/viaduct/commit/f7799fac)) by @catacraciun
- Complete Java scalar type mappings ([7dd836c5](https://github.com/airbnb/viaduct/commit/7dd836c5)) by @catacraciun
- Generate Java GRT reflection metadata ([54902b46](https://github.com/airbnb/viaduct/commit/54902b46)) by @catacraciun
- Typed `GlobalID<T>` support in Java GRT codegen for `@idOf`-annotated fields ([9cfdb138](https://github.com/airbnb/viaduct/commit/9cfdb138)) by @catacraciun
- `--applied_scopes` support in the Java GRT/resolver codegen ([ff3f7401](https://github.com/airbnb/viaduct/commit/ff3f7401)) by @catacraciun
- Thread an immutable `InternalContext` through Java tenant-API GRT constructors, mirroring Kotlin ([91dd8175](https://github.com/airbnb/viaduct/commit/91dd8175)) by @catacraciun
- `TenantModuleInjectorFactory` is implementable from Java ([252a4cce](https://github.com/airbnb/viaduct/commit/252a4cce)) by @geovannefduarte
- Extract `JavaEngineContextDelegate` to de-duplicate the Java runtime contexts ([5b2c7d0d](https://github.com/airbnb/viaduct/commit/5b2c7d0d)) by @catacraciun
- Share Tenant API input-value normalization via a new tenant-runtime-support module ([f4a2faea](https://github.com/airbnb/viaduct/commit/f4a2faea)) by @catacraciun
- Share Viaduct runtime variable-decoding helpers across the Java and Kotlin tenant APIs ([413bc959](https://github.com/airbnb/viaduct/commit/413bc959)) by @catacraciun
- Converge the Java Viaduct runtime onto the shared variable-decoding helpers ([972bb802](https://github.com/airbnb/viaduct/commit/972bb802)) by @catacraciun
- Shared language-neutral `SchemaAnalysis` in `shared/codegen`; Java codegen delegates to it ([6fccb839](https://github.com/airbnb/viaduct/commit/6fccb839)) by @catacraciun
- Kotlin codegen schema predicates delegate to shared `SchemaAnalysis` ([7d52b98b](https://github.com/airbnb/viaduct/commit/7d52b98b)) by @catacraciun
- Centralize resolver class and arguments-type naming in `SchemaAnalysis` ([d5ea7ef3](https://github.com/airbnb/viaduct/commit/d5ea7ef3)) by @catacraciun
- Golden-output characterization tests for Java and Kotlin GRT/resolver codegen ([1c4eb8e9](https://github.com/airbnb/viaduct/commit/1c4eb8e9)) by @catacraciun
- Extend Java tenant API contract-test coverage across error handling, field resolvers, subqueries, mutations, variables and default node resolvers ([f07b7289](https://github.com/airbnb/viaduct/commit/f07b7289)) by @catacraciun
- Unify the Kotlin and Java feature-app contract test plugins into one bilingual plugin ([906dd534](https://github.com/airbnb/viaduct/commit/906dd534)) by @catacraciun

### File-based bootstrapping and hotswap

- File-based bootstrapping, part 1: new `ModuleConfigSource` abstraction ([601d8fc8](https://github.com/airbnb/viaduct/commit/601d8fc8)) by @kristileka
- File-based bootstrapping, part 2: orchestration and compatibility ([68c74265](https://github.com/airbnb/viaduct/commit/68c74265)) by @kristileka
- File-based bootstrapping, part 3: classic and shims file-based refactor ([536b2db5](https://github.com/airbnb/viaduct/commit/536b2db5)) by @kristileka
- Bootstrap scanner tenants with registry config ([47e385a1](https://github.com/airbnb/viaduct/commit/47e385a1)) by @pclowes
- Filesystem execution registry sources for code hotswap ([ea988a1b](https://github.com/airbnb/viaduct/commit/ea988a1b)) by @njlynch
- Tenant-scoped bytecode for local schema hotswap ([78a1ca81](https://github.com/airbnb/viaduct/commit/78a1ca81)) by @njlynch
- Named stream-backed executor registry configs ([40677f20](https://github.com/airbnb/viaduct/commit/40677f20)) by @rstata
- `EngineTestModule` for the `runFeatureTest` execution-registry path ([38fb7569](https://github.com/airbnb/viaduct/commit/38fb7569)) by @rstata

### Remote resolvers (experimental)

- Remote resolvers in NETWORK mode, with `rrs-server` for remote resolver execution ([d4c78e50](https://github.com/airbnb/viaduct/commit/d4c78e50)) by @cetinsahin
- Field-resolver remote-execution substrate: RPC, serializer, registry, executor ([0e73564a](https://github.com/airbnb/viaduct/commit/0e73564a)) by @cetinsahin
- Opt-in remote field-resolver execution via `VIADUCT_REMOTE_RESOLVER_FIELDS` ([95850160](https://github.com/airbnb/viaduct/commit/95850160)) by @cetinsahin
- Remote field resolvers are proxied by default, like node resolvers ([a8eb4194](https://github.com/airbnb/viaduct/commit/a8eb4194)) by @cetinsahin
- Remote field resolvers can return objects, node references and lists (opt-in) ([6af855f1](https://github.com/airbnb/viaduct/commit/6af855f1)) by @cetinsahin
- Remote field resolvers with composite and node-reference returns work in network mode ([0c6eabce](https://github.com/airbnb/viaduct/commit/0c6eabce)) by @cetinsahin
- Bootstrap selected Viaduct tenant resolver code in the remote resolver server ([29ec9a13](https://github.com/airbnb/viaduct/commit/29ec9a13)) by @junjinp
- Host-defined request context carrier ([5833354d](https://github.com/airbnb/viaduct/commit/5833354d)) by @cetinsahin
- Bidirectional-streaming proto schema ([f4b9e1df](https://github.com/airbnb/viaduct/commit/f4b9e1df)) by @vickeyyeh
- VS-side streaming wireframe ([e1ada684](https://github.com/airbnb/viaduct/commit/e1ada684)) by @vickeyyeh
- RRS-side streaming wireframe ([8324dbd3](https://github.com/airbnb/viaduct/commit/8324dbd3)) by @vickeyyeh
- Real node resolution over the streaming transport ([147a7fff](https://github.com/airbnb/viaduct/commit/147a7fff)) by @vickeyyeh
- Real node resolution in the VS-side streaming proxy ([e4c7e9ee](https://github.com/airbnb/viaduct/commit/e4c7e9ee)) by @vickeyyeh

### Property-based testing

- `Arb.viaduct` ([bd3a5519](https://github.com/airbnb/viaduct/commit/bd3a5519)) by @jbellenger
- `CheckedArb` ([c8e151d0](https://github.com/airbnb/viaduct/commit/c8e151d0)) by @jbellenger
- Deep selection set coverage ([ba6b1b27](https://github.com/airbnb/viaduct/commit/ba6b1b27)) by @jbellenger
- Deep arb suite ([541c98d2](https://github.com/airbnb/viaduct/commit/541c98d2)) by @jbellenger

### Engine, instrumentation and tooling

- Synchronous `EngineObjectData` presence checks ([ec71118e](https://github.com/airbnb/viaduct/commit/ec71118e)) by @fireboy1919
- Context-keyed node batch resolver results ([dc357ac7](https://github.com/airbnb/viaduct/commit/dc357ac7)) by @alexanderuv
- `beginFetchSelection` callback SPI for fetch-selection instrumentation ([22e65fab](https://github.com/airbnb/viaduct/commit/22e65fab)) by @vickeyyeh
- Fire fetch-selection instrumentation for `ctx.query()` / `ctx.mutation()` subqueries ([09765120](https://github.com/airbnb/viaduct/commit/09765120)) by @vickeyyeh
- QueryPlan cache metrics ([861d855b](https://github.com/airbnb/viaduct/commit/861d855b)) by @jbellenger
- `Viaduct.dump` ([f6dc3a56](https://github.com/airbnb/viaduct/commit/f6dc3a56)) by @jbellenger
- `RootFieldRefStub` testing API for stubbing `ctx.rootFieldRef` ([2b16a9cc](https://github.com/airbnb/viaduct/commit/2b16a9cc)) by @alexanderuv
- Public API stability contract ([6c6a0e29](https://github.com/airbnb/viaduct/commit/6c6a0e29)) by @gokhan-ozgozen
- Semantic binary schema diff tooling ([d1bb0fd6](https://github.com/airbnb/viaduct/commit/d1bb0fd6)) by @njlynch
- Migrate available experience IDs to modern resolver ([d23b4e6b](https://github.com/airbnb/viaduct/commit/d23b4e6b)) by @andimarek
- Retire the tenant `featuretests` framework, migrating coverage to the engine and contract test suites ([4fc0f6fe](https://github.com/airbnb/viaduct/commit/4fc0f6fe)) by @kristileka
- Argument, mutation and introspection test coverage in the starters ([#370](https://github.com/airbnb/viaduct/pull/370)) by @jtuchscherer
- `demoappsStandaloneTest` orchestration task ([78c55b6e](https://github.com/airbnb/viaduct/commit/78c55b6e)) by @gokhan-ozgozen
- Advisory CVE-parity security scanning: convention plugin and comparison script ([926e6c86](https://github.com/airbnb/viaduct/commit/926e6c86), [598653c2](https://github.com/airbnb/viaduct/commit/598653c2)) by @geovannefduarte
- `InvalidDependencyRule` ktlint rule for banned catalog aliases ([7aad631e](https://github.com/airbnb/viaduct/commit/7aad631e)) by @nmarsollier
- Contract tests extending `KotlinFeatureAppTestContractBase` were silently skipped when the KSP registry wasn't on the classpath; the missing registry is now a failure, not "not applicable" ([64f931ab](https://github.com/airbnb/viaduct/commit/64f931ab)) by @kristileka

---

## Bug Fixes

### Schema validation and scoping

- Safely hide `BackingData` and `@parent` fields from client schemas ([cdd6458b](https://github.com/airbnb/viaduct/commit/cdd6458b)) by @andimarek
- Validate `BackingData` and `@parent` fields as tenant-local-equivalent ([14e16ae3](https://github.com/airbnb/viaduct/commit/14e16ae3)) by @andimarek
- Enforce tenant-local field constraints across Viaduct ([a87a3bf6](https://github.com/airbnb/viaduct/commit/a87a3bf6)) by @andimarek
- Validate tenant-local resolver selections in Gradle builds ([4db2a266](https://github.com/airbnb/viaduct/commit/4db2a266)) by @rstata
- Reject `@parent` fields declared on interfaces during schema validation ([a0d3acd4](https://github.com/airbnb/viaduct/commit/a0d3acd4)) by @andimarek
- Reject resolver directives on interface fields ([9b6060d1](https://github.com/airbnb/viaduct/commit/9b6060d1)) by @andimarek
- Reject object and interface scopes without fields ([80005ff7](https://github.com/airbnb/viaduct/commit/80005ff7)) by @andimarek
- Validate Viaduct scope directives in a single OSS rule ([74ab70c4](https://github.com/airbnb/viaduct/commit/74ab70c4)) by @andimarek
- Clarify namespace directive conflict diagnostics ([472861a5](https://github.com/airbnb/viaduct/commit/472861a5)) by @amity177
- Reject empty tenant module names ([3d37eb2e](https://github.com/airbnb/viaduct/commit/3d37eb2e)) by @rstata
- Use a distinct schema ID for the internal full-schema document provider ([635dacac](https://github.com/airbnb/viaduct/commit/635dacac)) by @andimarek
- Define `PageInfo` in schemabase, not a module partition ([63a3442f](https://github.com/airbnb/viaduct/commit/63a3442f)) by @geovannefduarte
- Declare schema scopes in the ktor and micronaut starters ([0ec1ed95](https://github.com/airbnb/viaduct/commit/0ec1ed95)) by @geovannefduarte

### Execution and engine correctness

- `ctx.query()` was leaking cell-level resolver errors instead of storing them per field ([13734093](https://github.com/airbnb/viaduct/commit/13734093)) by @vickeyyeh
- Allow selection-set merge across interface and implementor parent types ([e89c0aec](https://github.com/airbnb/viaduct/commit/e89c0aec)) by @vickeyyeh
- Forward `CompletionException`-wrapped cancellations as cancellations ([e7d4c3c3](https://github.com/airbnb/viaduct/commit/e7d4c3c3)) by @skevy
- Reject unresolved `RootFieldReference` values instead of hanging during serialization ([c91ccfcd](https://github.com/airbnb/viaduct/commit/c91ccfcd)) by @vickeyyeh
- Serialize namespaced mutation fields ([e6da5342](https://github.com/airbnb/viaduct/commit/e6da5342)) by @andimarek
- Reject object fragments on mutation namespaces ([99f98396](https://github.com/airbnb/viaduct/commit/99f98396)) by @andimarek
- Implement `valueToLiteral` for `Long` scalars ([6fd6c03f](https://github.com/airbnb/viaduct/commit/6fd6c03f)) by @jbellenger
- Preserve type constraints for widened selections ([e071e113](https://github.com/airbnb/viaduct/commit/e071e113)) by @jbellenger
- Fix conditional dropped fragments ([839896db](https://github.com/airbnb/viaduct/commit/839896db)) by @jbellenger
- Fix the type checker variable RSS target ([1533c522](https://github.com/airbnb/viaduct/commit/1533c522)) by @jbellenger
- Pass directive context for resolver reads ([c0124646](https://github.com/airbnb/viaduct/commit/c0124646)) by @bhavanapallempati
- Reject selective resolvers on mutation fields ([afc730ba](https://github.com/airbnb/viaduct/commit/afc730ba)) by @alexanderuv
- Selective field resolver fixes ([454e1dc8](https://github.com/airbnb/viaduct/commit/454e1dc8)) by @jbellenger
- Reduce selective resolver weight ([bfa9e591](https://github.com/airbnb/viaduct/commit/bfa9e591)) by @jbellenger
- Improve deterministic value generation ([a7fff90b](https://github.com/airbnb/viaduct/commit/a7fff90b)) by @jbellenger
- Miscellaneous arb fixes ([4df163d3](https://github.com/airbnb/viaduct/commit/4df163d3)) by @jbellenger

### Build plugins, bootstrap and codegen

- Make `application-gradle-plugin` order-independent ([e6864bb1](https://github.com/airbnb/viaduct/commit/e6864bb1)) by @gokhan-ozgozen
- Fix orphaned `TenantModuleBootstrapper` in the file-based bootstrap path ([49173510](https://github.com/airbnb/viaduct/commit/49173510)) by @pclowes
- Prefer hotswap-aware Viaduct bootstrappers over generated registry resources ([55f7a4d6](https://github.com/airbnb/viaduct/commit/55f7a4d6)) by @njlynch
- Make `kspKotlin` sensitive to resolver-bases output and tenant package inputs ([52e07a14](https://github.com/airbnb/viaduct/commit/52e07a14)) by @gokhan-ozgozen
- Emit JVM binary names from KSP to fix nested class loading ([f10d85c5](https://github.com/airbnb/viaduct/commit/f10d85c5)) by @gokhan-ozgozen
- Emit LF newlines in generated source on all platforms ([afb6e119](https://github.com/airbnb/viaduct/commit/afb6e119)) by @geovannefduarte
- Validate direct module deps from topology paths ([e1fa23d0](https://github.com/airbnb/viaduct/commit/e1fa23d0)) by @rstata
- Consolidate schema resource scanning on ClassGraph ([f0ebd617](https://github.com/airbnb/viaduct/commit/f0ebd617)) by @fireboy1919
- Restore Micronaut standalone demo compatibility ([baf503f3](https://github.com/airbnb/viaduct/commit/baf503f3)) by @rstata
- Instantiate non-bean field resolvers in the main-server demo injector ([2bfaa316](https://github.com/airbnb/viaduct/commit/2bfaa316)) by @cetinsahin
- Remove stale `storageKey` shorthand and an undefined `gradlew run` instruction ([dd0171ae](https://github.com/airbnb/viaduct/commit/dd0171ae)) by @fireboy1919
- Resolve Kotlin compiler warnings ([ab81045c](https://github.com/airbnb/viaduct/commit/ab81045c)) by @geovannefduarte
- Restore `GraphQLSchemaParserTest` as Java with AssertJ ([7aee122a](https://github.com/airbnb/viaduct/commit/7aee122a)) by @nmarsollier
- Downgrade `foojay-resolver-convention` to 0.8.0 for Java 11 compatibility ([abea5938](https://github.com/airbnb/viaduct/commit/abea5938)) by @fireboy1919

---

## Performance Improvements

- Tune the query plan cache default size ([58525211](https://github.com/airbnb/viaduct/commit/58525211)) by @jbellenger

---

## Documentation

- Document tenant-local fields and executable schema views ([98c94fcb](https://github.com/airbnb/viaduct/commit/98c94fcb)) by @andimarek
- Document Viaduct named fragments (`@GraphQLFragment`) with runnable Star Wars examples ([14151e9e](https://github.com/airbnb/viaduct/commit/14151e9e)) by @kristileka
- Document `ctx.rootFieldRef()` in the OSS resolver docs ([e06fe1ce](https://github.com/airbnb/viaduct/commit/e06fe1ce)) by @gummybug
- Document namespace types ([8f1476a3](https://github.com/airbnb/viaduct/commit/8f1476a3)) by @gummybug
- Clarify global ID best practices and add tenant API design principles ([c1732e97](https://github.com/airbnb/viaduct/commit/c1732e97)) by @rstata
- Clarify JSON resolver value conventions ([9b0a92ae](https://github.com/airbnb/viaduct/commit/9b0a92ae)) by @rstata
- Refresh Viaduct service-engineering documentation ([60e0cd90](https://github.com/airbnb/viaduct/commit/60e0cd90)) by @rstata
- Document the remote resolver architecture ([1d91f125](https://github.com/airbnb/viaduct/commit/1d91f125)) by @cetinsahin
- Document KSP pipeline incrementality and stale-output cleanup ([520ea770](https://github.com/airbnb/viaduct/commit/520ea770)) by @nmarsollier
- Add comments describing the dedup logic in `DispatcherRegistryFactory` ([dbe5b346](https://github.com/airbnb/viaduct/commit/dbe5b346)) by @junjinp
- Clarify execution origins and child QueryPlan targets ([da582ba3](https://github.com/airbnb/viaduct/commit/da582ba3)) by @andimarek
- Make standalone Maven-local the primary demoapp test path ([7e801666](https://github.com/airbnb/viaduct/commit/7e801666)) by @gokhan-ozgozen
- Note that the demoapps composite is not a design objective ([958b9830](https://github.com/airbnb/viaduct/commit/958b9830)) by @gokhan-ozgozen
- Indent subsections in doc navigation ([#356](https://github.com/airbnb/viaduct/pull/356)) by @ryantanner
- Remove a stray `{.mt-5}` attribute from the roadmap page ([15973824](https://github.com/airbnb/viaduct/commit/15973824)) by @gokhan-ozgozen

---

## Testing

- Java coverage harness for the service API ([e1b0d743](https://github.com/airbnb/viaduct/commit/e1b0d743)) by @geovannefduarte
- `gradletestapps` end-to-end coverage ([9ae3d7e5](https://github.com/airbnb/viaduct/commit/9ae3d7e5)) by @rstata
- Extend `StandardViaduct.Builder` to take developer-controlled sources for bootstrap config data ([d2c304f4](https://github.com/airbnb/viaduct/commit/d2c304f4)) by @rstata
- Exercise `installDist` in micronaut-starter ([#390](https://github.com/airbnb/viaduct/pull/390)) by @geovannefduarte
- Migrate feature app contract tests to file-based bootstrap ([725873a4](https://github.com/airbnb/viaduct/commit/725873a4)) by @gokhan-ozgozen
- Drop `@singleton` from schema root-detection test fixtures ([daf0e2c0](https://github.com/airbnb/viaduct/commit/daf0e2c0)) by @amity177
- Replace em-dashes in Kotlin test method names with hyphens ([37994791](https://github.com/airbnb/viaduct/commit/37994791)) by @viaduct-maintainers

---

## Refactoring

- Invoke resolvers through generated base contracts ([de3843c9](https://github.com/airbnb/viaduct/commit/de3843c9)) by @alexanderuv
- Generate base resolver contracts ([c1df79fe](https://github.com/airbnb/viaduct/commit/c1df79fe)) by @alexanderuv
- Retype `EngineObjectDataBuilder.build()` to `EngineObjectData.Sync` ([4db5a2a8](https://github.com/airbnb/viaduct/commit/4db5a2a8)) by @vickeyyeh
- Retype the checker chain `objectDataMap` to `EngineObjectData.Sync` ([ebef7648](https://github.com/airbnb/viaduct/commit/ebef7648)) by @vickeyyeh
- Introduce `EngineObjectDataMaterializer` to decouple materialization from instrumentation ([9c761f76](https://github.com/airbnb/viaduct/commit/9c761f76)) by @vickeyyeh
- Move checker RSS materialization under the access-check instrumentation boundary ([29e0efcd](https://github.com/airbnb/viaduct/commit/29e0efcd)) by @vickeyyeh
- Remove coroutine-context coupling from `SyncEngineObjectDataFactory` instrumentation ([c7848d9c](https://github.com/airbnb/viaduct/commit/c7848d9c)) by @vickeyyeh
- Replace `ProxyEngineObjectData`/`CheckerProxyEngineObjectData` construction sites with `SyncEngineObjectDataFactory` ([c86b32f9](https://github.com/airbnb/viaduct/commit/c86b32f9)) by @vickeyyeh
- Use sync object data for variable resolution ([338439f5](https://github.com/airbnb/viaduct/commit/338439f5)) by @bhavanapallempati
- Resolve re-entrant callback queries via `resolveSelectionSetSync` ([aa543707](https://github.com/airbnb/viaduct/commit/aa543707)) by @vickeyyeh
- Resolve root field references via `resolveSelectionSetSync` ([46f9ee2e](https://github.com/airbnb/viaduct/commit/46f9ee2e)) by @vickeyyeh
- Migrate `RemoteEngineExecutionContext` to `resolveSelectionSetSync` ([b527ed4d](https://github.com/airbnb/viaduct/commit/b527ed4d)) by @vickeyyeh
- Migrate the query/mutation bridge to `resolveSelectionSetSync`; delete `materializeToSync` ([5b1688d4](https://github.com/airbnb/viaduct/commit/5b1688d4)) by @vickeyyeh
- Align instrumentation nullability to match graphql-java nullability ([aec2f004](https://github.com/airbnb/viaduct/commit/aec2f004)) by @jbellenger
- Move helper methods to `FieldExecutionHelpers` ([66561233](https://github.com/airbnb/viaduct/commit/66561233)) by @jbellenger
- Build `QueryPlanIndex` with `QueryPlan` ([3f3c9bda](https://github.com/airbnb/viaduct/commit/3f3c9bda)) by @jbellenger
- Prepare execution parameters object ancestry ([abab2c47](https://github.com/airbnb/viaduct/commit/abab2c47)) by @andimarek
- Stop re-reading the module registry file during executor bootstrap ([c5ee255f](https://github.com/airbnb/viaduct/commit/c5ee255f)) by @geovannefduarte
- Decouple `grtPackagePrefix` from the engine bootstrap layer ([dc00b1a0](https://github.com/airbnb/viaduct/commit/dc00b1a0)) by @gokhan-ozgozen
- Rename `ExecutionRegistry` data model classes with a `Config` suffix ([263df595](https://github.com/airbnb/viaduct/commit/263df595)) by @gokhan-ozgozen
- Complete per-tenant DI via Micronaut ([59055646](https://github.com/airbnb/viaduct/commit/59055646)) by @gokhan-ozgozen
- Replace `com.google.inject.Inject`/`Singleton` with `javax.inject` equivalents ([1b6579a3](https://github.com/airbnb/viaduct/commit/1b6579a3)) by @fireboy1919
- Make the remote-resolvers library include-only and its demo self-contained ([d76a5c30](https://github.com/airbnb/viaduct/commit/d76a5c30)) by @cetinsahin
- `rrs-server` builds node executors from the tenant manifest via the file-based bootstrapper ([d9d4901c](https://github.com/airbnb/viaduct/commit/d9d4901c)) by @cetinsahin
- Rename the remote-resolver demo servers (`rrp-server` → `main-server`, `rrs-server` → `remote-server`) and group them under `x/remoteresolvers/starwars/` ([e618be20](https://github.com/airbnb/viaduct/commit/e618be20)) by @cetinsahin
- Inject the greeting message via `@Value` in the micronaut-starter demoapp ([5b82f2a0](https://github.com/airbnb/viaduct/commit/5b82f2a0)) by @nmarsollier
- Remove `viaduct.apiannotations` usages from non-BCV-api modules ([4a71d950](https://github.com/airbnb/viaduct/commit/4a71d950)) by @nmarsollier
- Migrate test assertions off assertj-core, strikt-core, kotlin-test and guava-testlib ([a41b57d5](https://github.com/airbnb/viaduct/commit/a41b57d5)) by @nmarsollier
- Make the Java codegen golden test tolerant of line endings ([38efe37c](https://github.com/airbnb/viaduct/commit/38efe37c)) by @geovannefduarte
- Remove dead `grtPackageName` configuration from `ViaductApplicationExtension` ([345bc7b9](https://github.com/airbnb/viaduct/commit/345bc7b9)) by @nmarsollier
- Remove root logging binding dependencies ([8cacdb70](https://github.com/airbnb/viaduct/commit/8cacdb70)) by @rstata
- Use `kotlinx-coroutines-core` consistently ([0364e732](https://github.com/airbnb/viaduct/commit/0364e732)) by @rstata
- Remove redundant repository declarations from settings files ([937afeac](https://github.com/airbnb/viaduct/commit/937afeac)) by @rstata
- Remove a stale README ([e235442a](https://github.com/airbnb/viaduct/commit/e235442a)) by @rstata

---

## Build System

- Upgrade language and API targets to 1.9 ([367894bb](https://github.com/airbnb/viaduct/commit/367894bb)) by @viaduct-maintainers
- Wire `check` to depend on `demoappsStandaloneTest` ([1d6b0363](https://github.com/airbnb/viaduct/commit/1d6b0363)) by @gokhan-ozgozen
- Enable Kotlin `-Werror` for Viaduct OSS, the Gradle core build, build-logic and `x/remoteresolvers`, then consolidate it into the Kotlin conventions ([d7c59431](https://github.com/airbnb/viaduct/commit/d7c59431), [14f4186d](https://github.com/airbnb/viaduct/commit/14f4186d), [91ca83ba](https://github.com/airbnb/viaduct/commit/91ca83ba), [e830ddaa](https://github.com/airbnb/viaduct/commit/e830ddaa), [703312c7](https://github.com/airbnb/viaduct/commit/703312c7)) by @geovannefduarte
- Make ktlint, detekt and checkstyle fail the build rather than warn ([c8ce81aa](https://github.com/airbnb/viaduct/commit/c8ce81aa), [0ab4ce8d](https://github.com/airbnb/viaduct/commit/0ab4ce8d), [292cfeb6](https://github.com/airbnb/viaduct/commit/292cfeb6)) by @geovannefduarte
- Enforce detekt in build-logic ([31364539](https://github.com/airbnb/viaduct/commit/31364539)) by @geovannefduarte
- Drop redundant `ignoreFailures` and `remoteresolvers` `-Werror` settings ([ae352d31](https://github.com/airbnb/viaduct/commit/ae352d31)) by @geovannefduarte
- Remove the SpotBugs plugin and annotations in favour of ErrorProne for Java and detekt for Kotlin ([80074353](https://github.com/airbnb/viaduct/commit/80074353)) by @nmarsollier
- Consolidate Jackson deps with a bundle alias in the Gradle catalog ([933645d5](https://github.com/airbnb/viaduct/commit/933645d5)) by @nmarsollier
- Preserve Viaduct OSS fat-JAR metadata for binary CVE scanners ([c3152d85](https://github.com/airbnb/viaduct/commit/c3152d85)) by @geovannefduarte
- Retry flaky tests on CI via the Gradle test-retry plugin ([#384](https://github.com/airbnb/viaduct/pull/384)) by @geovannefduarte
- Remove demoapps from the root composite build ([7cd40930](https://github.com/airbnb/viaduct/commit/7cd40930)) by @gokhan-ozgozen
- Avoid duplicate jar filenames in composite demoapp builds ([50e4f64c](https://github.com/airbnb/viaduct/commit/50e4f64c)) by @geovannefduarte
- Resolve demoapp Viaduct deps from a fresh mavenLocal ([fa453725](https://github.com/airbnb/viaduct/commit/fa453725)) by @geovannefduarte
- Suppress a deprecation warning in the `Arb.viaduct` generator ([a8ea52bd](https://github.com/airbnb/viaduct/commit/a8ea52bd)) by @geovannefduarte
- Add detekt lint rules preventing `com.google.inject.Inject`/`Singleton` ([7664b1a5](https://github.com/airbnb/viaduct/commit/7664b1a5)) by @fireboy1919
- Pin the OSS docs djlint below 1.39 ([b60e0b4f](https://github.com/airbnb/viaduct/commit/b60e0b4f)) by @andimarek

---

## Continuous Integration

- Compile and test in one job to stop flaky cache handoff ([#400](https://github.com/airbnb/viaduct/pull/400)) by @geovannefduarte
- Advisory CVE-parity security-scan workflow ([d9bf5b18](https://github.com/airbnb/viaduct/commit/d9bf5b18)) by @geovannefduarte
- Surface CycloneDX SBOMs in the security-scan job summary ([#382](https://github.com/airbnb/viaduct/pull/382)) by @geovannefduarte
- Surface the security-scan report in the job summary for fork PRs ([#388](https://github.com/airbnb/viaduct/pull/388)) by @geovannefduarte
- Windows Gradle check jobs, with Windows-specific compatibility fixes ([#368](https://github.com/airbnb/viaduct/pull/368)) by @gokhan.ozgozen
- Pre-publication SBOM inspection gate ([#389](https://github.com/airbnb/viaduct/pull/389)) by @geovannefduarte
- Verify all published demo apps post-publication ([#391](https://github.com/airbnb/viaduct/pull/391)) by @geovannefduarte
- Stop polling unpublished artifacts in the release workflow ([a23a8ddf](https://github.com/airbnb/viaduct/commit/a23a8ddf)) by @geovannefduarte
- Make the demoapp pipeline re-run-safe and reap stale tmp branches ([#387](https://github.com/airbnb/viaduct/pull/387)) by @geovannefduarte
- Skip demoapp cleanup when the tmp branch is already deleted ([#386](https://github.com/airbnb/viaduct/pull/386)) by @geovannefduarte
- Skip coverage-summary when coverage-reports is cancelled ([7f2ba535](https://github.com/airbnb/viaduct/commit/7f2ba535)) by @fireboy1919
- Replace `jacoco-report-aggregation` with per-module exec data and a hierarchical HTML report ([91edb8da](https://github.com/airbnb/viaduct/commit/91edb8da)) by @fireboy1919
- Route CI and demoapp Gradle resolution through the configured Artifactory mirror ([794a2915](https://github.com/airbnb/viaduct/commit/794a2915), [2d7f5bd6](https://github.com/airbnb/viaduct/commit/2d7f5bd6), [e91e45f1](https://github.com/airbnb/viaduct/commit/e91e45f1), [94259b53](https://github.com/airbnb/viaduct/commit/94259b53), [a1f5ff94](https://github.com/airbnb/viaduct/commit/a1f5ff94), [60c778f0](https://github.com/airbnb/viaduct/commit/60c778f0)) by @geovannefduarte, @andimarek, @vickeyyeh, @gokhan-ozgozen, @fireboy1919

---

## Chores

- Restore `@VisibleForTest` annotations that were selectively reverted ([647dad73](https://github.com/airbnb/viaduct/commit/647dad73)) by @nmarsollier
- Migrate to OWNERS files ([83f9a783](https://github.com/airbnb/viaduct/commit/83f9a783)) by @viaduct-maintainers
