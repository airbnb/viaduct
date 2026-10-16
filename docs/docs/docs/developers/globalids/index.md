---
title: Global IDs
description: Global identifiers for nodes
---

Viaduct uses `GlobalID<T>` and `String` to represent GraphQL `ID` values in both Kotlin and Java. Kotlin imports `viaduct.api.globalid.GlobalID`; Java imports `viaduct.java.api.globalid.GlobalID`. `GlobalID<T>` is an object that consists of a type and an internal ID. They are used to uniquely identify `Node` objects in the graph. `GlobalID` values support structural equality, as opposed to referential equality.

There are two conditions under which `GlobalID<T>` will be used:

1. The `id` field of a `Node` object type
2. A field of type `ID` with the `@idOf(type:"T")` directive, where `T` must be a GraphQL object or interface type that implements `Node`

Where neither condition is met, `String` is used in both languages to represent GraphQL IDs.

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

To ensure consistency in the type of `id` fields, if a Node object type implements another interface, and that interface has an id field, then that interface must also implement Node (and so the `id` field is consistently a global ID).

## Semantics

Logically a global ID consists of two components:

* **Type:** a GraphQL object or interface type that implements `Node`
* **Internal ID:** an type-specific unique identifier of an instance of that type. Application logic defines the contents of internal ids, usually in the type's node resolver. Details vary greatly, but a common example is for the internal ID to be the primary key for the type's database table.

In Kotlin, these components are the `type` and `internalID` properties. In Java, call `getType()` and `getInternalID()`. Java type descriptors come from `viaduct.java.api.reflect.Type`; the generated GRT also exposes a `Reflection` constant.

Instances of `GlobalID` can be created using the `Context` objects provided to resolvers:

=== "Kotlin"

    ```kotlin
    id(ctx.globalIDFor(MyNode.Reflection, entity.id))
    ```

=== "Java"

    ```java
    GlobalID<MyNode> id = ctx.globalIDFor(MyNode.Reflection, entity.id());
    MyNode node = MyNode.builder(ctx).id(id).build();
    ```


Kotlin also provides an inline reified form; Java passes a type descriptor explicitly:

=== "Kotlin"

    ```kotlin
    id(ctx.globalIDFor<MyNode>(entity.id))
    ```

=== "Java"

    Java has no reified type parameter form. Pass the generated `MyNode.Reflection` descriptor or `Type.ofClass(MyNode.class)` explicitly:

    ```java
    GlobalID<MyNode> id = ctx.globalIDFor(Type.ofClass(MyNode.class), entity.id());
    ```


## Serialization and Durability

> The serialization format of Global IDs is intentionally **opaque**. Neither tenant code nor external clients should attempt to parse them.

The objective of the global-id type is to support modularity: each `Node` implementation can have its own, encapsulated opinion as to how its instances are identified.  This opinion is encapsulated in the `internalID` half of a global id. At the same time, Viaduct itself needs a way to dispatch `ID`s to the node-resolver that supports them, which is where the `type` half of a global id comes in.  But note that both of these concerns should be encapsulated from external clients: they should be treat global ids as opaque, atomic identifiers.

Viaduct resolvers, should be using our `GlobalID` type to safely extract `internalID`s from a serialized global id. If you find yourself needing to decode global ids in such application logic, chances are you are missing an `@idOf` directive in your schema (see below). Resolvers should use `internalID`s (or some other identifier) to identify `Node` instances in a database or in a downstream service: serialized global ids are not intended for that purpose.

Architects should assume that over a long-enough period of time the serialization format for global ids will change and they should design their applications around that assumption. While on front-ends, global ids should remain stable across a client session (and perhaps even beyond that), on the backend the architectural assumption should be that the format of globals ids may change, and thus they are not well suited as _durable_ identifiers, i.e., identifiers that an external application might put into their long-term storage. Backends often expose their `internalID` directly, which is a fine pattern. An alternative is to encapsulate `internalID`s and use an even more durable identifier such as an account number:

```graphql
extend type Query {
  userByAccountNumber(accountNumber: Int!): User
}
```

where `accountNumber` _is_ an identifier intended to be stored by external systems.

## Using Global IDs in node resolvers

Node resolvers receive a `GlobalID` via `ctx.id` in Kotlin or `ctx.getId()` in Java. Read its internal identifier to load the entity. For the batching example, declare `MyNode` with `@resolver(isBatching: true)`:

=== "Kotlin"

    ```kotlin
    @Resolver
    class MyNodeResolver
        @Inject
        constructor(
            private val repository: MyRepository
        ) : NodeResolvers.MyNode() {
            override suspend fun batchResolve(contexts: List<Context>): Map<Context, FieldValue<MyNode>> {
                val ids = contexts.map { it.id.internalID }.distinct()
                val entities = repository.findByIds(ids)
                return contexts.associateWith { ctx ->
                    val entity = entities[ctx.id.internalID]
                    if (entity == null) {
                        FieldValue.ofError(MissingMyNodeException("MyNode ${ctx.id} was not found"))
                    } else {
                        FieldValue.ofValue(MyNode.Builder(ctx)
                            .id(ctx.id)
                            .id2(entity.id)
                            .id3(ctx.id)
                            .build())
                    }
                }
            }
        }
    ```

=== "Java"

    ```java
    @Resolver
    public final class MyNodeResolver extends NodeResolvers.MyNode {
      private final MyRepository repository;

      @Inject
      public MyNodeResolver(MyRepository repository) {
        this.repository = repository;
      }

      @Override
      public CompletableFuture<Map<Context, FieldValue<MyNode>>> batchResolve(
          List<Context> contexts) {
        List<String> ids = contexts.stream()
            .map(ctx -> ctx.getId().getInternalID())
            .distinct()
            .toList();
        var entities = repository.findByIds(ids);
        Map<Context, FieldValue<MyNode>> results = new LinkedHashMap<>();
        for (Context ctx : contexts) {
          var entity = entities.get(ctx.getId().getInternalID());
          results.put(ctx, entity == null
              ? FieldValue.ofError(new MissingMyNodeException("MyNode " + ctx.getId() + " was not found"))
              : FieldValue.ofValue(MyNode.builder(ctx)
                  .id(ctx.getId())
                  .id2(entity.id())
                  .id3(ctx.getId())
                  .build()));
        }
        return CompletableFuture.completedFuture(results);
      }
    }
    ```


Both examples assume `repository.findByIds` returns a map keyed by internal ID and each entity exposes its internal ID as `id` in Kotlin or `id()` in Java. `MissingMyNodeException` is an application-defined exception. Set every non-resolver field in the output selection set; both examples fill the three ID fields in the schema above.

The returned map must contain every exact input `Context` object as a key. Return `FieldValue.ofError` when an ID is not found; missing or foreign context keys are rejected.

## Schema hinting with `@idOf`

Where field arguments and input fields hold the `ID` of a `Node` type, the `@idOf` directive should be used to bind them to the intended GraphQL type, enabling type-safe handling in resolvers and tooling:

{{ codetag("demoapps/starwars/modules/filmography/src/main/viaduct/schema/Character.graphqls", "one_of_example", lang="graphql") }}

In the Kotlin and Java GRTs for `CharacterSearchInput`, the type of `byId` is `GlobalID<Character>`, allowing type-safe handling of that field.

While `@idOf` may be used on the fields of GraphQL object and interface types, we do _not_ recommend using `ID` for such fields:

* prefer `foo: Foo` on object and interface fields
* over `fooID: ID @idOf(type: "Foo")`


## Do and don't

- **Do** treat Global IDs as opaque and stable across the API surface.
- **Do** generate them in resolvers using `ctx.globalIDFor(MyNode.Reflection, internalId)` in either language.
- **Do** access the internal ID via `ctx.id.internalID` in Kotlin or `ctx.getId().getInternalID()` in Java node resolvers.
- **Do** use `@idOf` on field arguments and input fields carrying Global IDs.
- **Don't** expose internal IDs or rely on clients decoding them.
- **Don't** embed business logic or access control information in IDs.
