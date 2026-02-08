---
title: Pagination
description: Relay-compliant pagination support in Viaduct.
---

# Pagination

!!! info "Coming Soon"
    Full pagination support is under active development. This guide will be expanded as features are released.

## Overview

Viaduct provides utilities for implementing [Relay-style cursor pagination](https://relay.dev/graphql/connections.htm), the industry standard for GraphQL pagination.

## Current Status

The foundation directives are available:

- [`@connection`](../directives/connection.md) - Mark connection types
- [`@edge`](../directives/edge.md) - Mark edge types

## Planned Features

The following features are under development:

- **Connection validation** - Build-time validation of connection/edge type structure
- **ConnectionBuilder** - Utilities for building connection responses
- **Offset cursor support** - Convert offset/limit backends to cursor pagination
- **Full list pagination** - Paginate in-memory lists automatically

## Resources

- [Relay Connection Specification](https://relay.dev/graphql/connections.htm)
- [Roadmap](../../../roadmap/index.md) - See "Connections" feature status
