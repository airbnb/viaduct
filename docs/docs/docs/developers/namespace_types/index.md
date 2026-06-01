---
title: Namespace Types
description: Grouping related root fields with @namespaceType
---


## Motivation

As a GraphQL schema grows, the root query type accumulates fields from many teams and domains. Without organization, the root types become a flat list of hundreds of fields — hard to navigate, prone to naming collisions, and impossible to scope ownership to.

The `@namespaceType` directive solves this by letting you group related root fields under a dedicated type that acts purely as an organizational namespace. Namespace types don't represent domain entities — they exist only to provide structure in the schema hierarchy. The engine auto-resolves fields that return a namespace type (no resolver needed for the namespace field itself), so they add zero runtime cost.

Namespace types also enable cross-tenant delegation patterns like factory functions. A namespace type can expose `@resolver` fields that other tenants invoke via `ctx.rootFieldRef()` without needing a direct code dependency on the owning tenant.

## Usage

Apply `@namespaceType` to an object type and expose it as a field on the root query type:

```graphql
type Query {
  listings: Listings
}

type Listings @namespaceType {
  availableRoomTypes: [RoomType] @resolver
}
```

The `Query.listings` field is automatically resolved by the engine — you don't write a resolver for it. You only write resolvers for the fields *inside* the namespace type (such as `availableRoomTypes`).

### Nesting namespace types

Namespace types can be nested to create deeper hierarchies:

```graphql
type Listings @namespaceType {
  availableRoomTypes: [RoomType] @resolver
  pricing: ListingsPricing
}

type ListingsPricing @namespaceType {
  currencyOptions: [Currency] @resolver
}
```

## Constraints

The following constraints are enforced at build time:

### Namespace fields must take no arguments

```graphql
type Query {
  # Invalid — namespace fields cannot take arguments
  listings(region: String!): Listings
}

type Listings @namespaceType { ... }
```

The namespace field is a pure organizational grouping. Arguments belong on the fields *inside* the namespace type.

### Namespace fields cannot be lists

```graphql
type Query {
  # Invalid — namespace types cannot appear in lists
  listings: [Listings]
}
type Listings @namespaceType { ... }
```

### Namespace fields must be nullable

```graphql
type Query {
  # Invalid — namespace fields must be nullable
  listings: Listings!
}
type Listings @namespaceType { ... }
```

This follows the general Viaduct convention that fields should be nullable to avoid error propagation.

### Namespace fields can only appear in root query or namespace types

```graphql
type RoomType {
  # Invalid — RoomType is not a root or namespace type
  listings: Listings
}
type Listings @namespaceType { ... }
```

A namespace type must be reachable from the root query type only through other namespace types. It cannot appear as a field on a regular type.

### Each namespace type must be referenced by exactly one field

```graphql
type Query {
  # Invalid — Listings is referenced by two fields
  listings1: Listings
  listings2: Listings
}
type Listings @namespaceType { ... }
```
