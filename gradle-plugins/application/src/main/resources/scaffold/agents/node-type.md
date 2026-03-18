# Viaduct Node Type Pattern

**⚠️ CRITICAL:** Types implementing Node MUST have `@resolver` directive to generate `NodeResolvers.TypeName`:

```graphql
type User implements Node @resolver @scope(to: ["default"]) {
  id: ID!                      # Required for Node
  email: String
  displayName: String @resolver
}
```

**Without `@resolver` on the type, `NodeResolvers.User` won't exist and your resolver won't compile.**

## NodeResolver Implementation

```kotlin
package com.viaduct.resolvers

import com.viaduct.resolvers.NodeResolvers  // Note: NodeResolvers is NOT in resolverbases!

@Resolver
class UserNodeResolver : NodeResolvers.User() {

    override suspend fun resolve(ctx: Context): User {
        val userId = ctx.id.internalID  // GlobalID -> UUID string

        // TODO: Fetch user from database
        // val data = fetchUser(userId)

        return User.Builder(ctx)
            .email("placeholder@example.com")
            .build()
        // Don't set @resolver fields here - they have their own resolvers
    }
}
```

## Common Error

```
Unresolved reference 'NodeResolvers'
```

**Fix:** Add `@resolver` to the type declaration:
```graphql
# ❌ WRONG - missing @resolver
type Tag implements Node @scope(to: ["default"]) { ... }

# ✅ CORRECT
type Tag implements Node @resolver @scope(to: ["default"]) { ... }
```

## ⚠️ Import Paths (Important!)

**NodeResolvers is in a DIFFERENT package than other resolvers:**

```kotlin
// ✅ CORRECT - NodeResolvers has NO resolverbases subpackage
import com.viaduct.resolvers.NodeResolvers

// ✅ CORRECT - Other resolvers ARE in resolverbases
import com.viaduct.resolvers.resolverbases.QueryResolvers
import com.viaduct.resolvers.resolverbases.MutationResolvers
import com.viaduct.resolvers.resolverbases.TypeResolvers
```

## Node Type + Query = Two Resolvers

When a type `implements Node` AND has a query to fetch it by ID, you need **BOTH** resolvers:

```kotlin
// 1. NodeResolver - handles Relay node(id: ID!) interface
class TagNodeResolver : NodeResolvers.Tag() {
    override suspend fun resolve(ctx: Context): Tag { ... }
}

// 2. QueryResolver - handles your specific query
class TagQueryResolver : QueryResolvers.Tag() {
    override suspend fun resolve(ctx: Context): Tag? { ... }
}
```

**Both are required.** Don't skip the NodeResolver - it's needed for the Relay node interface.

## Checklist

1. Type has `implements Node`
2. Type has `@resolver` directive
3. Type has `@scope(to: ["default"])`
4. Type has `id: ID!` field
5. Create `NodeResolvers.TypeName()` resolver
6. If query exists, also create `QueryResolvers.TypeName()` resolver
7. Import NodeResolvers from correct package (no `resolverbases`)
