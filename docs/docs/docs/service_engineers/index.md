---
title: Service Engineers
description: Operating a Viaduct instance.
---

The Service Engineers section is for those responsible for operating and configuring a Viaduct instance—as opposed to developing tenant modules that run on it. While the Developers section covers writing resolvers, defining schemas, and building GraphQL features, this section addresses infrastructure and operational concerns.

As a service engineer, you manage the Viaduct service itself: configuring how tenant modules are composed, setting up monitoring and alerting, defining application-wide policies, and extending the schema with shared types and directives. You bridge the gap between individual tenant teams and the underlying platform, ensuring the GraphQL API performs reliably at scale.

If you're building features within a Viaduct application, start with the [Developers](../developers/index.md) section instead. Come back here when you need to configure the Viaduct instance itself or set up operational tooling.

## What's in this section

- **[Dependency Injection](dependency_injection/index.md)** — Wire a DI framework (Micronaut, Guice, Spring, etc.) into Viaduct so resolver classes can declare constructor dependencies. Start here if your resolvers need services injected.
- **[Feature Flags](feature_flags/index.md)** — Control runtime behaviours such as access-check enforcement and experimental features by bridging Viaduct's `FlagManager` into your organisation's flag system.
- **[Multi-tenancy](multi_tenancy/index.md)** — Compose multiple independently-developed tenant modules into a single unified schema.
- **[Observability](observability/index.md)** — Configure metrics, error reporting, and monitoring.
- **[Schema Extensions](schema_extensions/index.md)** — Define application-wide custom directives and shared types available to all tenants.
- **[Server Integration](server_integration/index.md)** — Embed Viaduct into an HTTP framework, message consumer, or any other entry point.
