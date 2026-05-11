---
title: Getting Started
description: Set up your first Viaduct application
---

This section will guide you through setting up your first Viaduct tenant and understanding the core concepts of Viaduct. You'll start by configuring your development environment—either by cloning an existing tenant or creating one from scratch—then take a tour of the codebase structure. The Star Wars tutorial walks you through building a complete GraphQL API, covering schemas, resolvers, directives, mutations, and testing. By the end of this section, you'll have a working Viaduct tenant and the foundational knowledge to build and extend your own GraphQL servers.

## Start here if you are…

- **A tenant developer** writing GraphQL schema and resolvers — work through this Getting Started section, then keep the [Developers](../docs/developers/index.md) reference at hand.
- **A service engineer** embedding Viaduct into a web service — skim Getting Started for vocabulary, then go to [Service Engineers](../docs/service_engineers/index.md) for configuration, multi-tenancy, and observability.
- **A contributor** working on the Viaduct codebase itself — start with [Contributors](../docs/contributors/index.md) for the architecture overview.

## Terminology

A few terms that appear throughout the docs:

- **Tenant** — a logical owner of a slice of the schema. In practice, an Airbnb-style team that owns some types and fields.
- **Tenant module** — a Gradle module containing one tenant's schema files and resolver code. A Viaduct application is composed of one or more tenant modules.
- **Viaduct (the server)** — the whole product: the runtime plus the developer API. This is what an embedding service depends on.
- **Engine** — the lower-level execution component that plans and runs GraphQL operations. Surfaced as `viaduct.engine.api.Engine`; service engineers configure it via the `viaduct.service.api.Viaduct` entry point.
- **Resolver** — a function (written by a tenant) that produces the value for a field marked with `@resolver`.
- **GRT (GraphQL Representational Type)** — the Kotlin class or interface Viaduct generates for each GraphQL type so resolvers can read and build values type-safely.
- **Global ID** — an opaque, type-tagged identifier for an object that implements `Node`. Viaduct's identity model assumes references between objects flow through Global IDs.
- **Scope** — a label used by `@scope` to expose different schema variants (e.g., `default`, `internal`) from the same codebase.

## What's Next

Continue to [Project Setup](setup/index.md) to understand the structure of a Viaduct application.

