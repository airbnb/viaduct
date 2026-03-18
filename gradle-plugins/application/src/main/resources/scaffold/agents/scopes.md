# Viaduct Scope Pattern

## Default Scope (Most Common)

Most types only need default scope:

```graphql
type Tag implements Node @resolver @scope(to: ["default"]) {
  id: ID!
  name: String!
  color: String
}
```

## Admin Mutations

Use `extend type Mutation` for admin-only mutations - this works correctly:

```graphql
# ✅ Admin mutations work with @resolver
extend type Mutation @scope(to: ["admin"]) {
  deleteAllTags: Boolean! @resolver
  resetData: Boolean! @resolver
}
```

```kotlin
package com.viaduct.resolvers

import com.viaduct.resolvers.resolverbases.MutationResolvers

@Resolver
class DeleteAllTagsResolver : MutationResolvers.DeleteAllTags() {

    override suspend fun resolve(ctx: Context): Boolean {
        // TODO: Delete all tags
        return true
    }
}
```

## ⚠️ CRITICAL: Object Type Extensions Don't Work

**Do NOT use `extend type` for object types (Tag, User, etc.) when the base type has `@resolver`:**

```graphql
# ❌ DOESN'T WORK - fields get stripped during schema assembly
type Tag implements Node @resolver @scope(to: ["default"]) { ... }
extend type Tag @scope(to: ["admin"]) {
  internalNotes: String  # Gets stripped → empty block → error!
}
```

**The framework strips fields from extend blocks on object types with @resolver.**

## What Works vs What Doesn't

| Scenario | Works? |
|----------|--------|
| `extend type Query @scope(to: ["admin"])` with `@resolver` fields | ✅ Yes |
| `extend type Mutation @scope(to: ["admin"])` with `@resolver` fields | ✅ Yes |
| `extend type Tag @scope(to: ["admin"])` with any fields | ❌ No |

## Recommended Pattern

For admin-only operations, use mutations instead of fields:

```graphql
# ✅ CORRECT - use admin mutations
extend type Mutation @scope(to: ["admin"]) {
  getTagInternalNotes(id: ID! @idOf(type: "Tag")): String @resolver
  setTagInternalNotes(id: ID! @idOf(type: "Tag"), notes: String!): Tag! @resolver
}
```
