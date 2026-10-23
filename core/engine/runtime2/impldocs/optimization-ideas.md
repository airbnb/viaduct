# Optimization Ideas

## Simple Ideas

- Search and eliminate list consumers that make defensive copies — producers should make defensive copies.

- Replace the three `ConcurrentHashMap`s in `AbstractCycleCheckState` with plain `HashMap`s
  pre-sized to ~512. All mutation is already `@Synchronized`; the CHM segment locking is
  pure waste. Similarly, `ConcurrentHashMap.newKeySet()` in `cycleCheck` and `addRead`
  should become plain `HashSet`. This is ~50% of allocation bytes in the resolver-only
  benchmark.

- Cache hashCode on `ObjectKeyImpl` and `ArgumentsImpl`. The Slate profiling run (752 CPU
  samples) shows `HashMap.getNode` (6.5%) + `Intrinsics.areEqual` (5.9%) +
  `StringLatin1.hashCode` (2.0%) = ~14% exclusive CPU spent on key equality in HashMap
  lookups. Pre-computed hashCode would let unequal keys short-circuit before the expensive
  structural equality walk.

- Intern common `possibleTypes` singleton sets per `ViaductSchema.Object` in `Selection.of`.
  Many selections share the same `setOf(type)` but allocate a fresh `HashSet` each time
  (~4.8% of allocation events in the Slate profile).

- Fuse the multiple successor-demand tree walks (`liftParentConstructionDemand`,
  `successorDemandWithChecks`, `compactSuccessorDemand`, `liftParentSuccessorDemand`) into
  fewer passes. Currently ~4 full recursive walks per resolver publication at ~26% inclusive
  CPU.

- Improve `SelectionForest.merge` cache hit rate by propagating cached merge results through
  `ConcatenatedSelectionForest` wrapping. Currently 16% inclusive CPU; cache is lost when
  the same logical forest is re-wrapped.

- Use mutable accumulators in `ParentDemandAnalysis` instead of creating intermediate
  immutable results via `plus()` at every recursion level.

- Use `buildList` or a mutable list in `SharedPassiveValueResolutionLogic.resolvePassiveValues`
  instead of `CollectionsKt.plus` which copies the backing array on every concatenation.

## Cons-Cell Path Representation

`List<PathComponent>` is used throughout resolution to represent the location of an
occurrence in the OER tree. The hot operation is append (`path + key` or
`path + Index.of(index)`), which currently copies the entire backing array every time.
Paths grow incrementally through passive-value descent and orchestration, so a query of
depth 10 produces O(n²) total copies across its path chain.

A linked cons-cell representation would make append O(1):

```kotlin
class PathComponentList(
    val prev: PathComponentList?,
    val component: PathComponent,
)
```

`isEmpty` becomes `prev == null` (or a sentinel empty instance). `hashCode` can be
computed incrementally at construction: `31 * (prev?.hashCode() ?: 1) + component.hashCode()`.
Equality walks the chain but short-circuits on cached hash mismatch.

A survey of all production `path` access patterns found that **nothing in the hot path
needs forward indexed traversal**:

- **`.isEmpty()`** — 3 sites (root checks). Trivial with linked representation.
- **`.hashCode()` / `.equals()`** — CycleTask and ResolverOccurrenceId map keys. O(1) cached hash; equality walks the chain but mismatches short-circuit.
- **`+ component`** — the dominant hot operation (coordinate, passive descent, list indexing). Becomes O(1) cons.
- **`.filterIsInstance<ObjectKey>().last()`** — works with reverse traversal.
- **`.fold(...)`** — hash computation. Order-independent.

The few sites needing forward traversal are cold paths:

- **`.dropLast(1).map { ... }`** — root-field-reference prefix extraction (FieldResolutionLogic.kt:337). With linked representation, `dropLast(1)` is just `prev`. The `.map` walks the chain; still cheaper than an array copy.
- **`.mapIndexed { ... }`** — two sites in ResolverSupport.kt and FieldValueResolver.kt, both registry-setup paths.
- **`joinToString` / `toString`** — error messages and diagnostics.

These cold paths can `toList()` on demand. The hot path — append, identity, isEmpty — all
improve.

This would eliminate the redundant defensive `toList()` copies in both `OEROccurrence`
(line 13) and `CycleTask` (line 25), since the linked representation is inherently
immutable — appending produces a new head without mutating the tail.

## Vended Operation-Scoped Integer Identifiers

### Idea

Many types in the resolution hot path exist solely to name an "occurrence" or "instance" of
something — a slot, a task, a resolver application. These identity objects carry structural
fields (path lists, enum tags, owner references) and implement `equals`/`hashCode` for use
as map keys. They allocate on the heap, defensively copy their inputs, and pay for structural
equality on every map lookup.

The proposal: put an `AtomicInteger` on `OperationContext` and use it to vend unique integer
IDs across an operation. Each identity object's factory function would instead request a
fresh integer from the operation counter. Maps keyed on these identities become
`HashMap<Int, ...>` with O(1) equality and zero heap allocation for the key.

### Candidates

A survey of the resolution and model packages found four vendable types and one that is not:

**CycleSlot** (`CycleCheckState.kt:76`) — VENDABLE.
Identifies one independently readable result slot (value, field-checker, or type-checker)
on a cell or result. Created from a single source of truth: the owning
`EngineResultCell` or `ObjectEngineResult`. Each cell has exactly 2 slots (value +
fieldChecker); each result has 1 (typeChecker). Currently a `(CycleSlotKind, owner)`
pair using identity equality (`owner ===`). Extension properties create a fresh
`CycleSlot` on every access — not actually lazy, just re-allocating. Vending 2–3 ints at
cell/result creation time would eliminate all CycleSlot heap objects (~800K per benchmark
batch).

**CycleTask** (`CycleCheckState.kt:20`) — VENDABLE.
Identifies one task (field resolver, field checker, or type checker) at one result
occurrence. Created from a single source of truth: the task object itself.
`FieldCheckerTask` already caches its `CycleTask` (line 78); `FieldResolverTask` and
`TypeCheckerTask` should do the same regardless of whether the type becomes an int. The
constructor defensively copies its path list (`path.toList()`), but every call site
already passes an immutable or freshly allocated list — the copy is redundant. Vending
an int at task creation eliminates the `CycleTask` object, the path list copy, and the
`OEROccurrence.coordinate(key)` list creation used to build the path.

**ResolverOccurrenceId** (`OccurrenceIds.kt`) — MIXED, trending VENDABLE.
Identifies one concrete resolver application at a specific path in the OER tree. Created
at one site (`FieldResolverTask` preparation), but embedded inside `VariableInstanceId`
and `Arguments.Variable`, so those structures would need updating too. Constructor does
`path.toList()`, also redundant per the same analysis.

**VariableInstanceId** (`OccurrenceIds.kt`) — MIXED, trending VENDABLE.
Identifies one variable declaration instantiated for one resolver occurrence. Created
from `Variable.instantiate(resolverOccurrenceId)` for a
`(resolverOccurrenceId, target, name)` triple. Piggybacks on `ResolverOccurrenceId`;
if that becomes an int, this can too (or become a vended int itself, deduped by the
triple at the instantiation site).

**ObjectKey** (`EngineResult.kt`) — STRUCTURAL, NOT VENDABLE.
Identifies one field selection within an object result (field + arguments).
Independently produced from many sites (selection parsing, closure computation,
materialization lookup). Cell lookup works by constructing a key from field and arguments
and probing whether a cell exists. Fundamentally structural.

### Scope

CycleSlot and CycleTask are the immediate low-risk targets — they're confined to the
cycle checker and don't flow through the broader selection/variable model. ResolverOccurrenceId
and VariableInstanceId are higher-ceiling but more invasive since they're woven into the
variable-binding identity chain.

`BindingDeclarationsState` keys (`ObjectEngineResult` references in a `ConcurrentHashMap`)
are also vendable if results received an int ID.

`OEROccurrence` already uses reference identity and wouldn't change, but its
`coordinate(key)` method — which creates `path + key` lists solely for `CycleTask`
construction — becomes unnecessary once `CycleTask` is a vended int.
