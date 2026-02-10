# Viaduct Field Resolver Pattern

Add `@resolver` to fields that need custom resolution logic:

```graphql
type User {
  firstName: String
  lastName: String
  displayName: String @resolver
}
```

## Resolver Implementation

```kotlin
package com.viaduct.resolvers

import com.viaduct.resolvers.resolverbases.UserResolvers

@Resolver("fragment _ on User { firstName lastName }")
class UserDisplayNameResolver : UserResolvers.DisplayName() {

    override suspend fun resolve(ctx: Context): String? {
        val fn = ctx.objectValue.getFirstName()
        val ln = ctx.objectValue.getLastName()
        return listOfNotNull(fn, ln).joinToString(" ").ifEmpty { null }
    }
}
```

## Key Syntax

| Pattern | Purpose |
|---------|---------|
| `@Resolver("fragment _ on Type { field1 field2 }")` | Declares required parent fields |
| `ctx.objectValue.getFieldName()` | Access parent fields (camelCase getter) |
| `TypeResolvers.FieldName()` | Base class to extend |

## Selection Set (objectValueFragment)

The fragment in `@Resolver(...)` tells Viaduct which parent fields you need:

```kotlin
// Request userId and role from parent
@Resolver("fragment _ on GroupMember { userId role }")
class GroupMemberDisplayNameResolver : GroupMemberResolvers.DisplayName() {

    override suspend fun resolve(ctx: Context): String? {
        val userId = ctx.objectValue.getUserId()  // Available because declared
        val role = ctx.objectValue.getRole()      // Available because declared
        // ctx.objectValue.getEmail() // ❌ NOT available - not in fragment
        return "$userId ($role)"
    }
}
```

## Important Notes

- Only access fields declared in your fragment
- Field resolvers are NOT set in the NodeResolver - they resolve separately
- Return type matches the schema field type
