# Viaduct Batch Resolution Pattern

Use `batchResolve` instead of `resolve` to prevent N+1 queries:

```kotlin
package com.viaduct.resolvers

import com.viaduct.resolvers.resolverbases.GroupResolvers

@Resolver("fragment _ on Group { id }")
class GroupTagsResolver : GroupResolvers.Tags() {

    override suspend fun batchResolve(contexts: List<Context>): List<FieldValue<List<Tag>>> {
        // 1. Collect all parent IDs
        val groupIds = contexts.map { it.objectValue.getId().internalID }

        // 2. Fetch all data in ONE query
        // TODO: val tagsByGroup = fetchTagsForGroups(groupIds)

        // 3. Return results in SAME ORDER as contexts
        return contexts.map { ctx ->
            val groupId = ctx.objectValue.getId().internalID
            // Return empty list as placeholder
            FieldValue.ofValue(emptyList<Tag>())
        }
    }
}
```

## FieldValue Return Types

| Method | Use Case |
|--------|----------|
| `FieldValue.ofValue(obj)` | Successful resolution |
| `FieldValue.ofError(exception)` | Per-item error |
| `FieldValue.ofNull()` | Explicit null |

## Critical Rules

1. **Return list in SAME ORDER as input contexts**
2. **Return `List<FieldValue<T>>` not `List<T>`**
3. **One database query for all items** - that's the whole point

## Schema

```graphql
type Group {
  id: ID!
  tags: [Tag!]! @resolver  # Will use batchResolve
}
```

## When to Use Batch

Use `batchResolve` when:
- Field returns related entities (tags, members, comments)
- Parent type appears in lists
- You see N+1 query patterns in logs

Use regular `resolve` when:
- Field is a simple computation
- No database access needed
- Parent is always fetched individually
