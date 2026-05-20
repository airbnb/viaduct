---
title: Global IDs
description: Global identifiers for nodes
---


Viaduct uses two different Kotlin types to represent GraphQL `ID` types: `GlobalID<T>` and String. `GlobalID<T>` is an object that consists of a type and an internal ID. They are used to uniquely identify node objects in the graph. `GlobalID` values support structural equality, as opposed to referential equality.

There are two conditions under which `GlobalID<T>` will be used:

1. The `id` field of a `Node` object type
2. A field of type `ID` with the `@idOf(type:"T")` directive, where `T` must be a GraphQL object or interface type that implements `Node`

Elsewhere in the Kotlin code, String will be used for IDs.

For the examples below, `id`, `id3` and `f2` are GlobalIDs and while `id2` and `f1` are Strings.

```graphqls
type MyNode implements Node {
  id: ID!
  id2: ID!
  id3: ID! @idOf(type: "MyNode")
}

input Input {
  f1: ID!
  f2: ID! @idOf(type: "MyNode")
}
```

If a Node object type implements an interface, and that interface has an id field, then that interface must also implement Node.

## Format and encoding

The raw form is `"<Type>:<InternalID>"`, which is then base64-encoded before being sent to clients. For example, a `MyNode` with internal ID `"1"` encodes to `"TXlOb2RlOjE="`.

> Treat Global IDs as **opaque**. They are intended for retrieval via `node` queries, not as human-facing identifiers. Do not expose internal IDs or rely on clients decoding the base64.

## Generating Global IDs in resolvers

Instances of `GlobalID` can be created using the `Context` objects provided to resolvers:

```kotlin
id(ctx.globalIDFor(MyNode.Reflection, entity.id))
```

Or using the inline reified form:

```kotlin
id(ctx.globalIDFor<MyNode>(entity.id))
```

## Using Global IDs in node resolvers

Node resolvers receive a `GlobalID` via `ctx.id`; use `ctx.id.internalID` to extract the internal identifier and load the entity:

```kotlin
@Resolver
class MyNodeResolver
    @Inject
    constructor(
        private val repository: MyRepository
    ) : NodeResolvers.MyNode() {
        override suspend fun batchResolve(contexts: List<Context>): List<FieldValue<MyNode>> {
            val ids = contexts.map { it.id.internalID }
            val entities = repository.findByIds(ids)
            return contexts.map { ctx ->
                entities[ctx.id.internalID]
                    ?.let { FieldValue.ofValue(MyNodeBuilder(ctx).build(it)) }
                    ?: FieldValue.ofError(IllegalArgumentException("Not found: ${ctx.id.internalID}"))
            }
        }
    }
```

## Client usage via `node(id:)`

Clients pass a Global ID to retrieve a specific entity, independent of the underlying storage key format:

```graphql
query ($id: ID!) {
  node(id: $id) {
    ... on MyNode {
      id
      name
    }
  }
}
```

## Schema hinting with `@idOf`

Annotate `ID` fields and arguments with `@idOf` to bind them to a concrete GraphQL type, enabling type-safe handling in resolvers and tooling:

```graphql
input MyNodeSearchInput @oneOf {
  """
  Search by ID
  """
  byId: ID @idOf(type: "MyNode")
}
```

```graphql
"""
A node in the graph.
"""
type MyNode implements Node {
  """
  The Global ID of this node — uniquely identifies it in the graph.
  """
  id: ID!
}
```

## Do and don't

- **Do** treat Global IDs as opaque and stable across the API surface.
- **Do** generate them in resolvers using `ctx.globalIDFor(Type.Reflection, internalId)`.
- **Do** access the internal ID via `ctx.id.internalID` in node resolvers.
- **Do** use `@idOf` on schema fields/arguments carrying Global IDs.
- **Don't** expose internal IDs or rely on clients decoding base64.
- **Don't** embed business logic or access control information in IDs.
