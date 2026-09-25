# Qplan Documentation

- [`README.md`](./README.md) - Start here for qplan's purpose, integration state, project layout, and documentation map.
- [`handoff.md`](./handoff.md) - Read before acting for the current implementation state, explicit scope boundaries, validation evidence, and longer-term context.
- [`design-principles.md`](./design-principles.md) - Use for durable modeling rules, semantic boundaries, occurrence identity, one-shot correctness, and the Engine API boundary.
- [`research-evidence.md`](./research-evidence.md) - Use for the evidence, obligations, hard cases, prior art, acceptance cases, and provenance behind the design principles.
- [`maintainer-guide.md`](./maintainer-guide.md) - Use for validation, replay, failure classification, debugging, documentation conventions, and investigation workflow.
- [`resolver-versions.md`](./resolver-versions.md) - Use to understand the maintained resolver grid and choose a simpler comparison implementation.
- [`access-check-semantics.md`](./access-check-semantics.md) - Use for access-check vocabulary, checker demand, enforcement boundaries, type base-cell behavior, fresh checker Query OERs, and bypass semantics.
- [Producer-Activation Inclusion Handoff](https://slate.airbnb.tools/w875KwohFB) - Deferred design handoff for making Resolver01–08 and Resolver21–23 apply `@skip`/`@include` to resolver, checker, Query-fragment, and reference activation rather than only input projection.
- [`model/guidelines.md`](./model/guidelines.md) - Read before changing semantic carriers, equality, factories, promises, keys, result structures, or model dependency boundaries involving `Assumptions`.
- [`semantics/README.md`](./semantics/README.md) - Read before changing semantic transformations, resolver implementations, correctness judgments, semantic context ownership, or operation dependencies involving `SharedOperationContext`.
- [`semantics/testing-contracts.md`](./semantics/testing-contracts.md) - Read before changing or interpreting resolver tests and generated profiles.
- [`semantics/property-test-rounds.md`](./semantics/property-test-rounds.md) - Read before changing serialized generator profiles, campaign resources, or standalone property-test campaign execution.
- [`resolver-test-dsl.md`](./resolver-test-dsl.md) - Read before adding schema-embedded deterministic resolver worlds or counterexamples.
- [`arbitrary/README.md`](./arbitrary/README.md) - Read before changing schema, registry, query, or witness generation.
- [`execution/README.md`](./execution/README.md) - Read before changing GraphQL execution, executor-backed feature tests, registry adaptation, or execution integration scope.
- [`spec/UPSTREAM.md`](./spec/UPSTREAM.md) - Use for the vendored GraphQL specification's source baseline, build boundary, and manual update policy.
- [`claims.md`](./claims.md) - Use for the index of stable propositions and links to their scoped arguments.
- [`tla/README.md`](./tla/README.md) - Read before changing or citing the machine-checked TLA+ baseline.
- [`semantics/resolver-benchmarks.md`](./semantics/resolver-benchmarks.md) - Read before running, changing, or reporting resolver benchmarks.
- [`semantics/resolver-profiling.md`](./semantics/resolver-profiling.md) - Read before capturing or interpreting resolver profiles, and update its performance log when an investigation concludes.

## Writing resolver-test DSL schemas

When writing resolver-test DSL schemas in tests, documentation, or counterexamples, present them top-down: start with `extend type Query`, then define the types reached from its fields, followed by their dependencies.

## Markdown formatting

Write each prose paragraph in Markdown on one physical line, including paragraphs within list items; do not hard-wrap prose. Preserve structural line boundaries for headings, separate list items, tables, and fenced code blocks.
