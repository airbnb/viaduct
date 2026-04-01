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
  edges: [UserEdge]
  pageInfo: PageInfo!
  totalCount: Int  # Optional additional fields
}
```

A type with `@connection` must:

- Have a name ending in `Connection`
- Have an `edges` field with type `[<EdgeType>]` (nullability is flexible) where the edge type has the `@edge` directive
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

Viaduct automatically provides the `PageInfo` type — you do not need to define it. For details on how `PageInfo` is managed and validated, see [Schema Extensions: PageInfo](https://viaduct.airbnb.tech/docs/service_engineers/schema_extensions/#pageinfo).

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
    O : Object,
    Q : Query,
    A : ConnectionArguments,
    R : Connection<*, *>,
> : FieldExecutionContext<O, Q, A, R>
```

This provides type-safe access to pagination arguments and ensures compatibility with builder utilities.

## Building Connections

Connection GRT builders extend `ConnectionBuilder`, which provides utilities for common pagination scenarios.

### From Edges (Native Cursors or Edge Metadata)

Use `fromEdges()` when you need full control over edge construction. This is the right choice in two situations:

**1. Backend returns native cursors:**

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

**2. Edge type carries metadata fields beyond `node` and `cursor`:**

When your edge has extra fields (such as `role`, `reason`, `score`), you must build the edges manually using `fromEdges()` since `fromSlice()` and `fromList()` only build the node. Use `OffsetCursor.fromOffset()` to encode cursors for offset/limit backends:

```kotlin
@Resolver
class UsersByRoleResolver : QueryUsersByRoleResolver() {
    override suspend fun resolve(ctx: Context): UserConnection {
        val (offset, limit) = ctx.arguments.toOffsetLimit()
        val fetched = userService.getUsers(offset, limit + 1)
        val page = fetched.take(limit)

        return UserConnection.Builder(ctx)
            .fromEdges(
                edges = page.mapIndexed { idx, user ->
                    UserEdge.Builder(ctx)
                        .node(ctx.nodeFor(user.id))
                        .cursor(OffsetCursor.fromOffset(offset + idx).value)
                        .role(user.role)
                        .build()
                },
                hasNextPage = fetched.size > limit,
                hasPreviousPage = offset > 0
            )
            .build()
    }
}
```

### From Slice (Offset/Limit, No Edge Metadata)

When your backend uses offset/limit and your edge type has no extra fields beyond `node` and `cursor`, use `fromSlice()`. It handles cursor encoding automatically:

```kotlin
@Resolver
class UsersResolver : QueryUsersResolver() {
    override suspend fun resolve(ctx: Context): UserConnection {
        val (offset, limit) = ctx.arguments.toOffsetLimit()
        val fetched = userService.getUsers(offset, limit + 1)

        return UserConnection.Builder(ctx)
            .fromSlice(
                items = fetched,
                hasNextPage = fetched.size > limit
            ) { user ->
                ctx.nodeFor(user.id)
            }
            .build()
    }
}
```

The `fromSlice()` method:

1. Trims `items` to at most `limit` entries (the extra item is only for `hasNextPage` detection)
2. Builds edges with automatically encoded offset cursors (offset + index)
3. Sets `pageInfo` with correct `hasNextPage` and `hasPreviousPage` values

!!! note
    `fromSlice()` only builds the node value per edge. If your edge type has additional fields, use `fromEdges()` instead.

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
            .build()
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

## Cursors

Cursors are opaque strings that identify a position in a paginated list.

### Offset Cursors

For offset/limit backends, Viaduct provides `viaduct.api.connection.OffsetCursor`:

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
  edges: [MemberEdge]
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
class OrganizationMembersResolver : OrganizationResolvers.Members() {
  @Inject lateinit var memberService: MemberService

  override suspend fun resolve(ctx: Context): MemberConnection {
    val orgId = ctx.objectValue.getId()
    val (offset, limit) = ctx.arguments.toOffsetLimit()

    val response = memberService.getMembers(
      organizationId = orgId.internalID,
      offset = offset,
      limit = limit + 1
    )

    val page = response.members.take(limit)
    return MemberConnection.Builder(ctx)
      .fromEdges(
        edges = page.mapIndexed { idx, member ->
          MemberEdge.Builder(ctx)
            .node(ctx.nodeFor(member.userId))
            .cursor(OffsetCursor.fromOffset(offset + idx).value)
            .role(member.role)
            .joinedAt(member.joinedAt)
            .build()
        },
        hasNextPage = response.members.size > limit,
        hasPreviousPage = offset > 0
      )
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

| Situation | Method | Notes |
|-----------|--------|-------|
| Backend returns native cursors | `fromEdges()` | Pass through backend cursors directly |
| Offset/limit backend, edge has extra fields | `fromEdges()` | Build edges manually with `OffsetCursor.fromOffset()` |
| Offset/limit backend, no extra edge fields | `fromSlice()` | Cursor encoding handled automatically |
| Full in-memory list | `fromList()` | Slicing and cursor encoding handled automatically |

## Testing

Connection resolvers are tested like any other field resolver. Use `FieldResolverTester` with the generated connection arguments type:

```kotlin
class MyConnectionResolverTest {
  private val tester = FieldResolverTester.create<
      Query,                    // Object type (Query for root fields)
      Query,                    // Query type
      Users_Arguments,          // Must implement ConnectionArguments
      UserConnection            // Return type
      >(TesterConfig(schemaSDL = SCHEMA_SDL))

  @Test
  fun `first page returns correct edges`() = runBlocking {
    val result = tester.test(UsersResolver()) {
      objectValue = NullObject
      arguments = Users_Arguments.Builder(tester.context).first(3).build()
    }
    assertEquals(3, result.getEdges().size)
  }
}
```

See the [resolver testing guide](../resolvers/index.md) for full testing documentation.

## Best Practices

- **Fetch limit + 1** items to efficiently determine `hasNextPage` without a count query
- **Trim to `limit` before building edges for `fromEdges()`** — over-fetching is only for detecting the next page
- **Include `totalCount`** on the connection type when it is useful for UI pagination controls
- **Set reasonable defaults** for page size (typically 10–50 items) via `toOffsetLimit(defaultPageSize = N)`
- **Keep cursors opaque** — don't parse or construct `OffsetCursor` values in client code
- **Use `fromEdges()` for edge metadata** — if your edge type has any field beyond `node` and `cursor`, `fromSlice()` and `fromList()` cannot populate those fields
