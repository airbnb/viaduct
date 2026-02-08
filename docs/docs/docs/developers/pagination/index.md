---
title: Pagination
description: Cursor-based pagination using Relay Connections
---

Pagination is essential for efficiently querying large datasets. Viaduct implements the [Relay Connection specification](https://relay.dev/graphql/connections.htm), which provides a standardized, cursor-based pagination model for GraphQL.

## Why Relay Connections?

- **Cursor-based**: More stable than offset pagination when data changes
- **Bidirectional**: Supports both forward and backward traversal
- **Standardized**: Well-understood pattern across the GraphQL ecosystem
- **Rich metadata**: Provides page information for UI pagination controls

## Connections

A **Connection** represents a paginated list of items. It contains edges (the items with their cursors) and page information.

### Connection Type

Use the `@connection` directive to define a connection type:

```graphqls
type UserConnection @connection {
  edges: [UserEdge!]!
  pageInfo: PageInfo!
  totalCount: Int  # Optional additional fields
}
```

A type with `@connection` must:

- Have a name ending in `Connection`
- Have an `edges` field with type `[<EdgeType>!]!` where the edge type has the `@edge` directive
- Have a `pageInfo: PageInfo!` field

### Edge Type

An **Edge** wraps a node and provides its cursor for pagination. Use the `@edge` directive:

```graphqls
type UserEdge @edge {
  node: User!
  cursor: String!
  role: String  # Optional additional fields
}
```

A type with `@edge` must have:

- A `node` field (any scalar, enum, object, interface, or union—not a list)
- A `cursor: String!` field

### PageInfo

`PageInfo` is a built-in type that provides pagination metadata per the [Relay Connection specification](https://relay.dev/graphql/connections.htm){:target="_blank"}:

```graphqls
type PageInfo {
  hasNextPage: Boolean!
  hasPreviousPage: Boolean!
  startCursor: String
  endCursor: String
}
```

#### Automatic PageInfo Handling

Viaduct automatically manages the `PageInfo` type in your schema:

- **If PageInfo doesn't exist**: Viaduct creates it with the standard Relay fields shown above
- **If PageInfo already exists**: Viaduct validates it conforms exactly to the Relay specification

This ensures all connection types have access to a compliant `PageInfo` type without requiring manual definition.

#### PageInfo Validation

When a custom `PageInfo` type is defined, it must match the Relay specification exactly:

- **Required fields only**: Only the four standard fields (`hasNextPage`, `hasPreviousPage`, `startCursor`, `endCursor`) are allowed
- **No custom fields**: Additional fields such as `totalCount` are not permitted
- **No custom directives**: Directives on the `PageInfo` type or its fields are not allowed

**Field requirements:**

| Field | Type | Nullability |
|-------|------|-----------|
| `hasNextPage` | `Boolean` | Non-nullable (`!`) required |
| `hasPreviousPage` | `Boolean` | Non-nullable (`!`) required |
| `startCursor` | `String` | Nullable |
| `endCursor` | `String` | Nullable |

Non-conforming `PageInfo` types result in a validation error:

```
PageInfo type does not conform to Relay Connection specification:
  - Missing required field 'hasPreviousPage'
  - Field 'hasNextPage' must be non-nullable (Boolean!)
  - PageInfo type cannot have custom fields. Found extra fields: 'totalCount'.
  - PageInfo type cannot have custom directives. Found directives: @deprecated.
```

### Connection Field Arguments

Fields returning a connection type must include pagination arguments:

```graphqls
type Query {
  # Forward pagination
  users(first: Int, after: String): UserConnection!

  # Backward pagination
  recentUsers(last: Int, before: String): UserConnection!

  # Bidirectional pagination
  allUsers(first: Int, after: String, last: Int, before: String): UserConnection!
}
```

| Argument | Direction | Description |
|----------|-----------|-------------|
| `first` | Forward | Number of items from the start |
| `after` | Forward | Cursor to start after (exclusive) |
| `last` | Backward | Number of items from the end |
| `before` | Backward | Cursor to end before (exclusive) |

## Generated Interfaces

Viaduct generates Kotlin interfaces in `viaduct.api.types` that your GRTs implement:

### Connection and Edge

```kotlin
interface Connection<E : Edge<N>, N> : Object

interface Edge<N> : Object
```

### Pagination Arguments

```kotlin
interface ConnectionArguments : Arguments

interface ForwardConnectionArguments : ConnectionArguments {
    val first: Int?
    val after: String?
}

interface BackwardConnectionArguments : ConnectionArguments {
    val last: Int?
    val before: String?
}

interface MultidirectionalConnectionArguments
    : ForwardConnectionArguments, BackwardConnectionArguments
```

Generated argument types implement the appropriate interface based on which pagination arguments the field accepts.

### Execution Context

Connection field resolvers receive a {{ kdoc("viaduct.api.context.ConnectionFieldExecutionContext") }}:

```kotlin
interface ConnectionFieldExecutionContext<
    T : Object,
    Q : Query,
    A : ConnectionArguments,
    O : Connection<*, *>,
> : FieldExecutionContext<T, Q, A, O>
```

This provides type-safe access to pagination arguments and ensures compatibility with builder utilities.

## Building Connections

Connection GRT builders extend `ConnectionBuilder`, which provides utilities for common pagination scenarios.

### From Edges (Native Cursors)

When your backend natively supports cursor-based pagination, use `fromEdges()`:

```kotlin
@Resolver
class UsersResolver : QueryUsersResolver() {
    override suspend fun resolve(ctx: Context): UserConnection {
        val response = userService.getUsers(
            cursor = ctx.arguments.after,
            limit = ctx.arguments.first ?: 20
        )

        return UserConnection.Builder(ctx)
            .fromEdges(
                edges = response.users.map { user ->
                    UserEdge.Builder(ctx)
                        .node(ctx.nodeFor(user.id))
                        .cursor(user.cursor)
                        .build()
                },
                hasNextPage = response.hasMore,
                hasPreviousPage = response.hasPrevious
            )
            .build()
    }
}
```

### From Slice (Offset/Limit)

When your backend uses offset/limit pagination, use `fromSlice()`. This automatically encodes offsets as cursors:

```kotlin
@Resolver
class UsersResolver : QueryUsersResolver() {
    override suspend fun resolve(ctx: Context): UserConnection {
        val (offset, limit) = ctx.arguments.toOffsetLimit()
        val response = userService.getUsers(offset, limit + 1)

        return UserConnection.Builder(ctx)
            .fromSlice(
                items = response.users,
                hasNextPage = response.users.size > limit
            ) { user ->
                ctx.nodeFor(user.id)
            }
            .totalCount(response.totalCount)
            .build()
    }
}
```

The `fromSlice()` method:

1. Converts pagination arguments to offset/limit via `toOffsetLimit()`
2. Builds edges with automatically encoded offset cursors
3. Sets `pageInfo` with correct `hasNextPage` and `hasPreviousPage` values

### From List (Full Data)

When your backend returns the complete dataset and you want Viaduct to handle slicing:

```kotlin
@Resolver
class UsersResolver : QueryUsersResolver() {
    override suspend fun resolve(ctx: Context): UserConnection {
        val allUsers = userService.getAllUsers()

        return UserConnection.Builder(ctx)
            .fromList(allUsers) { user ->
                ctx.nodeFor(user.id)
            }
    }
}
```

## Converting Arguments to Offset/Limit

Use the `toOffsetLimit()` extension function to convert cursor-based arguments:

```kotlin
val (offset, limit) = ctx.arguments.toOffsetLimit()
```

**Valid argument combinations:**

| Arguments | Result |
|-----------|--------|
| None | First page with default size |
| `first` | First N items |
| `first`, `after` | N items after cursor |
| `after` only | Default page size after cursor |
| `last`, `before` | Last N items before cursor |
| `before` only | Default page size before cursor |

**Validation:**

- `first` and `last` must be > 0 if specified
- `after` and `before` must be valid, decodable cursors

### Backward Pagination with Total Count

When only `last` is specified (without `before`), the total count is needed:

```kotlin
if (ctx.arguments.requiresTotalCountForOffsetLimit()) {
    val totalCount = userService.getUserCount()
    val (offset, limit) = ctx.arguments.toOffsetLimit(totalCount)
}
```

## Cursors

Cursors are opaque strings that identify a position in a paginated list.

### Offset Cursors

!!! note
    This feature is still in development.

For offset/limit backends, Viaduct provides `viaduct.api.types.OffsetCursor`:

```kotlin
@JvmInline
value class OffsetCursor(val value: String) {
    fun toOffset(): Int

    companion object {
        fun fromOffset(offset: Int): OffsetCursor
    }
}
```

Cursors are encoded as Base64 strings. The format is opaque to clients.

### Cursor Stability

Offset-based cursors are best-effort and may produce duplicate or skipped results when underlying data changes between requests. For strict cursor stability, use backend-native cursors.

## Complete Example

**Schema:**

```graphqls
type Organization implements Node {
  id: ID!
  name: String!
  members(first: Int, after: String): MemberConnection!
}

type MemberConnection @connection {
  edges: [MemberEdge!]!
  pageInfo: PageInfo!
  totalCount: Int!
}

type MemberEdge @edge {
  node: User!
  cursor: String!
  role: MemberRole!
  joinedAt: DateTime!
}

enum MemberRole {
  ADMIN
  MEMBER
  VIEWER
}
```

**Resolver:**

```kotlin
@Resolver
class OrganizationMembersResolver : OrganizationMembersFieldResolver() {
    @Inject lateinit var memberService: MemberService

    override suspend fun resolve(ctx: Context): MemberConnection {
        val orgId = ctx.source.id
        val (offset, limit) = ctx.arguments.toOffsetLimit()

        val response = memberService.getMembers(
            organizationId = orgId.internalID,
            offset = offset,
            limit = limit + 1
        )

        return MemberConnection.Builder(ctx)
            .fromSlice(
                items = response.members,
                hasNextPage = response.members.size > limit
            ) { member ->
                MemberEdge.Builder(ctx)
                    .node(ctx.nodeFor(member.userId))
                    .role(member.role)
                    .joinedAt(member.joinedAt)
                    .build()
            }
            .totalCount(response.totalCount)
            .build()
    }
}
```

**Query:**

```graphql
query {
  organization(id: "org123") {
    name
    members(first: 10) {
      edges {
        node {
          id
          name
        }
        role
        joinedAt
        cursor
      }
      pageInfo {
        hasNextPage
        endCursor
      }
      totalCount
    }
  }
}
```

## Choosing an Approach

| Backend Support | Method | Notes |
|----------------|--------|-------|
| Native cursors | `fromEdges()` | Pass through backend cursors directly |
| Offset/limit | `fromSlice()` | Encodes offsets as cursors automatically |
| Full list | `fromList()` | Handles slicing and cursor encoding |

## Best Practices

- **Fetch limit + 1** items to efficiently determine `hasNextPage`
- **Include `totalCount`** when available for UI pagination controls
- **Set reasonable defaults** for page size (typically 10-50 items)
- **Keep cursors opaque**—don't expose internal format to clients
- **Use `fromSlice()`** for offset/limit backends to get automatic cursor handling
