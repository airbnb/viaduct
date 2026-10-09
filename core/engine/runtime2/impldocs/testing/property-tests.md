# Property Testing

Runtime2 property testing combines valid generated GraphQL worlds, replayable test-input profiles, independent correctness and exactness judgments, and directed multi-round campaigns. The arbitrary package in the `support` source set generates schemas, resolver registries, and Query selections for property tests, benchmarks, and standalone campaigns. It is pre-reasoning infrastructure: generated recipes may use ordinary implementation state, but every emitted world crosses the same canonical schema, registry, lowering, and validation boundaries used by static fixtures.

These generators and correctness replays exercise Query resolution, not stateful mutation effects. Query selection permutations cannot establish mutation ordering or alias identity. Deterministic cross-family effect traces, payload assertions, and service-backed nested execution tests supply the [mutation evidence](resolution.md#graphql-mutation-and-nested-execution-tests).

## Composition

```kotlin
val counts = TestCaseCount(
    schemas = 20,
    registriesPerSchema = 3,
    queriesPerSchema = 5,
)
val config =
    Config.default +
        (ArgumentsEnabled to true) +
        (ResolverFragmentsEnabled to false)

checkResolverTestCases(counts, config) { testWorld, testCase ->
    // Run the resolver and judge the completed result.
}
```

`checkResolverTestCases` uses `S` as its outer Kotest iteration count. Each schema sample contains `R` independently generated registries and `Q` independently generated queries, and the runner evaluates their Cartesian product while reusing one canonical world per registry.

## Generated Worlds

`Arb.schema(config)` generates supported GraphQL SDL. `schema.registry(config)` chooses field-resolver coordinates and raw node resolvers, derives output paths and fixed object fragments, and produces deterministic resolver programs. `schema.query(config)` generates valid Query selections against the schema.

Resolver programs may be constant, input-sensitive, argument-sensitive, or sensitive to both. Structured outputs derive bounded occurrence-distinct values from canonical input and argument fingerprints, never from application order or mutable randomness.

Generated node implementations are fixture inputs. Composition retains the generated GraphQL-Java schema for source validation and derives a separate canonical lowered `ViaductSchema` in which Node-valued fields retain their source coordinates. Generated resolvers return source-shaped node references; fixture composition normalizes each one into a root-field reference targeting the built-in `Query.node`, whose encoded ID preserves the concrete object type and original resolver ID. Generated non-`Node` abstract types remain disjoint from node-resolved objects.

`SelectiveNodeResolversEnabled` makes those generated node resolvers selection-aware without changing their independent value relation. Each callback receives Resolution's node-owned demand and still materializes the complete deterministic `ObjectPlan`; model-owned nonselective projection then restricts that stable value to the supplied demand. The Resolution-only `selective-node` profile captures supplied-demand witnesses and requires an activated node resolver with nonempty demand, so it tests selective-node planning without treating fixture-specific selective output logic as the correctness oracle.

Resolver dependencies and variable provider/use branches are generated in one acyclic rank order and then validated by canonical registry assembly. Provider paths are inserted into the defining resolver's fixed object fragment before compilation.

`RootFieldReferencesEnabled` installs a fixed family for root-field-reference property testing instead of relying on random schema generation to discover its hard cases. The family has three nested namespace levels; targets at namespace depths two, three, and four; grounded arities zero, one, and four; fixed nested input objects; scalar, enum, object, interface, and union results; and result-object cycles of exactly two and four types. Target resolvers have empty object fragments, while directed Query fragments exercise ordinary namespace demand, `FromArgument`, and `FromQueryField`. Every concrete result object has a registered extension field so selections beneath a referenced object require successor and construction demand in the consumer OER. A fixed consumer returns direct references, a mixed list of a reference and passive value, a three-hop reference tail, and an omitted active-fallback field; its selected fields also have registered resolvers so source-supplied references exercise resolver override. When node resolvers are enabled, the family also installs a dedicated Node type whose node resolver returns a reference to a compatible non-Node interface target. Generated queries always select the ordinary consumer and the node consumer, including the node ID and resolver-backed extension. Reference-enabled stress profiles require runtime observation of both ordinary and node-resolver references.

`RootFieldReferenceWeight` independently decorates compatible ordinary resolver outputs and nested passive values with references into that fixed target family. A zero weight retains only the fixed references needed for deterministic coverage; positive weights make references occur in the middle of otherwise generated output trees. Engine-managed Node identity is not an ordinary decoration site.

Generated profiles for the [reference implementations](../architecture/resolver-families.md#comparison-grid) Resolver02/03, Resolver07/08, and Resolver22/23 exercise `FromArgument`, including paths through nullable input objects. Resolution profiles additionally execute `FromObjectField`, `FromQueryField`, and `FromProvider`. The isolated `FromProvider` profile generates one callback per owning resolver, returns every declared name together, and derives schema-compatible scalar or list values deterministically from the owning occurrence's grounded arguments. A separate query-fragment profile generates Query-rooted resolver inputs and is enabled only for resolver versions that implement them.

Resolver23's field-checker profiles materialize the generated resolver object and Query fragment plans as duplicate named checker pairs, add an empty pair, and add independently aliased object-only and Query-only `__typename` pairs that create checker-only demand without adding resolver dependencies. Generated checkers support `FromArgument`; coordinates using unsupported variable providers remain unchecked, and the mixed profile also deterministically leaves a subset of otherwise supported coordinates unchecked while alternating successful and denying checker outcomes. The checker mode is an execution-subject input rather than a serialized generator key, so ordinary resolver profiles remain checker-free. Resolution field-checker modes install the same paired checker shape with all four variable sources, including original response paths for from-field providers and deterministic argument-sensitive callback providers. Runtime profiles retain symbolic cells and require generated-versus-activated evidence for each variable source and symbolic checker keys; separate passive and root-reference profiles require those publication modes. Inclusion remains deterministic until arbitrary inclusion generation exists.

`GeneratedTypeCheckerMode` is a separate execution-profile input with `NONE`, `SUCCESS`, `DENIAL`, and `MIXED` modes plus corresponding production Resolution variants. Resolver23 type-checker profiles register grounded checkers on generated concrete non-Query types. Duplicate named raw object/Query pairs and an empty pair exercise projection. Object inputs select typename, up to two passive scalar fields, and up to two argumentless scalar value resolvers; Query inputs select typename and up to two argumentless scalar value resolvers. A candidate resolver is admitted only if every concrete type reached by its transitive object and Query input demand sorts before the checked type by name. This conservative type ordering extends the ordinary generated field dependency DAG, including composite selections with empty child forests, and avoids introducing type/value wait cycles. Resolver inputs restore checked semantics, so these worlds exercise nested field and type checks beyond the raw boundary. Mixed mode assigns success or denial by concrete type. The mode consumes no extra generator randomness and does not change serialized generator configuration. Deterministic contracts retain recursive, parent, reference, and lifecycle cases outside this bounded distribution.

Resolution's runtime type-checker modes additionally reuse up to two randomly generated resolver fragment/provider plans per concrete type, retargeting their variables to the type checker. Eligible plans define from-path and/or callback-provider variables (never field-argument variables) and satisfy the same conservative transitive type order. Selection favors rarer path-bearing plans before filling the two-plan budget with callback-only plans. The runtime profile samples two or three arguments per argument-bearing field and one variable per source attempt, leaving argument positions for path assignment after callback assignment. Their object and Query fragments retain sampled paths, arguments, aliases, nullability, and variable uses; duplicate named pairs retain separate variable instances. The production Resolution type profiles enable `FromObjectField`, `FromQueryField`, and `FromProvider` generation and mixed runtime field checks in these same worlds. Argument errors retain their normal generation weight. When materializing an error tuple erases variable uses, generated field and type checker pairs retain only definitions still used by their materialized fragments. Aggregate activation checks require type-owned completed bindings consumed on both input roots, both path-provider roots, callback-provider bindings consumed on both input roots, a nested provider path, and error-valued arguments reached in type-checker inputs. Callbacks reuse the sampled deterministic value plan with the type checker’s empty argument tuple; duplicate named pairs retain independent provider binding instances. Provider exceptions and inclusion/exclusion remain deterministic lifecycle/witness cases.

Queries and registries are independently generated from one schema. Query sources are bounded below GraphQL Java's parser limit, and oversized candidates are discarded before becoming test cases.

## Feature Controls

Configuration controls argument count and shape, resolver object and query fragments, variables by source, interfaces, unions, lists, node lowering, root-field references, selection depth, resolver density, and other size or weighting decisions. Argument-bearing fields may have multiple independently generated arguments. `ResolverVariableSingletonCoercionEnabled` lets list-target variables admit scalar and shallower-list providers through GraphQL singleton coercion, including nested list layers; it defaults off so resolver profiles opt in only after their implementation supports that grounding behavior. Resolution's generated and stress profiles enable it. Object- and Query-fragment shapes are generated independently through the same root-type-parameterized selection primitive, then variable assignment considers both fragments together so one binding can be consumed by multiple selections in either or both fragments. `ResolverFromProviderVariablesEnabled` independently admits callback-provided bindings; the isolated `from-provider` profile and every serialized Resolution broad-campaign profile enable it. `FromObjectField` provider paths are generated only in the object fragment, while `FromQueryField` provider paths are generated only in the Query fragment; the fragment consuming either variable does not change its source. `ResolverFromObjectFieldVariablesEnabled` and `ResolverFromQueryFieldVariablesEnabled` admit the respective sources, while both from-field sources share provider-path length, use-depth, passive-use, owner-use, provider-argument, and per-source owner-count controls. Both from-field sources can generate literal/symbolic convergence. `ResolverQueryFragmentsEnabled` admits ordinary Query-rooted resolver inputs, while `ResolverQueryFragmentWeight` independently bounds their density; enabling `FromQueryField` may add its required provider selection to an otherwise empty Query fragment. `ParentFieldsEnabled` adds the fixed great-grandparent spine used as a stable activation witness. Resolver value plans treat `@parent` like every other argumentless passive field, so parent-enabled profiles also exercise resolver-supplied parent values. `RandomParentFieldsEnabled` supplements that spine with two independently shaped parent chains of depth three or four, each rooted at an ordinary object below Query so no parent backedge targets the Query root, randomly choosing singular, list, and nested-list producers, nullable positions, concrete or union-valued parent targets, scalar siblings, and ordinary generated resolver fragments that may select those parent fields. Each random-parent object has at least one scalar field with two compatible argument positions so multiple variable sources can coexist in one generated resolver input. Its `value0` resolver receives a top-level parent requirement, an eligible lower-ranked ancestor resolver is selected beneath that requirement to compose deeper diagonals, and enabled variable generation directs two attempts across object and Query input locations without replacing the otherwise random fragments. When random parents and sometimes-passive generation are both enabled, each random-parent object also has an argumentless constant resolver with unused `parent { __typename }` input. Ancestor outputs may supply that active field, preserving value equivalence while forcing the resolver to speculate about its parent demand before learning that the standard invocation is unnecessary. Internal parent-target unions are not exposed through unrelated ordinary Query fields, so every parent-bearing occurrence has its validated producer ancestry. `SometimesPassiveFieldWeight` optionally lets generated resolver outputs supply argumentless fields that also have standard registered resolvers. It defaults to `0.0` and consumes no additional randomness at that value. The ordinary `sometimes-passive` profile, serialized broad-campaign profiles, and Resolution deep stress override it and require both generation and runtime activation evidence. Feature generation does not imply runtime activation; profiles that claim an interaction must record or require evidence that the relevant source-owned occurrence executed without its standard resolver application. Resolution's reference-enabled deep and broad stress profiles therefore fail unless references are both generated and observed at runtime; the focused profile additionally hard-requires every fixed interaction described above.

## Witnesses And Coverage

World construction only assembles deterministic model resolvers. To record execution, pass `registry.resolverObserver(...)` to the operation or contract resolution helper. The observer consumes the shared `onResolverInvocation` event before ordinary and reference-target calls, including calls that later fail or cancel. It retains invocation IDs and Query/reference evidence, and can append full fingerprint witnesses or lightweight application counts to the registry's diagnostic logs. Supplied-demand capture is opt-in; `clearResolutionWitness()` and `clearResolutionApplicationCounts()` control log lifetime. The raw selective-node demand log remains separate because it measures demand actually delivered to the generated node callback. Correctness replay does not emit shared invocation events.

Generated witnesses identify applications by canonical post-lowering field, exact arguments, materialized-input fingerprint, and, where required, result occurrence. Focused selective-demand profiles may capture supplied-demand detail; ordinary stress profiles avoid unnecessary witness cost. Coverage obligations distinguish generation from activation: registry or query metadata can prove that a feature was available, while runtime observations prove that the subject actually exercised it. A profile that promises an interaction must require both forms where incidental nonactivation could otherwise produce a misleading green run.

`BoundedRecorder<Entry>` stores Runtime2 observations. `Entry` is the record type: `ResolverApplicationRecord` for field calls, `ResolverOccurrenceApplicationRecord` for exact occurrences, or `SelectiveNodeResolverApplicationRecord` for node calls. Each recorder keeps duplicates, rejects writes beyond its limit, and returns a copy of its list. Record construction and witness assertions stay with the caller.

Snapshot or clear after resolution completes. `withoutRecording` excludes comparison runs and restores recording after exceptions; nested pauses are supported, but overlapping pause blocks on different threads are not. Field recording checks `isRecording` before constructing fingerprints and checks again when storing the record.

## Serialized Generator Profiles

`GeneratorConfigData` is a versioned data-class representation of a fully resolved `Config`, built only from primitive maps and range data. It records every supported key, including defaults, so a later default change cannot reinterpret existing data. Conversion back to `Config` rejects unsupported versions, missing or unknown keys, keys in the wrong type group, and values rejected by their `ConfigKey`.

The arbitrary package does not serialize this data or load resources. The launcher layer under `src/support/kotlin/viaduct/engine/runtime2/propertytest` owns JSON, resource indexes, campaign and round files, and resource loading. Generator profile resources live under `src/test/resources/viaduct/engine/runtime2/property-tests/generator-configs`; their explicit `index.json` gives directory and jar execution the same discovery behavior. Broad campaign resources live under `src/test/resources/viaduct/engine/runtime2/property-tests/campaigns`.

Each generator document is a complete `GeneratorConfigData` value rather than a delta over current defaults. Query-fragment admission and density, root-field-reference admission and insertion weight, and selection-aware node construction therefore remain independently reproducible. Every persisted production Resolution broad profile enables the fixed root-reference family, and the broad subject requires both generated and activated references.

## Runs, Rounds, And Campaigns

A property-test run has two independent profile inputs. `testInputProfileId` selects a versioned, fully resolved generator configuration; `subjectProfileId` selects executable behavior and oracles. The run also fixes the seed, `S:R:Q` dimensions, and required coverage-signature IDs. A `PropertyTestRoundConfigFile` gives the round a versioned ID and an ordered list of runs.

`PropertyTestCampaignConfigFile` is the compact serialized form for a complete campaign. It names one subject profile, declares its test-input profiles and coverage obligations, defines reusable phases, and assigns consecutive seed ranges to those phases. For round `n`, the range's `baseSeed` advances from its first round and each profile receives `(baseSeed + n - first) * seedMultiplier + seedOffset`. Expanding a campaign therefore produces the same ordinary round configuration consumed by the runner.

`PropertyTestRoundRunner` resolves both profile IDs and calls `executeResolverTestCases`, the generated-product entry point that does not inspect process properties. It owns one production Resolution dispatcher for the round and closes it after the selected runs. `resolution-broad-correctness` is the maintained standalone subject profile, and each run supplies its required structural signatures.

Campaign JUnit tests deserialize and expand the same campaign resources before calling the same runner. Their runtime-only `PropertyTestRoundExecution` may select one input profile and one `S:R:Q` coordinate. This preserves the JUnit replay interface without adding ephemeral selection state to serialized campaign or round data.

## Running Campaigns

Install the direct launcher from the repository root:

```shell
./gradlew :core:engine:runtime2:installPropertyTestRoundLauncher
```

Run one checked-in campaign round, or supply a standalone external round configuration:

```shell
core/engine/runtime2/build/install/property-test-round/bin/property-test-round \
  --campaign classpath:/viaduct/engine/runtime2/property-tests/campaigns/resolution-broad-campaign-v1.json \
  --round 1

core/engine/runtime2/build/install/property-test-round/bin/property-test-round \
  /absolute/path/to/custom-round.json
```

Use the campaign driver for all configured rounds or an explicit subset:

```shell
core/engine/runtime2/run-property-test-campaign.sh \
  classpath:/viaduct/engine/runtime2/property-tests/campaigns/resolution-broad-campaign-v1.json

core/engine/runtime2/run-property-test-campaign.sh \
  classpath:/viaduct/engine/runtime2/property-tests/campaigns/resolution-broad-campaign-v1.json \
  1 21 46 81
```

The driver changes to the Runtime2 project directory, asks the launcher for configured round numbers when none are supplied, installs the launcher once, lets Gradle exit, and invokes one fresh launcher JVM per round. The shell does not parse campaign JSON or assume a round range. Per-round and total timing begin after installation and therefore exclude Gradle configuration, task execution, and JUnit startup. Logs default to `core/engine/runtime2/build/reports/<campaign-id>` and can be redirected with `PROPERTY_TEST_CAMPAIGN_REPORT_DIR`.

## Maintaining Campaign Resources

Campaign JSON is authored directly. Generator profiles originate as typed Kotlin `Config` values in the production Resolution broad-stress profile definitions. After adding, removing, or renaming a `ConfigKey`, changing its wire semantics, or changing a typed generator profile, regenerate the complete resources from the repository root:

```shell
./gradlew :core:engine:runtime2:materializeGeneratorConfigs
```

Review and check in the resulting generator JSON with the schema or profile change, then run the affected campaign and inspect its exercised coverage. Adjust the typed profile and repeat as needed. Increment format versions deliberately when wire semantics change; never rely on a new default to reinterpret an existing serialized profile.

## Replay And Failure Reduction

Every semantic failure reports the profile, seed, one-based `S:R:Q` coordinate, schema, registry, and query. Replay the exact coordinate through `:core:engine:runtime2:resolverPropertyReplay` before changing generator or resolver code. [Testing strategy](strategy.md#generated-tests) defines the stable profile IDs and replay interface.

A persisted broad-campaign failure can be replayed through its recorded round, profile, and coordinate:

```shell
./gradlew :core:engine:runtime2:resolutionBroadStressCampaign \
  -PresolutionBroadStressCampaignRound=21 \
  -PresolutionBroadStressCampaignProfile=multiple-owners \
  -PresolverPropertyCase=18:4:1
```

Preserve the original `S:R:Q` dimensions when replaying a coordinate because registry and query counts affect generation before the selected case. Coordinate replay suppresses aggregate coverage obligations; use a complete profile replay when the failure is itself a missing aggregate signature.

After exact replay is stable, classify the failure as resolver, generator, oracle, campaign, or resource-envelope behavior. First reduce execution to the one recorded coordinate. Then remove irrelevant selections, registry entries, schema types, and enabled features while retaining the same violated assertion; when possible, preserve the result as a deterministic feature contract or focused regression. Do not replace an exact application, binding, or occurrence failure with a timeout-only reproduction. A generated world that contains a feature but never activates it is a coverage defect, not evidence about that feature.

## Validation

From the repository root, run generator and serialization tests with:

```shell
./gradlew :core:engine:runtime2:test \
  --tests 'viaduct.engine.runtime2.arbitrary.*' \
  --tests 'viaduct.engine.runtime2.propertytest.*'
```

Generator tests and ordinary resolver properties live in Runtime2's `test` source set and are included in `./gradlew :core:engine:runtime2:check`. Deep stress, broad campaigns, and standalone multi-round campaigns are opt-in and require explicit or serialized seeds.
