---
title: Core Concepts
description: Viaduct core concepts applied in the Star Wars demo application.
---


This section explains how Viaduct's core building blocks — schemas, resolvers, scopes, Global IDs, and batching — map onto the Star Wars demo application. The demo is split across two **tenants** (`universe` and `filmography`); each tenant owns part of the schema and the resolver code that backs it, and they compose into one runnable server. Throughout this section we use minimal in-memory data so the patterns are easy to see and adapt to real backends later.

## Topics in this section

- [Global IDs](global_id.md)
- [Node Resolvers](node_resolvers.md)
- [Field Resolvers](field_resolvers.md)
- [Batch Resolvers](batch_resolvers.md)
- [Resolver Integration Patterns](resolver_integrations.md)


