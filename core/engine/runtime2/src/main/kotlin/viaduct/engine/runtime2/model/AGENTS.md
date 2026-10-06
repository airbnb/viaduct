# Model Agent Guidance

The model package is Runtime2's executable semantic vocabulary. Its stylized Kotlin intentionally makes domains, construction, equality, and invariants explicit; do not reshape it as a conventional object-oriented domain model without first checking the documented semantic boundary.

- [`impldocs/architecture/model.md`](../../../../../../../impldocs/architecture/model.md) is the canonical guide to carriers, keys, variables, results, equality, construction, and factory-established invariants.
- [`impldocs/architecture/execution-model.md`](../../../../../../../impldocs/architecture/execution-model.md) defines the source-world concepts represented by these types.
- [`impldocs/architecture/principles.md`](../../../../../../../impldocs/architecture/principles.md) defines the domain-separation, occurrence-identity, monotonicity, and Engine API rules that model changes must preserve.
- [`impldocs/architecture/resolution.md`](../../../../../../../impldocs/architecture/resolution.md) shows how production Resolution consumes and extends the model.
- [`impldocs/correctness/claims.md`](../../../../../../../impldocs/correctness/claims.md) indexes propositions whose assumptions or conclusions may depend on a model change.

When a model change alters equality, construction, identity, or accepted inputs, update the relevant factories and independently validate every resolver family and correctness oracle that relies on that invariant.
