---
title: Schema Reference
description: Viaduct's built-in directives, types, scalars, and schema components
---


Viaduct automatically provides a rich set of built-in schema components that are available in every application. This reference documents the directives, types, scalars, and other schema elements provided by the framework.

## Built-in Directives

Viaduct includes four core directives that are fundamental to the framework's functionality. These directives are automatically available and cannot be overridden.

### @resolver

Marks fields or types that require custom resolution logic. This is the primary mechanism for implementing data fetching in Viaduct.

**Locations:** `FIELD_DEFINITION`, `OBJECT`

**Arguments:**

- `isSelective: Boolean! = false` — enables selective resolution
- `isBatching: Boolean! = false` — when `true`, codegen generates a `batchResolve` method instead of `resolve`, enabling batch resolution for the field or type

**Example:**

```graphql
type Query {
  user(id: ID!): User @resolver
}

type User @resolver {
  id: ID!
  name: String
  email: String
  friends: [User] @resolver(isBatching: true)
}
```

**Use cases:**

- Fields that fetch data from external services or databases
- Fields that require custom business logic beyond simple property access
- Object types that need node resolution for Global ID support
- Fields or types that benefit from batch loading (`isBatching: true`)

When you apply `@resolver` to a field, Viaduct generates an abstract resolver class that you must implement. The generated class contains either a `resolve` method (default) or a `batchResolve` method (when `isBatching: true`) — never both. At startup, Viaduct validates that the `isBatching` flag in the schema matches the method your resolver implements. See [Resolvers](../resolvers/index.md) and [Batch Resolution](../resolvers/batch_resolution.md) for details.

### @backingData

Specifies the backing data class for a field, enabling type-safe data access in resolvers.

**Locations:** `FIELD_DEFINITION`

**Arguments:**

- `class: String!` — fully qualified name of the backing data class

**Example:**

```graphql
type User {
  profile: UserProfile @backingData(class: "com.example.data.UserProfileData")
}

type UserProfile {
  bio: String
  avatarUrl: String
}
```

### @namespaceType

Groups related fields under a dedicated type that acts as an organizational namespace on the root query type. The engine auto-resolves fields that return a namespace type — no resolver is needed for the namespace field itself.

**Locations:** `OBJECT`

**Example:**

```graphql
type Query {
  listings: Listings
}

type Listings @namespaceType {
  availableRoomTypes: [RoomType] @resolver
  pricing: ListingsPricing
}

type ListingsPricing @namespaceType {
  currencyOptions: [Currency] @resolver
}
```

See [Namespace Types](../namespace_types/index.md) for detailed documentation.

### @scope

Controls field and type visibility across different schema scopes. This is a repeatable directive that enables multi-tenant or multi-variant schemas.

**Locations:** `OBJECT`, `INTERFACE`, `UNION`, `ENUM`, `INPUT_OBJECT`, `FIELD_DEFINITION`, `ENUM_VALUE`

**Arguments:**

- `to: [String!]!` — list of scope names where this element is visible

**Example:**

```graphql
type User @scope(to: ["public"]) {
  id: ID!
  name: String!
  email: String @scope(to: ["internal"])
  adminNotes: String @scope(to: ["admin"])
}

type InternalMetrics @scope(to: ["internal"]) {
  requestCount: Long!
  errorRate: Float!
}
```

See [Scopes](../scopes/index.md) for detailed documentation on using scopes.

### @idOf

Declares that an `ID` field or argument represents a Global ID for a specific GraphQL type. When a field or argument has `@idOf`, Viaduct generates code using `GlobalID<T>` instead of `String` in the resolver signature. This enables type-safe ID handling.

**Locations:** `FIELD_DEFINITION`, `INPUT_FIELD_DEFINITION`, `ARGUMENT_DEFINITION`

**Arguments:**

- `type: String!` — name of the GraphQL type this ID references (must implement `Node`)

**Example:**

```graphql
type Query {
  user(id: ID! @idOf(type: "User")): User @resolver
  users(ids: [ID!]! @idOf(type: "User")): [User!]! @resolver
}

input UpdateUserInput {
  userId: ID! @idOf(type: "User")
  name: String
}
```

See [Global IDs](../globalids/index.md) for more information.

## Built-in Types

### Node Interface

The standard GraphQL Relay Node interface for entity identification. Viaduct automatically includes this interface when it's used in your schema.

**Definition:**

```graphql
interface Node @scope(to: ["*"]) {
  id: ID!
}
```

**When it's included:**

- Your schema implements types that extend `Node`
- You use the `@idOf` directive anywhere in your schema

**Example usage:**

```graphql
type User implements Node {
  id: ID!
  name: String!
}

type Post implements Node {
  id: ID!
  title: String!
  author: User @resolver
}
```

### Node Query Fields

Viaduct automatically provides these root query fields when your schema uses the `Node` interface:

```graphql
extend type Query @scope(to: ["*"]) {
  node(id: ID!): Node
  nodes(ids: [ID!]!): [Node]!
}
```

`Query.node` and `Query.nodes` come with built-in resolvers that work with Viaduct's Global ID system: based on the type embedded in a GlobalID, they will automatically call that type's node-resolver to obtain their results.

## Built-in Scalars

Viaduct provides extended scalar types beyond GraphQL's standard scalars (`Int`, `Float`, `String`, `Boolean`, `ID`). These are automatically available without explicit declaration.

### Date

ISO 8601 date format (YYYY-MM-DD).

**Example value:** `"2024-10-29"`

**Kotlin type mapping:** `java.time.LocalDate`

### DateTime

ISO 8601 date-time format with timezone.

**Example value:** `"2024-10-29T14:30:00Z"`

**Kotlin type mapping:** `java.time.Instant`

### Long

64-bit signed integer, beyond GraphQL's standard `Int` (32-bit).

**Example value:** `9223372036854775807`

**Kotlin type mapping:** `Long`

### BigDecimal

Arbitrary precision decimal number.

**Example value:** `"123.456789012345"`

**Kotlin type mapping:** `java.math.BigDecimal`

### BigInteger

Arbitrary precision integer.

**Example value:** `"12345678901234567890"`

**Kotlin type mapping:** `java.math.BigInteger`

### JSON

Generic JSON object type. Can represent any JSON structure.

**Example value:** `{"key": "value", "nested": {"count": 42}}`

**Kotlin type mapping:** `com.fasterxml.jackson.databind.JsonNode`

### BackingData

Special internal type used by the `@backingData` directive. Not typically used directly in schemas.

## Root Types

Viaduct intelligently manages root types based on your schema definitions:

### Query

**Always created.** Required by the GraphQL specification.

You must use `extend type Query` in your schema files:

```graphql
extend type Query {
  user(id: ID!): User @resolver
  users(limit: Int = 10): [User!]! @resolver
}
```

### Mutation

**Created only when mutation extensions exist.** Viaduct automatically creates the `Mutation` root type when it detects `extend type Mutation` in your schema.

```graphql
extend type Mutation {
  createUser(input: CreateUserInput!): CreateUserPayload @resolver
  updateUser(id: ID!, input: UpdateUserInput!): UpdateUserPayload @resolver
}
```

## Directive Summary

| Directive | Locations | Purpose | Generated Code Impact |
|-----------|-----------|---------|----------------------|
| `@resolver(isSelective: Boolean! = false, isBatching: Boolean! = false)` | FIELD_DEFINITION, OBJECT | Marks fields/types requiring custom resolution | Generates `resolve` (default) or `batchResolve` (`isBatching: true`) |
| `@backingData(class: String!)` | FIELD_DEFINITION | Specifies backing data class | Allows access to arbitrary class from GRTs |
| `@namespaceType` | OBJECT | Groups related fields under an organizational namespace | Engine auto-resolves the parent field |
| `@scope(to: [String!]!)` | OBJECT, INTERFACE, UNION, ENUM, INPUT_OBJECT, FIELD_DEFINITION, ENUM_VALUE | Controls visibility by scope (repeatable) | Affects schema filtering |
| `@idOf(type: String!)` | FIELD_DEFINITION, INPUT_FIELD_DEFINITION, ARGUMENT_DEFINITION | Declares Global ID type | Uses `GlobalID<T>` instead of `String` |

## Scalar Summary

| Scalar | Description | Kotlin Type | Example Value |
|--------|-------------|-------------|---------------|
| Date | ISO 8601 date | `java.time.LocalDate` | `"2024-10-29"` |
| DateTime | ISO 8601 date-time | `java.time.Instant` | `"2024-10-29T14:30:00Z"` |
| Long | 64-bit integer | `Long` | `9223372036854775807` |
| BigDecimal | Arbitrary precision decimal | `java.math.BigDecimal` | `"123.456789"` |
| BigInteger | Arbitrary precision integer | `java.math.BigInteger` | `"12345678901234567890"` |
| Object | JSON object | `JsonNode` | `{"key": "value"}` |
| Upload | File upload | Implementation-specific | (binary) |
| BackingData | Internal backing data ref | Internal | (internal) |

## Best Practices

### Do

- **Use `extend type` for all root types** — Never define `Query` or `Mutation` directly
- **Apply `@resolver` to fields fetching external data** — This is how Viaduct knows which fields need custom logic
- **Use `@idOf` for type-safe IDs** — Leverage compile-time validation for ID references
- **Apply `@scope` explicitly to sensitive fields** — Don't rely on implicit visibility
- **Leverage built-in scalars** — Use `DateTime`, `Long`, `BigDecimal` instead of strings or custom scalars
- **Implement Node interface for entities** — Use for globally identifiable objects

### Don't

- **Don't override core directives** — `@resolver`, `@backingData`, `@scope`, and `@idOf` are framework-provided
- **Don't redefine standard scalars** — They're automatically available
- **Don't manually add the Node interface** — It's added automatically when used
- **Don't forget to extend root types** — Always use `extend type Query`, not `type Query`

## See Also

- [Resolvers](../resolvers/index.md) — Implementing resolvers for fields marked with `@resolver`
- [Global IDs](../globalids/index.md) — Working with `@idOf` and the Node interface
- [Namespace Types](../namespace_types/index.md) — Organizing root fields with `@namespaceType`
- [Scopes](../scopes/index.md) — Advanced scope configuration with `@scope`
- [Service Engineers: Schema Extensions](../../service_engineers/schema_extensions/index.md) — Defining application-wide custom directives and types
- [Star Wars: Custom Directives](../../../getting_started/starwars/directives/index.md) — Examples from the Star Wars demo
