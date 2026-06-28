---
title: Service Engineers
description: Operating a Viaduct instance.
---

As a service engineer, you manage the Viaduct service itself: integrating it into your organization's web-serving stack, configuring how tenant modules are composed, extending the schema with shared types and directives, integrating with your organization's standard dependency-injection framework, and setting up monitoring and alerting. You bridge the gap between individual tenant teams and the underlying platform, ensuring the GraphQL API performs reliably at scale. This section documents how to perform these functions. If you're looking for material on how to write application logic using the Viaduct framework, turn to the [Developers](../developers/index.md) section instead.

## What's in this section

- **[Server Integration](server_integration/index.md)** — Embed Viaduct into an HTTP framework, message consumer, or other entry point.
- **[Schema Extensions](schema_extensions/index.md)** — Define application-wide custom directives and shared types available to all tenants.
- **[Dependency Injection](dependency_injection/index.md)** — Wire a DI framework (e.g., Guice, Koin, Spring) into Viaduct so resolver classes can declare constructor dependencies.
- **[Observability](observability/index.md)** — Configure metrics, error reporting, and monitoring.
- **[Multi-tenancy](multi_tenancy/index.md)** — Compose multiple independently-developed tenant modules into a single unified schema.
- **[Feature Flags](feature_flags/index.md)** — Configure Viaduct feature flags.

When reading this material, click through to the linked KDocs for more details on individual classes.
