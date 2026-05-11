---
title: Custom Directives
description: Learn about custom directives like @backingData, @scope, @idOf, @oneOf, @connection, and @edge in Viaduct.
---


Custom directives in Viaduct enhance your GraphQL schema with extra behavior and validation. A **custom directive** is a schema annotation that Viaduct interprets at planning or execution time — they let you express resolver hints, identity types, and visibility rules without writing extra Kotlin. This page introduces the directives used in the Star Wars demo and links to focused pages for details and examples.

## What you will find here

- [`@backingData`](backing_data.md) — share pre-fetched data between sibling field resolvers to avoid duplicate calls.
- [`@scope`](scope.md) — expose types/fields only to specific scopes (multi-module boundaries).
- [`@idOf`](idof.md) — mark `ID` fields/args with their GraphQL type for type-safe Global ID handling.
- [`@oneOf`](oneof.md) — enforce exactly one non-null field in an input object (union-like inputs).
- [`@connection`](connection.md) — mark object types as Relay Connection types for pagination.
- [`@edge`](edge.md) — mark object types as Relay Edge types within connections.


