# Viaduct Query Resolver Pattern

## ⛔ BEFORE IMPLEMENTING: Check Schema for @idOf

**First, check if the query argument has `@idOf`.** If missing, add it:

```graphql
# ❌ BROKEN - Missing @idOf
extend type Query {
  project(id: ID!): Project @resolver
}

# ✅ FIXED - Add @idOf
extend type Query {
  project(id: ID! @idOf(type: "Project")): Project @resolver
}
```

## Why @idOf Matters

Without `@idOf`, `ctx.arguments.id` returns the raw base64 GlobalID string (e.g., `"UHJvamVjdDo1NTBlODQwMC4uLg=="`), NOT the actual UUID.

```kotlin
// ❌ BUG - gets base64 string, not UUID
val projectId = ctx.arguments.id  // "UHJvamVjdDo1NTBlODQwMC4uLg=="

// ✅ CORRECT - extracts actual UUID
val projectId = ctx.arguments.id.internalID  // "550e8400-e29b-41d4-a716-446655440000"
```

**This causes real bugs:** Database lookups fail and response IDs get corrupted.

## Schema Pattern

```graphql
extend type Query @scope(to: ["default"]) {
  user(id: ID! @idOf(type: "User")): User @resolver
  users: [User!]! @resolver
}
```

## Resolver Implementation

```kotlin
package com.viaduct.resolvers

import com.viaduct.resolvers.resolverbases.QueryResolvers

@Resolver
class UserQueryResolver : QueryResolvers.User() {

    override suspend fun resolve(ctx: Context): User? {
        val userId = ctx.arguments.id.internalID  // @idOf makes this GlobalID<User>

        // TODO: Fetch user from database
        // val data = fetchUser(userId) ?: return null

        return User.Builder(ctx)
            .id(ctx.globalIDFor(User.Reflection, userId))
            .email("placeholder@example.com")
            .build()
    }
}
```

## Key Patterns

| Pattern | Purpose |
|---------|---------|
| `ctx.arguments.id.internalID` | Extract UUID from GlobalID argument |
| `ctx.globalIDFor(Type.Reflection, id)` | Create GlobalID for response |
| `QueryResolvers.FieldName()` | Base class to extend |

## List Query Example

```kotlin
@Resolver
class UsersQueryResolver : QueryResolvers.Users() {

    override suspend fun resolve(ctx: Context): List<User> {
        // TODO: Fetch all users
        // val users = fetchUsers()

        return emptyList()
    }
}
```
