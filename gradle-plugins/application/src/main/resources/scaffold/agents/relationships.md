# Viaduct Node Reference Pattern (Relationships)

When a field returns another Node type (like `createdBy: User`), **always use `ctx.nodeFor()`**:

```kotlin
// ✅ CORRECT - delegates to User's node resolver
return ctx.nodeFor(ctx.globalIDFor(User.Reflection, createdById))

// ❌ WRONG - building User directly bypasses node resolution
return User.Builder(ctx)
    .name("...")
    .build()
```

**Why this matters:** `nodeFor()` delegates to Viaduct's node resolution system, enabling batching, caching, and consistent data fetching. Building objects directly bypasses this and causes inconsistent behavior.

## Schema

```graphql
type Tag implements Node @resolver @scope(to: ["default"]) {
  id: ID!
  name: String!
  createdById: String!           # Store raw ID
  createdBy: User @resolver      # Resolver returns User node
}
```

## Resolver Implementation

```kotlin
package com.viaduct.resolvers

import com.viaduct.resolvers.resolverbases.TagResolvers

@Resolver("fragment _ on Tag { createdById }")
class TagCreatedByResolver : TagResolvers.CreatedBy() {

    override suspend fun resolve(ctx: Context): User? {
        val createdById = ctx.objectValue.getCreatedById()
            ?: return null

        return ctx.nodeFor(
            ctx.globalIDFor(User.Reflection, createdById)
        )
    }
}
```

## ⚠️ CRITICAL: Target Must Implement Node

For `ctx.nodeFor()` to work, the target type MUST:

1. Have `implements Node` in schema
2. Have `@resolver` directive on the type
3. Have a NodeResolver class

**Check the target type's schema first!**

```graphql
# ✅ User can be used with nodeFor
type User implements Node @resolver @scope(to: ["default"]) {
  id: ID!
  email: String
}

# ❌ User WITHOUT Node - nodeFor won't work
type User @scope(to: ["default"]) {
  id: ID!
  email: String
}
```

## If Target Doesn't Implement Node

You must add `implements Node @resolver` to the target type:

```graphql
# Add this to User type
type User implements Node @resolver @scope(to: ["default"]) { ... }
```

**⚠️ Then update ALL existing User.Builder calls:**

```kotlin
// BEFORE: Type didn't implement Node
return User.Builder(ctx)
    .id(userId)  // String
    .build()

// AFTER: Type implements Node - MUST change to:
return User.Builder(ctx)
    .id(ctx.globalIDFor(User.Reflection, userId))  // GlobalID
    .build()
```

Search for `User.Builder` to find all places needing updates.

## ⚠️ Import Paths (Important!)

**NodeResolvers is in a DIFFERENT package than other resolvers:**

```kotlin
// ✅ CORRECT - NodeResolvers has NO resolverbases
import com.viaduct.resolvers.NodeResolvers

// ✅ CORRECT - TypeResolvers ARE in resolverbases
import com.viaduct.resolvers.resolverbases.TagResolvers
import com.viaduct.resolvers.resolverbases.UserResolvers
```

## Pattern Summary

```kotlin
// Create node reference (delegates fetching to Viaduct)
ctx.nodeFor(ctx.globalIDFor(TargetType.Reflection, rawId))
```
