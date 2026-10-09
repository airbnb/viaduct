# Runtime2 Architectural Principles

## Purpose

This document states the durable principles that govern the Runtime2 model and its resolver algorithms. It defines how to interpret the model, which semantic boundaries must remain explicit, and what correctness means independently of any one execution strategy. [Research provenance](../evidence/research-provenance.md) preserves the evidence, hard cases, and discarded alternatives behind these conclusions; the [Runtime2 overview](../../README.md) defines the current alpha scope; and the [testing guide](../testing/guide.md) owns practical validation and investigation workflows.

## Kotlin Is The Executable Semantic Model

Runtime2 was designed to make formal reasoning and verification tractable. Its Kotlin model therefore uses data structures in a deliberately stylized manner, especially in the `viaduct.engine.runtime2.model` package. Model declarations denote sets, values, functions, relations, and partial operations. They do not imply JVM timing, allocation, caching, or effects unless the model explicitly represents those concepts. Similarly, a resolver relation may be suspending and selection-sensitive at its Kotlin boundary. Suspension is an execution capability, not part of the modeled mathematical signature. This design makes Kotlin an executable semantic definition; it does not mean that the Kotlin implementation as a whole has been formally verified.

Each reasoning exercise fixes one canonical `Assumptions`, lowered `ViaductSchema`, and resolver registry. Source GraphQL parsing, schema lowering, registry assembly, node adaptation, and provider-path compilation are composition that occurs before semantic reasoning. Semantic code trusts the carrier invariants established at that boundary.

`Assumptions` is the immutable reasoning world. Request-local bindings, cycle state, publication state, observers, and coroutine ownership belong to operation and task contexts, not to `Assumptions`. Code reaches dependencies through their semantic owner instead of copying them into forwarding scopes or convenience aliases.

A schema definition may retain a canonical opaque foreign attachment needed at an integration boundary. Such an attachment is not a model value: semantic logic does not inspect it or use it in equality, hashing, conformance, or schema relations. Source-backed objects retain exact definitions from the source GraphQL Java schema when the Engine API requires a source type witness; model-internal synthetic definitions carry generated witnesses only when an integration boundary requires them.

Compilation, examples, generated tests, stress campaigns, and cross-resolver agreement are finite consistency evidence. None is a mathematical proof, and agreement among implementations that share carriers, fixtures, generators, or oracles is not independent evidence.

## Keep Semantic Domains Distinct

Selections may contain open `ObjectEngineResult.Key` values. An `ObjectEngineResult.ObjectKey` has a concrete object field and is therefore eligible for an exact object-engine-result (OER) cell and result path even when its arguments contain instantiated variables. `ObjectEngineResult.GroundKey` is the refinement whose arguments have resolved; operations such as resolver invocation that require input values must cross that checked boundary explicitly. Engine-object-data selections are strings and never contain OER keys.

Construction demand describes the selections needed to construct an object result. Resolver input demand contributes one resolver's required selections to that construction. Successor demand describes the output requested from a producer to satisfy downstream needs. Client demand, symbolic or potential demand, supplied demand, resolver-owned output, internal selection forests, tenant-visible GraphQL fragments, and completed result coverage are related but not interchangeable representations.

Cross each boundary through an explicit checked operation. Do not make exact operations tolerate open values or reuse one demand representation merely because two values coincide in one example.

Semantic domains need not be nominal Kotlin hierarchies. Performance-sensitive domains may be represented by `Any` type aliases whose members are specified by documented conformance relations and checked at construction, publication, and conversion boundaries. Such an alias is a carrier representation for a mathematical domain, not a claim that the domain is untyped. Distinct aliases remain distinct mathematical domains even though Kotlin cannot use them as overload discriminators or prevent an arbitrary `Any` from crossing an unchecked programming boundary.

A semantic union is **equality-homogeneous** when every member has the same equality semantics and **equality-heterogeneous** when different members have different equality semantics. Whole-union equality is meaningful only for a homogeneous value-equality union; recursive structural equality of lists, maps, and other immutable composites counts as value equality. Equality on a heterogeneous union has no semantic meaning until an operation narrows its operands to a homogeneous subset with a documented equality relation. `EngineInputData` is homogeneous value-equality. `EngineOutputData` is heterogeneous because its simple-data subset has value equality while object and error members need not. `EngineResult` is likewise heterogeneous because scalar values, result occurrences, lists of cell occurrences, and errors have different equality semantics.

A **pre-domain type** supplies an unambiguous runtime representation that one or more semantic domains may admit. Ordinary Kotlin `Int`, finite `Double`, `Boolean`, and `String` values are pre-domain representations. Runtime2's result domain additionally uses `EngineIDResult` and canonical `ViaductSchema.EnumValue` values so GraphQL strings, IDs, and enum members remain distinguishable without wrapping every scalar in a nominal result type.

The Engine API currently overloads Kotlin `String` for GraphQL String, ID, and enum values in engine input and output data. Runtime2 preserves that representation in `EngineInputData` and `EngineOutputData` for compatibility, while `EngineResult` uses `String`, `EngineIDResult`, and `ViaductSchema.EnumValue` respectively. Crossings between output data and engine results therefore require schema-directed conversion. Keep this mismatch isolated in adapters so carrier evolution does not change result semantics.

Errors belong to domains rather than to every pre-domain type. Engine results, engine output data, and argument resolution use distinct error variants; `EngineInputData` has no error member. No error variant impersonates scalar, list, or object interfaces merely to create an artificial Kotlin union. `EngineErrorData` owns all metadata associated with an output error, including causal and attributional metadata. When one erroneous field causes another resolver error, the new error chains the source error while adding its own metadata. Crossings between output data and engine results must preserve that complete history.

## Result Occurrence Is Identity

The semantic identity of work is an occurrence in a rooted result tree. A resolver occurrence combines the reference identity of its operation-rooted OER with its exact path. The primary Query or Mutation operation result roots primary occurrences. Ordinary declared Query fragments use the associated Query OER owned by their containing orchestration, so equal exact keys in that scope share production while every owner retains its own projection. Nested `ctx.query()` and `ctx.mutation()` calls receive fresh roots of their respective kinds; root-field-reference targets receive fresh Query invocation roots.

Equal node IDs, schema coordinates, arguments, paths in different roots, or values do not merge separate object or list occurrences. List indices and concrete containing paths remain part of occurrence identity. Caching, batching, and request deduplication are separate physical execution layers and must not redefine semantic identity.

Cells are allocated by their containing OER or list-engine-result (LER). Cell reference identity is the cell occurrence identity; a parallel numeric cell identifier would duplicate and risk disagreeing with the carrier.

Symbolic object keys preserve variable-instance identity structurally in their arguments. Equal symbolic keys may occur in different containing OERs without collision because the OER occurrence supplies their concrete result-tree location.

## One-Shot Correctness Is Producer-Specific

For every resolver-bearing occurrence in scope, all producer-owned values later consumed from that occurrence must be covered by the demand supplied to its one selective resolver application.

A correct final union is weaker evidence. A late cache hit, widened result, or second materialization can make the completed OER look adequate even when the producing application discarded required output. A barrier that waits for all contributors currently known to exist is also weaker than proving that no later contributor can target the producer.

One-shot designs must therefore bound all contributors before application, conservatively include bounded alternatives, assign late demand a distinct occurrence, or reject the shape. Post-application widening cannot repair an under-supplied selective producer.

## Choose Ungrounded-Key Semantics Explicitly

Argument-bearing fields whose keys are not yet grounded admit three coherent policies:

1. Wait until every key that could coalesce has grounded. This preserves grounded one-shot identity but requires cycle exclusion strong enough to prevent dependency deadlock.
2. Speculatively widen successor demand for resolver instances that future grounded keys might join. This makes supplied demand and work timing-sensitive and is not an acceptable developer contract.
3. Coalesce by symbolic argument values and variable-instance identity before grounding. Distinct symbolic keys may later ground to equal arguments and invoke the same field resolver more than once.

Resolution uses the third policy. Symbolic identity is therefore a semantic identity, not an incomplete approximation of eventual grounded-key identity, and speculative widening is not a substitute for one-shot closure.

## Attribute Demand To Its Owner

Demand must be projected through the producer that owns the requested output. Traversal through passive fields remains within the current producer; traversal stops at resolver-bearing boundaries and attributes successor work to the successor resolver.

Source presence participates in ownership. An argumentless registered field supplied by an ancestor producer is passive for that occurrence; if the ancestor omits it, its standard resolver remains active. Argument-bearing fields remain active because their value is identified by arguments as well as by source position.

Resolver object and Query fragments determine input requirements. Resolver arguments identify an eventual resolver instance but do not choose the resolver template or its fixed fragments. This distinction allows symbolic closure to discover fixed input requirements before variable values are available.

## Keep Field Resolvers And Checkers Structurally Aligned

`FieldCheckerResolver` follows `FieldValueResolver` in representation, terminology, validation, and variable modeling wherever their semantics do not require a difference. Do not introduce checker-specific wrappers or lifecycle concepts for facts already modeled by field resolvers.

Resolver-owned identities use an explicit `ResolverTarget`, not a bare schema field. A field-value target and field-checker target for the same schema coordinate identify different resolver slots; variable templates and their instantiated IDs retain that distinction.

`ResolverFragmentTemplates` groups one object template, one Query template, and the variable definitions and optional provider shared by both. A field resolver owns one such pair. A checker owns a named map of pairs and receives both materialized values for each name. For resolution, both expose exactly one object fragment and one Query fragment; the checker combines its pairs independently per root after prefixing variables with the pair name. `ResolverFragment` contains only occurrence-specific resolution facts, while response-key-preserving materialization templates remain on the registry entry until input materialization begins. A resolver produces a field value and may consume output demand; a checker produces a checker result. Any other difference requires a concrete semantic justification.

A field-checker application belongs to one field-cell occurrence. A type-checker application belongs to one concrete OER occurrence and executes once in that OER's orchestration as a peer of its field work. Ordinary object-valued positions create distinct OER occurrences even when their values or identities agree; a parent backedge reuses its ancestor occurrence and therefore does not create another type-checker application. Field and type results remain separate during resolution and combine only when a checked consumer enforces access to the reached object.

## Progress Is Monotonic And Strict

Mutable semantic state is limited to documented monotonic stores. An OER or LER cell value, a field-cell occurrence's field-checker result, an OER occurrence's type-checker result, and a request-local variable binding move from absent to one immediate or deferred promise; a deferred promise completes once.

These restrictions make safety and progress obligations explicit: each value has an identifiable owner and terminal transition, and reasoning can distinguish a value that is pending from one that can no longer be produced.

A parent may publish a stable child OER before the child is complete. Later work fills absent child cells without replacing the parent or rebuilding the subtree.

Duplicate claims, duplicate writers, repeated lifecycle transitions, undeclared binding reads, and unclaimed cells at freeze time fail visibly. Idempotence must not hide duplicate scheduling or ownership errors.

## Runtime Variables Add Value-Flow Dependencies

`FromArgument` values are available from the defining resolver occurrence. `FromObjectField` and `FromQueryField` values require reading resolved provider paths and can reveal an exact consumer key only later. `FromProvider` values come from one occurrence-local provider invocation.

Provider paths are compiled and validated before semantic reasoning, but provider evaluation occurs at runtime in Resolution. Provider containment, inclusion, and branch ordering are domain restrictions; they are not themselves an execution algorithm.

Substitution precedes exact-key grouping in [Resolver01–23](../../impldocs/architecture/resolver-families.md#comparison-grid). Resolution retains symbolic keys: selections with the same field and variable instances coalesce, while keys containing different variable instances remain distinct even when those variables bind to equal values.

## Structured Concurrency Owns Request Lifetime

One request-root scope owns all request coroutines. Successful synchronous return means request quiescence. Explicit request cancellation propagates through structured ownership; an ordinary local tenant failure must instead be published at its owning result boundary without aborting the request or cancelling unrelated sibling work.

Resolution treats the request scope as a root-task capability, not as a general-purpose coroutine scope. Only orchestration-task, mutation-orchestration-task, field-resolver-task, field-checker-task, type-checker-task, and GraphQL mutation completion-waiter roots may launch directly on it. Object orchestration retains its own conditionally launched request-root coroutine whenever an OER has active work; an OER with no active work freezes synchronously. Mutation orchestration owns ordered namespace traversal, and the GraphQL waiter delays mutation response completion until resolution finishes. Query-fragment producers, provider readers, binding producers, materializers, and every other auxiliary coroutine are children of the nearest owning task. Nested execution uses a child scope owned by its invoking field task. Introducing another request-root task kind requires an explicit architecture change.

Ordinary cross-task readiness travels through named promises or value-bearing deferreds, not through another task's call stack or `Job` completion. Mutation sequencing explicitly waits for the preceding field-task scope and its payload completion before advancing, and the GraphQL mutation waiter awaits resolution before response completion. Independent ordinary object and list occurrences do not require a global barrier.

## Isolate Tenant Failure Without Stranding Work

Tenant work includes value resolvers, field and type checkers, and variables-provider callbacks. If tenant work hangs, the operation may hang. The operation may also wait for slow tenant work after another error makes its output unnecessary. Execution need not retract demand, cancel the producer, or detach request-owned work merely to return sooner.

Producing the best possible outcome in the presence of multiple errors is not a goal. Beyond specified error-propagation and access-enforcement rules, Runtime2 need not minimize failure latency, discover every error, choose an optimal error ordering, or salvage the largest possible partial result. An error or unfinished check is never permission to expose a protected value.

An ordinary tenant failure remains at its owned field, checker, or binding boundary and propagates through specified dependency and GraphQL completion rules. Aborting or cancelling the request is not a fallback for handling a local tenant error. GraphQL non-null propagation may still make response data null; that is distinct from an engine abort that discards unrelated work.

Permission to wait does not excuse engine-created deadlocks, missing writers, or promises stranded after their producer exits or is bypassed. Required terminal error publication and explicit cancellation cleanup remain obligations. Caller cancellation and externally imposed deadlines are separate controls, not substitutes for local error handling.

## Use Resolver Families To Protect The Architecture

Production Resolution is the end product, but maintaining Resolver01–23 is part of its architecture. The earlier families are executable refinement and comparison models: they make the same semantic roles visible in simpler algorithms and across different execution structures, isolating semantic capability from scheduling machinery before those concerns meet in production. Shared relationships deliberately constrain how Resolution decomposes and encapsulates its concerns.

A shared interface may enforce an architectural role without a current polymorphic consumer. `SharedFieldResolverTask` preserves the relationship between a field-resolver task and its publication occurrence for this reason. Judge such an abstraction by the architectural invariant it exposes as well as by its runtime consumers.

The resolver families also define the implementation ladder for new features: establish the compact semantics in Resolver01–03, expose explicit work in Resolver06–08, establish coroutine ownership in Resolver21–23, and only then complete the feature in production Resolution. [Resolver families and alignment](resolver-families.md) defines the maintained boundaries, comparison grid, naming policy, and exceptions to that progression.

Resolver10 remains a negative design lesson rather than a maintained family. Readiness rescanning, persistent late-demand acceptance, and complete-output retention add machinery that can conceal an incomplete producing application. Do not recreate that architecture to solve a local Resolution problem.

## Validate Independent Properties Independently

`correctResolution` judges the completed primary Query OER and each required associated Query-fragment result extensionally, validating every owner's occurrence-local projection separately. It does not establish resolver application count, supplied demand, binding correctness, execution order, lifecycle ownership, or concurrency.

Keep separate evidence for completed-result correctness, exact and occurrence-aware application identities, from-field bindings, lifecycle invariants, mutation tests, structural activation, and scheduling behavior. An expected-application oracle derived from the completed result under test is not fully independent and must be described accordingly.

Generated presence is weaker than runtime activation. Directed profiles must require the target interaction to execute, and broad campaigns must record enough information to replay one exact `S:R:Q` coordinate. Large green campaigns remain finite evidence and do not override a focused counterexample.

## Preserve The Engine API Boundary

[`viaduct.engine.api.Engine`](../../../api/src/main/kotlin/viaduct/engine/api/Engine.kt) is Runtime2's public execution contract. Runtime2 owns internal carriers such as `EngineResult`, typed keys, schema validation, occurrence-aware cells, and the demand and task structures needed by Resolution. Engine API types cross that boundary only where they express the same semantic fact.

`EngineObjectData.Sync` is the synchronous partial-object boundary and distinguishes an absent field from a present null value. Runtime2 supplies a validating implementation that preserves schema preconditions and instrumentation points. Do not erase occurrence identity, grounded-key validation, or model invariants merely to reduce source-level differences at the Engine API boundary.

Runtime2-owned engine object data retains canonical lowered `ViaductSchema.Object` identity for semantic reasoning, while source-backed data exposes the exact retained source `GraphQLObjectType` through the Engine API. Resolver inputs have source-schema field shape, and source resolver outputs are normalized into Runtime2-owned lowered values before semantic reasoning. Node-valued source output is normalized into canonical `Query.node` root-field references before semantic reasoning.

Engine object data is a policy-neutral value boundary. Reading a present erroneous selection exposes its `EngineErrorData`; the Tenant API layer decides how tenant code observes that error, including whether a generated accessor throws. Compatibility with an implementation that throws while reading an erroneous selection must not move Tenant API error policy into Runtime2's result model.

Alignment with the Engine API does not require preserving every external representation inside the result tree. In particular, Runtime2 distinguishes result-domain ID and enum values even though engine input and output data currently represent both as strings. Explicit adapters own that conversion so a carrier migration can remove it without changing Resolution semantics.

## Mutation Ordering

Mutation namespace demand is ordered and collected by response key. One orchestration traversal follows namespace edges depth first and preserves the order at each forest level. Distinct aliases identify distinct mutation applications and result cells. Each active mutation's field task and payload completion precede the next mutation; ordinary payload and independent Query work retain their existing concurrency. Mutation resolver input fragments are excluded because closing sibling demand would introduce effects outside this prescribed order. Every selected mutation executes regardless of earlier failures or nullability; explicit request cancellation still interrupts execution. Response completion begins after the mutation traversal finishes, so response null propagation cannot cancel remaining mutation effects.

Each nested `ctx.mutation()` call owns an independent ordered root and waits for its effects and payloads before returning. Explicitly concurrent calls may overlap; ordering is local to each call. Both nested Query and Mutation execution remain structured children of the invoking field task and share its cancellation.
