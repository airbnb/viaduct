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

Viaduct automatically provides the `PageInfo` type — you do not need to define it. For details on how `PageInfo` is managed and validated, see [Schema Extensions: PageInfo](../../service_engineers/schema_extensions/index.md#pageinfo).

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

Generated GRTs implement pagination interfaces from `viaduct.api.types` in Kotlin or `viaduct.java.api.types` in Java. The simplified signatures below show their shape; the Java types use nullable `Integer` and `String` arguments. Java pagination APIs are marked `@ExperimentalApi`.

### Connection and Edge

=== "Kotlin"

    ```kotlin
    interface Connection<E : Edge<N>, N> : Object

    interface Edge<N> : Object
    ```

=== "Java"

    ```java
    // In viaduct.java.api.types:
    public interface Connection<E extends Edge<N>, N> extends GraphQLObject {}
    public interface Edge<N> extends GraphQLObject {}
    ```


### Pagination Arguments

=== "Kotlin"

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

=== "Java"

    ```java
    public interface ForwardConnectionArguments extends ConnectionArguments {
      Integer getFirst();
      String getAfter();
    }

    public interface BackwardConnectionArguments extends ConnectionArguments {
      Integer getLast();
      String getBefore();
    }

    // MultidirectionalConnectionArguments implements both interfaces
    // and reconciles their default methods.
    ```


Generated argument types implement the appropriate interface based on which pagination arguments the field accepts.

### Execution Context

Connection field resolvers receive a {{ kdoc("viaduct.api.context.ConnectionFieldExecutionContext") }} in Kotlin or a [`viaduct.java.api.context.ConnectionFieldExecutionContext`](https://github.com/airbnb/viaduct/blob/main/core/javaapi/api/src/main/java/viaduct/java/api/context/ConnectionFieldExecutionContext.java) in Java:

=== "Kotlin"

    ```kotlin
    interface ConnectionFieldExecutionContext<
        O : Object,
        Q : Query,
        A : ConnectionArguments,
        R : Connection<*, *>,
    > : FieldExecutionContext<O, Q, A, R>
    ```

=== "Java"

    ```java
    // In viaduct.java.api.context:
    public interface ConnectionFieldExecutionContext<
        O extends GraphQLObject,
        Q extends Query,
        A extends ConnectionArguments,
        R extends Connection<?, ?>>
        extends FieldExecutionContext<O, Q, A, R> {}
    ```


This provides type-safe access to pagination arguments and ensures compatibility with builder utilities.

## Building Connections

Connection GRT builders extend `ConnectionBuilder`, which provides utilities for common pagination scenarios. Java uses `Type.builder(ctx)` and positional method arguments. The resolver examples use `java.util.concurrent.CompletableFuture`, `java.util.List` / `ArrayList`, `viaduct.java.api.annotations.Resolver`, and pagination types from `viaduct.java.api.types`. Repository calls shown here are synchronous; compose futures if your repository is asynchronous.

### From Edges (Native Cursors or Edge Metadata)

Use `fromEdges()` when you need full control over edge construction. This is the right choice in two situations:

**1. Backend returns native cursors:**

=== "Kotlin"

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
                            .node(ctx.ref(user.id))
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

=== "Java"

    ```java
    @Resolver
    public final class UsersResolver extends QueryResolvers.Users {
      private final UserService userService;

      public UsersResolver(UserService userService) {
        this.userService = userService;
      }

      @Override
      public CompletableFuture<UserConnection> resolve(Context ctx) {
        var args = ctx.getArguments();
        var response = userService.getUsers(
            args.getAfter(), args.getFirst() == null ? 20 : args.getFirst());
        var edges = response.users().stream()
            .map(user -> UserEdge.builder(ctx)
                .node(ctx.ref(user.id()))
                .cursor(user.cursor())
                .build())
            .toList();
        return CompletableFuture.completedFuture(
            UserConnection.builder(ctx)
                .fromEdges(edges, response.hasMore(), response.hasPrevious())
                .build());
      }
    }
    ```


**2. Edge type carries metadata fields beyond `node` and `cursor`:**

When your edge has extra fields (such as `role`, `reason`, `score`), you must build the edges manually using `fromEdges()` since `fromSlice()` and `fromList()` only build the node. Use `OffsetCursor.fromOffset()` to encode cursors for offset/limit backends:

=== "Kotlin"

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
                            .node(ctx.ref(user.id))
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

=== "Java"

    ```java
    @Resolver
    public final class UsersByRoleResolver extends QueryResolvers.UsersByRole {
      private final UserService userService;

      public UsersByRoleResolver(UserService userService) {
        this.userService = userService;
      }

      @Override
      public CompletableFuture<UserConnection> resolve(Context ctx) {
        OffsetLimit bounds = ctx.getArguments().toOffsetLimit();
        var fetched = userService.getUsers(bounds.offset(), bounds.limit() + 1);
        List<UserEdge> edges = new ArrayList<>();
        int size = Math.min(bounds.limit(), fetched.size());
        for (int index = 0; index < size; index++) {
          var user = fetched.get(index);
          edges.add(UserEdge.builder(ctx)
              .node(ctx.ref(user.id()))
              .cursor(OffsetCursor.fromOffset(bounds.offset() + index).getValue())
              .role(user.role())
              .build());
        }
        return CompletableFuture.completedFuture(
            UserConnection.builder(ctx)
                .fromEdges(edges, fetched.size() > bounds.limit(), bounds.offset() > 0)
                .build());
      }
    }
    ```


### From Slice (Offset/Limit, No Edge Metadata)

When your backend uses offset/limit and your edge type has no extra fields beyond `node` and `cursor`, use `fromSlice()`. It handles cursor encoding automatically:

=== "Kotlin"

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
                    ctx.ref(user.id)
                }
                .build()
        }
    }
    ```

=== "Java"

    ```java
    @Resolver
    public final class UsersResolver extends QueryResolvers.Users {
      private final UserService userService;

      public UsersResolver(UserService userService) {
        this.userService = userService;
      }

      @Override
      public CompletableFuture<UserConnection> resolve(Context ctx) {
        OffsetLimit bounds = ctx.getArguments().toOffsetLimit();
        var fetched = userService.getUsers(bounds.offset(), bounds.limit() + 1);
        return CompletableFuture.completedFuture(
            UserConnection.builder(ctx)
                .fromSlice(fetched, bounds, fetched.size() > bounds.limit(), user -> ctx.ref(user.id()))
                .build());
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

=== "Kotlin"

    ```kotlin
    @Resolver
    class UsersResolver : QueryUsersResolver() {
        override suspend fun resolve(ctx: Context): UserConnection {
            val allUsers = userService.getAllUsers()

            return UserConnection.Builder(ctx)
                .fromList(allUsers) { user ->
                    ctx.ref(user.id)
                }
                .build()
        }
    }
    ```

=== "Java"

    ```java
    @Resolver
    public final class UsersResolver extends QueryResolvers.Users {
      private final UserService userService;

      public UsersResolver(UserService userService) {
        this.userService = userService;
      }

      @Override
      public CompletableFuture<UserConnection> resolve(Context ctx) {
        var allUsers = userService.getAllUsers();
        return CompletableFuture.completedFuture(
            UserConnection.builder(ctx).fromList(allUsers, user -> ctx.ref(user.id())).build());
      }
    }
    ```


`fromList()` handles backward pagination (`last` without `before`) automatically. When `toOffsetLimit()` returns a negative offset (the signal for "take from the tail"), `fromList()` resolves it against the list size — e.g. an offset of `-3` on a 10-item list becomes offset `7`, returning the last 3 items.

## Converting Arguments to Offset/Limit

Use `toOffsetLimit()` to convert cursor-based arguments. Kotlin provides an extension function; Java defines a method on the generated arguments interface:

=== "Kotlin"

    ```kotlin
    val (offset, limit) = ctx.arguments.toOffsetLimit()
    ```

=== "Java"

    ```java
    OffsetLimit bounds = ctx.getArguments().toOffsetLimit();
    int offset = bounds.offset();
    int limit = bounds.limit();
    ```


**Valid argument combinations:**

| Arguments | Result |
|-----------|--------|
| None | First page with default size |
| `first` | First N items |
| `first`, `after` | N items after cursor |
| `after` only | Default page size after cursor |
| `last` | Last N items (requires total count — see below) |
| `last`, `before` | Last N items before cursor |
| `before` only | Default page size before cursor |

**Validation:**

- `first` and `last` must be > 0 if specified
- `after` and `before` must be valid, decodable cursors

### Backward Pagination

When only `last` is specified (no `before` cursor), `toOffsetLimit()` returns a negative offset as a signal — the absolute value is the page size, and `fromList()` resolves it against the list size automatically.

For `fromSlice()` or `fromEdges()`, you need the real offset up front. Use `requiresTotalCountForOffsetLimit()` to check whether a total count is needed. Kotlin uses `toOffsetLimit(totalCount = total)`; Java requires **two** arguments, `toOffsetLimit(totalCount, defaultPageSize)`. In Java, the one-argument overload sets the default page size; it does not supply a total count. Pass the resulting bounds to Java `fromSlice(items, bounds, hasNextPage, buildNode)` so it uses the same offset as your backend request:

=== "Kotlin"

    ```kotlin
    val (offset, limit) = if (ctx.arguments.requiresTotalCountForOffsetLimit()) {
        val total = myService.count()
        ctx.arguments.toOffsetLimit(totalCount = total)
    } else {
        ctx.arguments.toOffsetLimit()
    }
    val fetched = myService.getItems(offset, limit + 1)
    ```

=== "Java"

    ```java
    OffsetLimit bounds = ctx.getArguments().requiresTotalCountForOffsetLimit()
        ? ctx.getArguments().toOffsetLimit(myService.count(), ConnectionArguments.DEFAULT_PAGE_SIZE)
        : ctx.getArguments().toOffsetLimit();
    var fetched = myService.getItems(bounds.offset(), bounds.limit() + 1);

    // Pass these same bounds to fromSlice when the offset depends on total count.
    return UserConnection.builder(ctx)
        .fromSlice(fetched, bounds, fetched.size() > bounds.limit(), item -> ctx.ref(item.id()))
        .build();
    ```


## Cursors

Cursors are opaque strings that identify a position in a paginated list.

### Offset Cursors

For offset/limit backends, Viaduct provides `viaduct.api.types.OffsetCursor` in Kotlin and `viaduct.java.api.types.OffsetCursor` in Java:

=== "Kotlin"

    ```kotlin
    @JvmInline
    value class OffsetCursor(val value: String) {
      fun toOffset(): Int

      companion object {
        fun fromOffset(offset: Int): OffsetCursor
      }
    }
    ```

=== "Java"

    ```java
    String cursor = OffsetCursor.fromOffset(10).getValue();
    int offset = new OffsetCursor(cursor).toOffset();
    ```


Cursors are serialized in a format intentionally opaque to clients.

### Cursor Stability

Offset-based cursors are best-effort and may produce duplicate or skipped results when underlying data changes between requests. For strict cursor stability, use backend-native cursors.

## Complete Example

**Schema:**

```graphqls
type Organization implements Node {
  id: ID!
  name: String!
  members(first: Int, after: String): MemberConnection! @resolver
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

=== "Kotlin"

    ```kotlin
    @Resolver
    class OrganizationMembersResolver : OrganizationResolvers.Members() {
      @Inject lateinit var memberService: MemberService

      override suspend fun resolve(ctx: Context): MemberConnection {
        val orgId = ctx.getObjectValue().getIdOrThrow()
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
                .node(ctx.ref(member.userId))
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

=== "Java"

    ```java
    @Resolver(objectValueFragment = "id")
    public final class OrganizationMembersResolver extends OrganizationResolvers.Members {
      private final MemberService memberService;

      public OrganizationMembersResolver(MemberService memberService) {
        this.memberService = memberService;
      }

      @Override
      public CompletableFuture<MemberConnection> resolve(Context ctx) {
        var orgId = ctx.getObjectValue().getId();
        OffsetLimit bounds = ctx.getArguments().toOffsetLimit();
        var response = memberService.getMembers(
            orgId.getInternalID(), bounds.offset(), bounds.limit() + 1);
        int size = Math.min(bounds.limit(), response.members().size());
        List<MemberEdge> edges = new ArrayList<>();
        for (int index = 0; index < size; index++) {
          var member = response.members().get(index);
          edges.add(MemberEdge.builder(ctx)
              .node(ctx.ref(member.userId()))
              .cursor(OffsetCursor.fromOffset(bounds.offset() + index).getValue())
              .role(member.role())
              .joinedAt(member.joinedAt())
              .build());
        }
        return CompletableFuture.completedFuture(
            MemberConnection.builder(ctx)
                .fromEdges(edges, response.members().size() > bounds.limit(), bounds.offset() > 0)
                .totalCount(response.totalCount())
                .build());
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

Connection resolvers are tested like other field resolvers. Kotlin can extend `DefaultAbstractResolverTestBase`. Java's isolated `ResolverTestBase` supports resolvers that build concrete GRTs, but the `UsersResolver` examples above create node references, which that harness does not implement. The Java example therefore executes through a real `Viaduct` instance, configured with the users query and its node resolvers and a service returning at least three users. Its Base schema must expose the query; pass your registered schema ID instead if using scoped views.

=== "Kotlin"

    ```kotlin
    @OptIn(ExperimentalApi::class)
    class MyConnectionResolverTest : DefaultAbstractResolverTestBase() {
      override fun getSchema() = mySchema

      @Test
      fun `first page returns correct edges`() = runBlocking {
        val result = runFieldResolver(
          resolver = UsersResolver(),
          arguments = Users_Arguments.Builder(context).first(3).build()
        )
        assertEquals(3, result.getEdgesOrThrow().size)
      }
    }
    ```

=== "Java"

    ```java
    @Test
    void firstPageReturnsCorrectEdges() {
      var input = ExecutionInput.create("query { users(first: 3) { edges { cursor } } }");
      var result = viaduct.executeAsync(input).join();
      assertThat(result.getErrors()).isEmpty();
      Map<?, ?> connection = (Map<?, ?>) result.getData().get("users");
      assertThat((List<?>) connection.get("edges")).hasSize(3);
    }
    ```


!!! note
    `DefaultAbstractResolverTestBase` and `runFieldResolver` are annotated `@ExperimentalApi`. Opt in with `@OptIn(ExperimentalApi::class)` on your test class or file.

See the [resolver testing guide](../testing/index.md) for harness setup. Use engine or application tests if the resolver creates node references, because the isolated Java harness does not implement `ctx.ref`. The [Java Star Wars integration test](https://github.com/airbnb/viaduct/blob/main/demoapps/starwars-java/src/test/java/com/example/starwars/service/StarWarsJavaIntegrationTest.java) exercises forward, backward, and adjacent connection pages.

## Best Practices

- **Fetch limit + 1** items to efficiently determine `hasNextPage` without a count query
- **Trim to `limit` before building edges for `fromEdges()`** — over-fetching is only for detecting the next page
- **Include `totalCount`** on the connection type when it is useful for UI pagination controls
- **Set reasonable defaults** for page size (typically 10–50 items) via Kotlin `toOffsetLimit(defaultPageSize = N)` or Java `toOffsetLimit(N)`
- **Keep cursors opaque** — don't parse or construct `OffsetCursor` values in client code
- **Use `fromEdges()` for edge metadata** — if your edge type has any field beyond `node` and `cursor`, `fromSlice()` and `fromList()` cannot populate those fields
- **Handle `last` without `before`** — call `requiresTotalCountForOffsetLimit()` first; if it returns `true`, use Kotlin `toOffsetLimit(totalCount = totalCount)` or Java `toOffsetLimit(totalCount, defaultPageSize)`. With `fromList()` this is handled automatically.

For complete Java implementations, see [AllCharactersConnectionQueryResolver](https://github.com/airbnb/viaduct/blob/main/demoapps/starwars-java/modules/filmography/src/main/java/com/example/starwars/modules/filmography/characters/queries/AllCharactersConnectionQueryResolver.java) (`fromList`) and [AllSpeciesConnectionQueryResolver](https://github.com/airbnb/viaduct/blob/main/demoapps/starwars-java/modules/universe/src/main/java/com/example/starwars/modules/universe/species/queries/AllSpeciesConnectionQueryResolver.java) (`fromEdges`, including `last` without `before`).
