---
title: Connection Directive
description: Mark object types as Relay Connection types for pagination support.
---

The `@connection` directive marks an object type as a [Relay Connection](https://relay.dev/graphql/connections.htm){:target="_blank"} type, enabling standardized cursor-based pagination in your GraphQL schema.

## Purpose

Use `@connection` to identify types that represent paginated lists following the Relay Connection specification. This enables:

- Build-time validation of connection type structure
- Integration with Viaduct's pagination utilities
- Clear schema documentation of pagination patterns

## Schema definition

The directive is defined in Viaduct's default schema:

```graphql
"Marks an object type as a Relay Connection type"
directive @connection on OBJECT
```

## Usage

Apply `@connection` to object types that follow the Relay Connection pattern:


{{ codetag("demoapps/starwars/modules/filmography/src/main/viaduct/schema/Character.graphqls", "connection_example", lang="graphql") }}

Connection types can include additional fields beyond the Relay spec minimum. Here's an example with an optional `totalCount` field:

{{ codetag("demoapps/starwars/modules/filmography/src/main/viaduct/schema/Film.graphqls", "films_connection_example", lang="graphql") }}


## Requirements

Types marked with `@connection` must:

1. Have a name ending in `Connection`
2. Have an `edges` field returning a list of edge types (marked with `@edge`)
3. Have a `pageInfo: PageInfo!` field

!!! note
    Full validation and builder utilities are under development. See the [Pagination guide](../pagination/index.md) for more details.

## Related

- [`@edge` directive](edge.md) - Mark edge types within connections
- [Pagination guide](../pagination/index.md) - Complete pagination documentation
- [Relay Connection Specification](https://relay.dev/graphql/connections.htm){:target="_blank"} - The specification this implements
