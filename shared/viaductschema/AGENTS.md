# ViaductSchema Library

## Purpose

The ViaductSchema library provides a unified abstraction layer for working with GraphQL schemas within the Viaduct ecosystem. It defines a comprehensive type-safe interface for accessing and manipulating GraphQL schema elements (types, fields, directives, etc.) independent of the underlying schema representation.

**Key capabilities:**

- **Schema navigation**: Navigate and query GraphQL type systems programmatically
- **Schema projections**: Filter schemas based on custom rules
- **Cross-representation compatibility**: Abstract away differences between schema representations
- **Contract validation**: Ensure different representations conform to abstraction's contract

## Documentation

* [FOR-LIBRARY-USERS.md](FOR-LIBRARY-USERS.md) This file is for developers who use ViaductSchema to work with GraphQL schemas in their applications.
* [FOR-CSV-USERS.md](FOR-CSV-USERS.md) This file is for developers who want to use the `schema2csv` command to explore GraphQL schemas.
* [FOR-IMPLEMENTERS.md](FOR-IMPLEMENTERS.md) This file is for developers who want to create their own `ViaductSchema` implementation backed by a different underlying representation.
* [FOR-MAINTAINERS.md](FOR-MAINTAINERS.md) This file is for developers who maintain or extend the ViaductSchema library itself.

## Goals and Non-Goals

### Goals

1. **Unified Interface**: Provide a single, consistent API for working with GraphQL schemas regardless of their underlying representation (parsed SDL, validated schema, filtered view, etc.)

2. **Navigability**: References "up" (e.g., `containingDef`) and "across" (e.g., `baseTypeDef`) the schema AST as well as "down" (e.g., `fields`)

3. **Immutability**: All schema representations are immutable to enable safe sharing and caching

4. **Performance**: Support efficient schema operations through efficient construction, lazy evaluation and careful memory management

5. **Extensibility**: Allow new schema flavors to be added without changing consuming code

6. **Testability**: Provide contract-based tests that validate implementation correctness

### Non-Goals

1. **Schema Execution**: This library does NOT execute GraphQL queries. It provides an abstraction for schema representations only.

2. **GraphQL Parsing**: This library does NOT provide a parser for GraphQL SDL (it does provide a bridge to graphql-java's `TypeDefinitionRegistry`).

3. **Schema Validation Logic**: This library does NOT implement GraphQL schema validation (it does provide a bridge to graphql-java's validated `GraphQLSchema`).
