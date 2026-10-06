# Arbitrary Agent Guidance

This package generates schemas, registries, queries, resolver programs, and correctness witnesses for Runtime2's replayable generated tests. Keep generation independent from the implementations and judgments it is intended to test.

- [`impldocs/testing/property-tests.md`](../../../../../../../impldocs/testing/property-tests.md) defines generated worlds, feature controls, serialized profiles, resource ownership, replay, shrinking, rounds, and campaigns.
- [`impldocs/testing/strategy.md`](../../../../../../../impldocs/testing/strategy.md) defines the assurance boundary between generated inputs, correctness oracles, exact witnesses, and structural coverage.
- [`impldocs/testing/guide.md`](../../../../../../../impldocs/testing/guide.md) owns replay-first investigation and counterexample preservation.
- [`impldocs/testing/resolution.md`](../../../../../../../impldocs/testing/resolution.md) owns production-specific profiles, stress commands, and campaign expectations.
- [`impldocs/testing/resolver-dsl.md`](../../../../../../../impldocs/testing/resolver-dsl.md) defines the deterministic DSL used to preserve reduced counterexamples.
- [`impldocs/architecture/model.md`](../../../../../../../impldocs/architecture/model.md) defines the carrier and factory invariants that generated values must satisfy.

Generator profile IDs and replay coordinates are interfaces: preserve them unless the associated profile is deliberately replaced. Keep serialization, resource discovery, launcher behavior, and campaign files in the property-test layer rather than adding those concerns to the arbitrary-value model.
