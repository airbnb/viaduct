---
title: Subqueries
description: Executing subqueries in resolvers
---


`Context.query` can be used to execute a subquery, i.e., a GraphQL query operation rooted in the full-schema's `Query` root type. As an example, we can modify the resolver for User.displayName to incorporate data that it loads from Query:

```kotlin
@Resolver(
  "fragment _ on User { id firstName lastName }"
)
class UserDisplayNameResolver: UserResolvers.DisplayName() {
    override suspend fun resolve(ctx: Context): String? {
        val id = ctx.objectValue.getId()
        val fn = ctx.objectValue.getFirstName()
        val ln = ctx.objectValue.getLastName()

        // determine if user is the logged-in user, in which case
        // we add a suffix to their displayName
        // loads a selection set on the root Query object
        val query = ctx.query("{ viewer { user { id } } }")
        val isViewer = id == query.getViewer()?.getUser()?.getId()
        val suffix = if (isViewer) " (you!)" else ""

        return when {
            fn == null && ln == null -> null
            fn == null -> ln
            ln == null -> fn
            else -> "$fn $ln$suffix"
        }
    }
}
```

We call this process of loading a selection set an "imperative subquery", which is distinguished from the more "declarative" method of data loading used by the `@Resolver` annotation. It can be used to load selections on the root Query object that are not known until runtime.
