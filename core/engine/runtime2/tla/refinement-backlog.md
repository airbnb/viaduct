# TLA+ Refinement Backlog

## Purpose

This file records work needed to turn the passing Resolver01-03 atomic proof baseline into a refinement argument for the Kotlin model. [`README.md`](README.md) defines what is currently machine checked, and [`../design-principles.md`](../impldocs/design-principles.md) defines the durable semantic obligations.

This is a separate backlog rather than an immediate project objective. It assumes the aligned carrier boundary recorded in [the model guidelines](../impldocs/model/guidelines.md), but no ordering against other qplan work is implied.

Field-relative variables, resolver Query fragments and their occurrence-specific Query OERs, `@parent`, checkers, lazy values, and cyclic resolver demand remain outside the current proof scope.

## Current Assessment

The TLAPS proofs are strong within their stated finite carriers and assumptions, but they are not yet proofs of the Kotlin constructors or `correctResolution`.

- Internal proof validity: high.
- Refinement to Kotlin structures and execution: low.
- Adversarial TLC coverage: weak.

The main issue is that several relations the implementation should derive are caller-supplied premises. A broken constructor can still choose atomic maps satisfying the current theorems.

## Refinement Gaps

### Returned Cells

`OccurrenceFolds.tla` removes abstract work and maps it to stipulated `WorkCell` values. It does not construct returned cells through counterparts of `resolveKey`, recursive `resolvePassiveValues`, and write-once `ObjectEngineResult.write`.

Terminal built keys therefore align with returned OER cells only by assumption. The refinement must derive both cell presence and cell values from the fold transitions.

### Complete Observations

`ResultTree.tla` accepts resolver observations rather than deriving every scalar, null, shape, list-position, and passive-descendant observation traversed by Kotlin `conformsToResolvers`.

Actual observations must come from actual returned cells; ideal observations must come from the raw resolver result on the final materialized input.

### Fragment Demand

`OperationDemand`, `ResolverDemand`, and related maps are inputs rather than extractions from structural fragments, exact keys, type guards, and registry membership. They can omit a Kotlin-required selection while the abstract closure theorem still passes.

### Materialization

`Materialization.tla` does not require `CellValue` to equal the corresponding returned cell. `ResolverApplication.tla` also abstracts away missing keys, resolver partiality, projection failure, and invalid construction.

The next model should make these operations structural and partial, with domain violations visible to TLC.

### Identity

Opaque atoms do not prove that unequal Kotlin exact keys, arguments, containing-object paths, concrete guards, list positions, and OER occurrences remain unequal after extraction.

The refinement needs explicit structural carriers or injective preservation obligations. In particular, distinct `ObjectEngineResult.GroundKey` values and distinct object occurrences must not collapse.

### Classifications And Attribution

Resolver cells, argument-error cells, typename cells, resolver boundaries, and cell producers are caller-classified. These sets should instead be derived from exact keys, registry membership, resolver-output guarantees, and construction transitions.

### One Application

The local core proves one sequence position per demanded exact key, but the composed Resolver03 result does not expose that fact. The final theorem should include one mathematical application per resolver-bearing `ObjectEngineResult.GroundKey` and OER occurrence.

### Projection Facts

The current projection relation needs a demand token to retain some scalar and shape facts that Kotlin preserves independently of nested demand. Separate demand-independent value facts from demand-dependent passive observations.

## Repair Sequence

### 1. Adversarial Fixtures

Create TLC fixtures that initially admit and expose:

- a wrong returned cell paired with an ideal observation;
- an omitted scalar or passive-descendant observation;
- a nonempty fragment mapped to empty demand;
- returned-cell and `CellValue` disagreement;
- collapsed argument tuples or list occurrences;
- wrong resolver, typename, error, boundary, or producer classification;
- a false local one-application fact.

Each repaired relation should make its fixture pass for the intended reason. Keep validity predicates as invariants so malformed or vacuous worlds fail visibly.

### 2. Structural Extraction

Introduce finite structures or proved refinement maps for schemas, object occurrences, `ObjectEngineResult.GroundKey`, selections, type guards, fragments, values, object cells, and list positions.

Derive registry classifications and operation/resolver demand from those structures. Prove preservation of arguments, paths, guards, and occurrence identity.

### 3. Monotonic Construction

Model the returned OER as transition state. Each cell moves from absent to one value and never changes; published child OER occurrences retain identity while their own cells are added.

Model equivalents of `resolveKey`, recursive `resolvePassiveValues`, materialization, and cell write. Terminal cells and values should then be the state produced by the fold, not alignment inputs.

This is also the right basis for refining the aligned qplan constructors: the refinement should model monotonic writes rather than immutable subtree union.

### 4. Structural Observation And Projection

Derive complete observations by traversing actual raw outputs and returned cells. Tie producer attribution to the exact resolver-bearing cell and derive `snipToDemand` behavior from the same structures.

### 5. Composed Theorems

State separate theorems for termination, demand closure, input availability, projection coverage, returned-result correctness, and one application per exact cell occurrence.

Final premises may constrain valid worlds and resolver functions, but must not choose supplied demand, returned cells, actual observations, or application counts for the algorithm.

## Suggested Repair Order

1. Preserve the passing baseline while adding a new adversarial refinement model.
2. Add wrong-cell and missing-observation countermodels.
3. Add structural exact-key and occurrence identity, including unequal arguments.
4. Connect monotonic fold state to returned cells.
5. Derive complete observations and demand-independent projection facts.
6. Derive fragment demand and semantic classifications.
7. Compose one-application into the final Resolver03 theorem.

## Validation

Run TLAPS serially because `.tlacache` is shared. Run TLC models concurrently only with distinct metadata directories.

Every new world predicate needs a negative fixture. Record explored state counts, treat unexpectedly small counts as possible over-constraint, and require a mutation that breaks the modeled algorithm while preserving carrier validity.

The matrix in [`README.md`](README.md) remains the regression suite for the internal calculus. Passing it is necessary but not sufficient evidence for refinement.
