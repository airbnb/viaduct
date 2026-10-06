# Production Resolution Agent Guidance

This package contains Runtime2's production resolution implementation and its shared production framework. [`Resolver.kt`](Resolver.kt) is production Resolution; do not describe it as Resolver26 or as a prospective implementation.

- [`impldocs/architecture/resolution.md`](../../../../../../../impldocs/architecture/resolution.md) is the canonical algorithm and protocol description.
- [`impldocs/architecture/principles.md`](../../../../../../../impldocs/architecture/principles.md) defines the semantic, ownership, progress, failure, and concurrency rules Resolution must preserve.
- [`impldocs/architecture/resolver-families.md`](../../../../../../../impldocs/architecture/resolver-families.md) defines how production Resolution aligns with Resolver01–23 and which framework boundaries are intentionally shared.
- [`impldocs/architecture/access-checks.md`](../../../../../../../impldocs/architecture/access-checks.md) owns field- and type-check semantics, raw reads, applicability, demand provenance, and exact checker identity.
- [`impldocs/integration/engine-api.md`](../../../../../../../impldocs/integration/engine-api.md) defines how Resolution is adapted to GraphQL Java and the Engine API.
- [`impldocs/testing/resolution.md`](../../../../../../../impldocs/testing/resolution.md) owns focused tests, stress profiles, concurrency settings, and campaign validation.
- [`impldocs/testing/strategy.md`](../../../../../../../impldocs/testing/strategy.md) defines the independent evidence required for semantic changes.
- [`impldocs/correctness/inclusion-validation.md`](../../../../../../../impldocs/correctness/inclusion-validation.md) records the current inclusion-condition validation boundary.

For a new semantic feature, use the maintained implementation ladder: Resolver01–03, Resolver06–08, Resolver21–23, then production Resolution. A production-only change needs an explicit reason grounded in the family roles or production-only machinery.

Preserve occurrence identity, producer-specific one-shot demand, owner attribution, monotonic state, structured request ownership, and local tenant-failure publication. Validate result correctness independently from exact resolver and checker applications; a final result alone is not sufficient evidence for producer behavior.
