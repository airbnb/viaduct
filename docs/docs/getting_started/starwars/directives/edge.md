---
title: Edge Directive
description: Mark object types as Relay Edge types within connections.
---

The `@edge` directive marks an object type as a [Relay Edge](https://relay.dev/graphql/connections.htm){:target="_blank"} type, representing an item within a paginated connection.

## Purpose

Use `@edge` to identify types that wrap nodes in a connection with cursor information. This enables:

- Build-time validation of edge type structure
- Integration with Viaduct's pagination utilities
- Clear schema documentation of pagination patterns

## Schema definition

The directive is defined in Viaduct's default schema:

```graphql
"Marks an object type as a Relay Edge type"
directive @edge on OBJECT
```

## Usage

Apply `@edge` to object types within connections:


{{ codetag("demoapps/starwars/modules/filmography/src/main/viaduct/schema/Character.graphqls", "edge_example", lang="graphql") }}

Here's another edge example from the Films connection:

{{ codetag("demoapps/starwars/modules/filmography/src/main/viaduct/schema/Film.graphqls", "films_edge_example", lang="graphql") }}


## Requirements

Types marked with `@edge` must:

1. Have a `node` field (any output type except list)
2. Have a `cursor: String!` field (non-nullable String)

!!! note
    Full validation and builder utilities are under development. See the [Pagination guide](../pagination/index.md) for more details.

## Related

- [`@connection` directive](connection.md) - Mark connection types
- [Pagination guide](../pagination/index.md) - Complete pagination documentation
- [Relay Connection Specification](https://relay.dev/graphql/connections.htm){:target="_blank"} - The specification this implements
