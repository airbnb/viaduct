# Java mapping domain parity (VIADUCT-700)

Decision: defer a public Java counterpart to Kotlin's experimental `GRTDomain` and `JsonDomain`
until a concrete Java tenant caller needs whole-type object mapping. At source snapshot
`7cefdc6ffe124`, the reviewed Java API, runtime, tenant, and demo application source has no such
caller. Kotlin's mapping contract exercises resolvers that convert a GRT to JSON and JSON to a
GRT, but it has no Java implementation. That contract is a possible use case, not evidence of a
Java tenant dependency.

The Java bridge already converts generated output objects to engine data, and Java input GRTs
already wrap input maps. These execution paths do not expose a tenant mapping domain.
Java JSON scalars and ordinary GraphQL input/output conversion remain supported independently of
this decision.

If a Java caller appears, define the supported operation and Java-facing types from that caller.
Reuse the shared `viaduct.mapping.graphql` domain and IR model, and adapt Java generated objects at
the boundary. Do not route Java execution through Kotlin's GRT conversion factory solely for API
parity. Define behavior for input and output objects, abstract object types, explicit types versus
`__typename`, missing or unknown typenames, incompatible types, and conversion errors before
implementation. Verify round trips for nested values, lists, nulls, enums, IDs, and JSON.

Whole-type mapping would use GraphQL field names and would not support aliased fields. Treat
projection and alias mapping as a separate decision. It does not depend on re-enabling Java
selective resolvers, which are currently disabled as described in `selective-resolvers.md`.

Source anchors: [Kotlin GRT domain](../../../tenant/api/src/main/kotlin/viaduct/api/mapping/GRTDomain.kt),
[Kotlin JSON domain](../../../tenant/api/src/main/kotlin/viaduct/api/mapping/JsonDomain.kt),
[Kotlin mapping contract](../../../tenant/runtime/src/testFixtures/kotlin/viaduct/tenant/runtime/execution/mapping/MappingContractTest.kt),
and [Java bridge conversion](../runtime/src/main/kotlin/viaduct/java/runtime/bridge/GRTConverter.kt).
