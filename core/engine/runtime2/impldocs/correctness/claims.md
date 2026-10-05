# Correctness Claims

These claims record implementation invariants that constrain production Resolution or the maintained Resolver01–23 families. Each claim states its domain explicitly and is supported by Kotlin implementation reasoning, focused tests, generated tests, and cross-family comparison as applicable. The evidence is intentionally scoped: no individual claim establishes Runtime2 correctness as a whole.

**[flattened-equivalence](./arguments/flattened-equivalence.md).** Within Runtime2's post-validation ordinary field-resolution boundary for Query selections and mutation payloads, flattened selections preserve the same unordered field-resolution obligations as nested GraphQL selections. Ordered mutation namespace traversal is outside this claim.

**[field-only-node-lowering](./arguments/field-only-node-lowering.md).** Within the fixture-supported node domain, retaining Node-valued source field coordinates while normalizing node values into concrete-type-bearing root-field references to the built-in `Query.node` preserves the field-resolution obligations of external Node-valued field and node-resolver inputs.

**[resolver03-one-shot-construction](./arguments/resolver03-one-shot-construction.md).** Within Resolver03's acyclic domain with argument-defined variables, every resolver-bearing OER occurrence is constructed by one field-resolver application after all guarded transitive demand for that occurrence has been aggregated.

**[resolver02-demand-closed-result](./arguments/resolver02-demand-closed-result.md).** Within Resolver02's finite acyclic domain with only `FromArgument` variables, resolving a Query selection forest produces an `ObjectEngineResult` closed under every activated resolver's fixed input demand.

**[resolver-provider-containment-construction](./arguments/resolver-provider-containment-construction.md).** Pre-reasoning construction of every canonical Kotlin registry built by `TestWorld` contains each field-relative variable provider path in its defining resolver's fixed object fragment.
