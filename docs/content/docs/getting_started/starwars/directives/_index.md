---
title: Custom Directives
description: Learn about custom directives like @backingData, @scope, @idOf, and @oneOf in Viaduct.
layout: docs/single
weight: 2
---

Custom directives in Viaduct enhance your GraphQL schema with extra behavior and validation. This page introduces the
directives used in the Star Wars demo and links to focused pages for details and examples.

## What you will find here

- **`@backingData`** — bind a field to a backing data class for transformation logic.
- **`@scope`** — expose types/fields only to specific scopes (multi-module boundaries).
- **`@idOf`** — mark `ID` fields/args with their GraphQL type for type-safe Global ID handling.
- **`@oneOf`** — enforce exactly one non-null field in an input object (union-like inputs).

> To access scoped fields, include the `X-Viaduct-Scopes` header in your request.

{{< prevnext >}}
