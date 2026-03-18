# Viaduct Mutation Pattern

## ⛔ BEFORE IMPLEMENTING: Check Schema for @idOf

**First, check if input types have `@idOf` on ID fields.** If missing, add it:

```graphql
# ❌ BROKEN - Missing @idOf
input UpdateTaskInput {
  id: ID!
}

# ✅ FIXED - Add @idOf
input UpdateTaskInput {
  id: ID! @idOf(type: "Task")
}
```

Without `@idOf`, `input.id` returns a raw base64 string instead of a GlobalID. Use `.internalID` to extract the UUID.

## Schema

```graphql
extend type Mutation @scope(to: ["default"]) {
  createUser(input: CreateUserInput!): User! @resolver
  updateUser(input: UpdateUserInput!): User! @resolver
  deleteUser(id: ID! @idOf(type: "User")): Boolean! @resolver
}

input CreateUserInput @scope(to: ["default"]) {
  email: String!
  firstName: String
}

input UpdateUserInput @scope(to: ["default"]) {
  id: ID! @idOf(type: "User")  # ⚠️ CRITICAL: Use @idOf!
  email: String
  firstName: String
}
```

## @idOf in Input Types

**⚠️ CRITICAL:** Always use `@idOf` on ID fields in input types:

```graphql
# ✅ CORRECT
input UpdateUserInput {
  id: ID! @idOf(type: "User")  # Enables .internalID
}

# ❌ WRONG - id will be raw String, not GlobalID
input UpdateUserInput {
  id: ID!
}
```

## Resolver Implementation

```kotlin
package com.viaduct.resolvers

import com.viaduct.resolvers.resolverbases.MutationResolvers

@Resolver
class UpdateUserResolver : MutationResolvers.UpdateUser() {

    override suspend fun resolve(ctx: Context): User {
        val input = ctx.arguments.input
        val userId = input.id.internalID  // GlobalID<User> thanks to @idOf

        // TODO: Update user in database
        // updateUser(userId, input.email, input.firstName)

        return User.Builder(ctx)
            .id(ctx.globalIDFor(User.Reflection, userId))
            .email(input.email)
            .firstName(input.firstName)
            .build()
    }
}
```

## GlobalID Summary

| Location | @idOf Required? |
|----------|-----------------|
| Input type ID field | **YES** |
| Mutation argument ID | **YES** |
| Node type `id: ID!` field | NO (automatic) |

## Key Patterns

```kotlin
// Extract UUID from input
val userId = input.id.internalID

// Create GlobalID for response
.id(ctx.globalIDFor(User.Reflection, userId))
```
