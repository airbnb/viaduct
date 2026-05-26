---
title: Star Wars Demo Application
description: Explore an advanced Viaduct application.
---

## Overview
This application implements a comprehensive GraphQL API for the Star Wars universe, demonstrating how Viaduct handles complex data relationships, advanced resolver patterns, and sophisticated schema design.

## Requirements

- Java 17+ and `git` (see [Project Setup](../setup/index.md) for the full compatibility matrix).
- A clone of [github.com/viaduct-dev/starwars](https://github.com/viaduct-dev/starwars). Read the repo's README for any framework-specific setup steps.
- Linux, Mac, or Windows

## What you'll find

The Star Wars demo showcases:

- **[Global IDs](core/global_id.md)** — Viaduct's identity model and how `Node` types are referenced.
- **[Node Resolvers](core/node_resolvers.md)** — direct object resolution by Global ID.
- **[Field Resolvers](core/field_resolvers.md)** — field-level computation and lightweight lookups.
- **[Batch Resolvers](core/batch_resolvers.md)** — efficient bulk data loading.
- **[Resolver Integration Patterns](core/resolver_integrations.md)** — how the layers compose at execution time.
- **[Mutations](mutations/index.md)** — modifying data through GraphQL mutations.
- **[Variables](variables/index.md)** — dynamic variable provision.
- **[Backing Data](directives/backing_data.md)** — sharing pre-fetched data between sibling resolvers via `@backingData`.

## Getting the Star Wars application

```shell
git clone https://github.com/viaduct-dev/starwars.git
cd starwars
```

## Running the application

Follow the instructions in the repository's README to build and run the application:

```shell
./gradlew test
./gradlew run
```

After exploring the Star Wars application, you'll have a solid understanding of how to build production-ready GraphQL applications with Viaduct.

## What's Next

Continue to [Core Concepts](core/index.md) to walk through Viaduct's foundational building blocks as they appear in this demo.

## Related resources

- [Viaduct Documentation](../../index.md)
- [GitHub Repository](https://github.com/viaduct-dev)


