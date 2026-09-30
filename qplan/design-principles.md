# Qplan Design Principles

## Purpose

This document records durable principles for the qplan model and its resolver algorithms. The evidence, known hard cases, and source provenance behind them live in [`research-evidence.md`](./research-evidence.md). Current implementation state and scope boundaries belong in [`handoff.md`](./handoff.md), practical workflows belong in [`maintainer-guide.md`](./maintainer-guide.md), and completed chronology belongs in Git history.

## A Compiling Mathematical Model

Compiling Kotlin is qplan's primary specification language. Model declarations denote sets, values, functions, relations, and partial operations; they do not imply JVM timing, allocation, caching, or effects unless the model explicitly represents those concepts.

Each reasoning exercise fixes one canonical `Assumptions`, lowered `ViaductSchema`, and resolver registry. Source GraphQL parsing, schema lowering, registry assembly, node adaptation, and provider-path compilation are pre-reasoning composition. Semantic code trusts the carrier invariants established at that boundary.

A schema definition may retain a canonical opaque foreign attachment needed at an integration boundary. Such an attachment is not a model value: semantic logic does not inspect it or use it in equality, hashing, conformance, or schema relations. Qplan source-backed objects retain the exact definitions from the unchanged source GraphQL-Java schema for Viaduct Engine API type witnesses; model-internal synthetic definitions retain generated witnesses only when an integration boundary requires them.

Compilation, examples, generated tests, stress campaigns, and cross-resolver agreement are finite consistency evidence. They are not mathematical proof. The TLA+ baseline proves only its explicitly stated finite calculus and assumptions.

## Keep Semantic Domains Distinct

Selections may contain open `ObjectEngineResult.Key` values. An `ObjectEngineResult.ObjectKey` has a concrete object field and is therefore eligible for exact OER cells and result paths even when its arguments contain instantiated variables. `ObjectEngineResult.GroundKey` is the refinement whose arguments have resolved; operations such as resolver invocation that require input values must cross that checked boundary explicitly. EOD selections are strings and never contain OER keys.

Construction demand describes the selections needed to construct an object result; resolver input demand contributes an individual resolver's required selections to that construction. Successor demand describes the output requested from a producer to satisfy downstream needs. Client demand, symbolic or potential demand, and supplied demand likewise serve distinct purposes. Resolver-owned output, internal selection forests, tenant-visible GraphQL fragments, and completed result coverage are likewise related but not interchangeable representations.

Cross each boundary through an explicit checked operation. Do not make exact operations tolerate open values or reuse one demand representation merely because two values happen to coincide in one example.

Semantic domains need not be nominal Kotlin hierarchies. Performance-sensitive domains may be represented by `Any` typealiases whose members are specified by documented conformance relations and checked at construction, publication, and conversion boundaries. Distinct aliases remain distinct mathematical domains even though Kotlin cannot use them as overload discriminators or prevent an arbitrary `Any` from crossing an unchecked programming boundary.

A semantic union is **equality-homogeneous** when every member has the same equality semantics and **equality-heterogeneous** when different members have different equality semantics. Whole-union equality is useful only for a homogeneous value-equality union; recursive structural equality of lists, maps, and other immutable composites counts as value equality for this classification. Equality on a heterogeneous union has no semantic meaning until an operation narrows its operands to a homogeneous subset with a documented equality relation. `EngineInputData` is homogeneous value-equality. `EngineOutputData` is heterogeneous: its simple-data subset is homogeneous value-equality, while object and error members need not be. `EngineResult` is likewise heterogeneous because scalar values, result occurrences, lists of cell occurrences, and errors have different equality semantics.

A **pre-domain type** supplies an unambiguous runtime representation that one or more semantic domains may admit. Ordinary Kotlin `Int`, finite `Double`, `Boolean`, and `String` values are pre-domain representations. Qplan's result domain additionally uses `EngineIDResult` and canonical `ViaductSchema.EnumValue` values so GraphQL strings, IDs, and enum members remain distinguishable without carrying a nominal result wrapper around every scalar.

Production Viaduct currently overloads Kotlin `String` for GraphQL String, ID, and enum values in engine input and output data. Qplan's carrier model preserves that representation in `EngineInputData` and `EngineOutputData` for current compatibility, while `EngineResult` uses `String`, `EngineIDResult`, and `ViaductSchema.EnumValue` respectively. Crossings between output data and engine results therefore require schema-directed conversion. This deliberate conversion boundary may disappear after production input and output carriers adopt the stronger pre-domain representations.

Errors belong to domains rather than to every pre-domain type. Engine results, engine output data, and argument resolution use distinct error variants; `EngineInputData` has no error member. No error variant impersonates scalar, list, or object interfaces merely to obtain an artificial Kotlin union. `EngineErrorData` owns all metadata associated with an output error, including causal and attributional metadata. When one erroneous field causes another resolver error, the new error chains the source error while adding its own metadata, preserving the complete causal and attributional history in the error value rather than decomposing it into sidecars. Crossing between output data and engine results must preserve that chain without loss.

## Result Occurrence Is Identity

The semantic identity of work is an occurrence in a rooted result tree. A resolver occurrence combines the reference identity of its Query-rooted OER with its exact path. The primary operation result roots primary occurrences. Ordinary declared Query fragments use the one associated Query OER owned by their containing orchestration, so equal exact keys in that scope share production while every owner retains its own projection. Independently rooted operations such as `ctx.query()` and root-field-reference targets retain fresh roots. Equal node IDs, schema coordinates, arguments, paths in different roots, or values do not merge separate object or list occurrences. List indices and concrete containing paths remain part of occurrence identity. Caching, batching, and request deduplication are separate execution layers.

Cells are allocated by their containing OER or LER. Cell reference identity is the cell occurrence identity; a parallel numeric cell identifier would duplicate and risk disagreeing with the carrier.

Symbolic object keys preserve variable-instance identity structurally in their arguments. Equal symbolic keys may occur in different containing OERs without collision because the OER occurrence already supplies their concrete result-tree location.

## One-Shot Correctness Is Producer-Specific

For every resolver-bearing occurrence in scope, all producer-owned values later consumed from that occurrence must be covered by the demand supplied to its one selective resolver application.

A correct final union is weaker. A late cache hit, widened result, or second materialization can make the completed OER look adequate even when the producing application discarded required output. Waiting for all contributors currently known to exist is also weaker than proving that no future contributor can target the producer.

One-shot designs must therefore bound all contributors before application, conservatively include bounded alternatives, assign late demand a distinct occurrence, or reject the shape. Post-application widening cannot repair an under-supplied selective producer.

## Choose Ungrounded-Key Semantics Explicitly

Argument-bearing fields whose keys are not yet grounded admit three coherent execution policies:

1. Hold execution until every key that could coalesce has grounded, preserving true one-shot execution. This requires a sound and potentially onerous cycle-exclusion rule so dependency cycles cannot deadlock; an attempted Resolver26 variant using this policy deadlocked often enough that it was abandoned.
2. Speculatively add successor demand to resolver instances that future grounded keys might join. This makes supplied demand and work timing-sensitive, producing a nondeterministic developer experience, so qplan rejects this policy.
3. Coalesce by symbolic argument values and variable-instance identity before grounding. Distinct symbolic keys may later ground to equal arguments and invoke the same field resolver more than once. Resolver26 deliberately chooses this policy.

These policies must not be blended accidentally. In particular, symbolic identity is not an incomplete approximation of eventual grounded-key identity, and speculative widening is not a substitute for proving one-shot closure.

## Attribute Demand To Its Owner

Demand must be projected through the producer that owns the requested output. Traversal through passive fields remains within the current producer; traversal stops at resolver-bearing boundaries and attributes successor work to the successor resolver.

Resolver object and Query fragments determine input requirements. Resolver arguments identify an eventual resolver instance but do not choose the resolver template or its fixed fragments. This distinction lets symbolic closure discover fixed input requirements before variable values are available.

## Keep Field Resolvers And Checkers Structurally Aligned

`FieldCheckerResolver` should follow `FieldValueResolver`'s representation, terminology, validation, and variable model wherever their semantics do not require a difference. Do not introduce checker-specific wrappers or lifecycle concepts for facts already modeled by field resolvers.

Resolver-owned identities use an explicit `ResolverTarget`, not a bare schema field. A field-value target and field-checker target for the same schema coordinate identify different resolver slots; variable templates and their instantiated IDs retain that distinction.

`ResolverFragmentTemplates` groups one object template, one Query template, and the variable definitions and optional provider shared by both. A field resolver owns one such pair. A checker owns a named map of pairs and receives both materialized values for each name. For resolution, both expose exactly one object fragment and one Query fragment; the checker combines its pairs independently per root after prefixing variables with the pair name. `ResolverFragment` contains only occurrence-specific resolution facts, while response-key-preserving materialization templates remain on the registry entry until input materialization begins. Their result relations also differ: a resolver produces the field value and may consume output demand, while a checker produces a checker result. Other differences require a concrete semantic justification rather than implementation history.

A field-checker application belongs to one field-cell occurrence. A type-checker application belongs to one concrete OER occurrence and executes once in that OER's orchestration, as a peer of its field work. Ordinary object-valued positions create distinct OER occurrences even when their values or identities agree; a parent backedge reuses its ancestor occurrence and therefore does not create another type-checker application. Field and type results remain separate during resolution and combine only when a checked consumer enforces access to the reached object.

## Progress Is Monotonic And Strict

Mutable semantic state is limited to documented monotonic stores. An OER or LER cell value, a field-cell occurrence's field-checker result, an OER occurrence's type-checker result, and a request-local variable binding move from absent to one immediate or deferred promise; a deferred promise completes once.

A parent may publish a stable child OER before the child is complete. Later work fills absent child cells without replacing the parent or rebuilding the subtree.

Duplicate claims, duplicate writers, repeated lifecycle transitions, undeclared binding reads, and unclaimed cells at freeze time should fail visibly. Idempotence must not hide duplicate scheduling or ownership errors.

## Runtime Variables Add Value-Flow Dependencies

`FromArgument` values are available from the defining resolver occurrence. `FromObjectField` values require reading a resolved provider path and can reveal an exact consumer key only later.

Provider paths are compiled and validated before semantic reasoning, but provider evaluation occurs at runtime in Resolver26. Provider containment and branch ordering are domain restrictions; they are not themselves an execution algorithm.

Substitution precedes exact-key grouping in Resolver01 through Resolver23. Resolver26 retains symbolic keys: selections with the same field and variable instances coalesce, while keys containing different variable instances remain distinct even when those variables bind to equal values.

## Structured Concurrency Owns Request Lifetime

One request-root scope owns all request coroutines. Successful synchronous return means request quiescence. Explicit request cancellation propagates through structured ownership; an ordinary local tenant failure must instead be published at its owning result boundary without aborting the request or cancelling unrelated sibling work.

Resolution code MUST treat the request scope as a root-task capability, not as a convenient general-purpose coroutine scope. Only orchestration-task, field-resolver-task, field-checker-task, and type-checker-task roots may launch directly on it. Object orchestration MUST retain its own conditionally launched request-root coroutine whenever an OER has active work, while an OER with no active work freezes synchronously without launching one. Query-fragment producers, provider readers, binding producers, materializers, and every other auxiliary coroutine MUST be children of the nearest owning orchestration, field-resolver, or checker task. Introducing another request-root task kind requires an explicit design change rather than a direct request-scope launch.

Cross-task readiness travels through named promises or value-bearing deferreds, not through another task's call stack or `Job` completion. Independent object and list occurrences should not require a global barrier.

## Tenant Failure Isolation And Progress

Tenant work includes value resolvers, field and type checkers, and variables-provider callbacks. If any such work hangs, the entire operation may hang. If it takes a long time, the operation may wait for it even after another error makes its output unnecessary, including when the failed consumer was its only demander. Execution need not retract demand, cancel the producer, or detach request-owned work to return sooner.

Producing the best possible outcome in the face of multiple errors is not a goal. Beyond the specified error-propagation and access-enforcement rules, there is no requirement to minimize failure latency, discover every error, choose an optimal error ordering, or salvage the largest possible partial result. Existing access checks and error-combination rules still apply; an error or unfinished check cannot be treated as permission to expose a protected value.

An ordinary tenant failure must remain at its owned field, checker, or binding boundary and propagate through the specified dependency and GraphQL completion rules. Aborting or cancelling the whole request is never an acceptable fallback for handling one or more local tenant errors. Ordinary GraphQL non-null propagation can still make the response data null; that is distinct from an engine abort that discards unrelated work.

This permission to wait does not excuse engine-created deadlocks, missing writers, or promises stranded after their producer exits or is bypassed. Required terminal error publication and explicit cancellation cleanup remain obligations. Caller cancellation and externally imposed deadlines are separate controls, not substitutes for local error handling; this policy does not require forcibly terminating uncooperative tenant code.

## Use Earlier Resolvers To Remove Accidental Complexity

Resolver26 is the intended end product. Maintaining Resolver01–23 helps preserve its architectural integrity by making the decomposition and encapsulation of concerns work across simpler algorithms and different execution structures. Their shared relationships are deliberate constraints on Resolver26's design. A shared interface may enforce an important architectural role without a current polymorphic consumer; `SharedFieldResolverTask` intentionally preserves the field-resolver-task/publication relationship for this purpose. Judge such an abstraction by its stated architectural role as well as its runtime consumers, and do not remove it solely because current callers use concrete implementations.

[`resolver-versions.md`](./resolver-versions.md#elements-of-alignment) makes this principle concrete through the elements to align, the essential differences to preserve, and [code naming preferences](./resolver-versions.md#code-naming-preferences) for corresponding roles.

Resolver03 is the compact semantic reference for demand closure, selective projection, exact-key publication, and completed-result correctness.

Resolver08 holds that semantic capability roughly constant while replacing recursive continuation with explicit depth-first work. Comparing Resolver26 with Resolver03 and Resolver08 helps distinguish essential demand semantics from scheduling machinery.

Resolver23 adds structured suspension and promise installation without path-variable identity. It is the clean coroutine comparison.

Resolver10 is useful as a negative lesson rather than a maintained implementation. Readiness rescanning, persistent late-demand acceptance, and complete-output retention added substantial machinery and could mask an incomplete producing application. Do not recreate that architecture to solve a local Resolver26 problem.

## Validate Independent Properties Independently

`correctResolution` judges the completed primary Query OER and every required shared query-fragment OER extensionally, validating each owner's occurrence-local projection separately. It does not establish resolver application count, supplied demand, binding correctness, execution order, lifecycle ownership, or concurrency.

Keep separate evidence for completed-result correctness, exact and occurrence-aware application identities, from-field bindings, lifecycle invariants, mutation tests, structural activation, and scheduling behavior. An expected-application oracle derived from the completed result under test is not fully independent and must be described accordingly.

Generated presence is weaker than runtime activation. Directed profiles should require the target interaction to execute, and broad campaigns should record enough information to replay one exact `S:R:Q` coordinate. Large green campaigns remain finite evidence and do not override a focused counterexample.

## Engine API Boundary

The qplan model owns carriers such as `EngineResult`, typed keys, schema validation, and occurrence-aware cells. Viaduct's `EngineObjectData.Sync` is the synchronous partial-object boundary and distinguishes an absent field from a present null value. Qplan supplies its own validating implementation so it can preserve schema preconditions and instrumentation points.

Qplan uses Viaduct engine API carriers where they express the same semantic fact while preserving qplan-only structure needed for formal reasoning. Do not erase occurrence identity, ground-key validation, or model invariants merely to reduce source-level differences.

Qplan-owned EODs retain canonical lowered `ViaductSchema.Object` identity for semantic reasoning, but source-backed EODs expose the exact retained source `GraphQLObjectType` through the Engine API. Resolver inputs must have source-schema field shape, and source resolver outputs must be normalized into qplan-owned lowered values before semantic reasoning. Node-valued source output is normalized into canonical `Query.node` root-field references before semantic reasoning.

EOD is a policy-neutral value boundary. Reading a present erroneous selection should expose its `EngineErrorData`; the Tenant API layer decides how tenant code observes that error, including whether a generated accessor throws. Current Engine API implementations that throw while reading an erroneous selection are transitional behavior that qplan must remain compatible with, not the desired ownership boundary. Until that behavior is removed, qplan-owned EODs should provide an explicit engine-only `outputValue` observation that returns the stored output value without applying Tenant API error policy.

Alignment does not require preserving every fragile production representation inside the result tree. In particular, qplan intentionally distinguishes result-domain ID and enum values even though current production engine input and output data represent both as strings. Keep that mismatch isolated in explicit adapters so a future production migration can remove conversions without changing result semantics.

The aligned boundary keeps the future implementation distance small. It does not make production runtime concerns part of every qplan function or imply that `execution2` design is part of qplan work.
