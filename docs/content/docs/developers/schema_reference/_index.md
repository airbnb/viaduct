---
title: Schema Reference
description: Viaduct's built-in directives, types, scalars, and schema components
weight: 50
---

Viaduct automatically provides a rich set of built-in schema components that are available in every application. This reference documents the directives, types, scalars, and other schema elements provided by the framework.

## Built-in Directives

Viaduct includes four core directives that are fundamental to the framework's functionality. These directives are automatically available and cannot be overridden.

### @resolver

Marks fields or types that require custom resolution logic. This is the primary mechanism for implementing data fetching in Viaduct.

**Locations:** `FIELD_DEFINITION`, `OBJECT`

**Example:**

```graphql
type Query {
  user(id: ID!): User @resolver
}

type User @resolver {
  id: ID!
  name: String
  email: String
}
```

**Use cases:**
- Fields that fetch data from external services or databases
- Fields that require custom business logic beyond simple property access
- Object types that need node resolution for Global ID support

When you apply `@resolver` to a field, Viaduct generates an abstract resolver class that you must implement. See [Resolvers](../resolvers/) for details.

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

**Use cases:**
- Mapping GraphQL types to internal data models
- Providing type information for fields derived from backing data
- Enabling Viaduct to automatically resolve fields from backing data without custom resolvers

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

**Use cases:**
- Creating public vs. internal API variants from the same codebase
- Feature flagging schema elements
- Multi-tenant schema visibility
- Gradual rollout of new features

See [Scopes](../scopes/) for detailed documentation on using scopes.

### @idOf

Declares that a field represents a Global ID for a specific GraphQL type. This enables type-safe ID handling.

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

**Use cases:**
- Type-safe Global ID handling in resolvers
- Node interface implementations
- Cross-type references with compile-time validation

When a field has `@idOf`, Viaduct generates code using `GlobalID<T>` instead of `String` in the resolver signature. See [Global IDs](../globalids/) for more information.

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
| `@resolver` | FIELD_DEFINITION, OBJECT | Marks fields/types requiring custom resolution | Generates abstract resolver classes |
| `@backingData(class: String!)` | FIELD_DEFINITION | Specifies backing data class | Enables automatic field resolution |
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
- **Don't use `@scope` as the only authorization mechanism** — Complement with application-level checks

## See Also

- [Resolvers](../resolvers/) — Implementing resolvers for fields marked with `@resolver`
- [Global IDs](../globalids/) — Working with `@idOf` and the Node interface
- [Scopes](../scopes/) — Advanced scope configuration with `@scope`
- [Service Engineers: Schema Extensions](../../service_engineers/schema_extensions/) — Defining application-wide custom directives and types
- [Getting Started: Custom Directives](../../getting_started/starwars/directives/) — Examples from the Star Wars demo
