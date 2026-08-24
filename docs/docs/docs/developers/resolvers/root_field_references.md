---
title: Root Field References
description: Creating lazy references to root fields in resolvers
---


Resolvers can delegate construction of an object type to a *root object field* resolver. A root field is a field on the root query type or on a [`@namespaceType`](../namespace_types/index.md) reachable from the root query type.

Rather than executing a full [subquery](subqueries.md) and eagerly resolving the result, `ctx.rootFieldRef()` returns a *lazy reference* that the engine resolves later with the client's selection set.

Like `ctx.nodeRef()`, `ctx.rootFieldRef()` returns a lazy reference. The difference is `nodeRef` delegates to a node resolver, whereas `rootFieldRef` delegates to a root object field resolver.

## When to use rootFieldRef

Use `rootFieldRef` when:

* You want to return an object type that another resolver knows how to construct, without coupling to that resolver's implementation.
* The target field lives on the root Query type or on a `@namespaceType` reachable from it.
* You don't need to read fields from the result inside your resolver — you just need to pass it along.

If you need to read fields from the result in the same resolver, use [`ctx.query()`](subqueries.md) instead.

## API

```kotlin
@ExperimentalApi
fun <A : Arguments, BR : Object> rootFieldRef(
    field: RootObjectField<*, BR, A>,
    arguments: A
): BR
```

**Parameters:**

* `field` — A generated `Fields` constant identifying the target root field. These are generated on the GRT companion for each `@resolver` field on the root Query type or on a `@namespaceType`.
* `arguments` — A typed arguments object matching the target field's argument type. Use `Arguments.NoArguments` if the field takes no arguments.

**Returns:** A GRT of the target field's output type. No fields are accessible on this object — attempting to read fields will throw an exception. The engine resolves the reference after your resolver returns, using the selection set from the client query.

## Example: delegating to a factory function

A common use case is *factory functions* — resolvers exposed via `@namespaceType` that encapsulate construction logic for a shared type. Consumers invoke the factory through `rootFieldRef` without depending on how the type is built.

### Schema

In this example, `UGCText` is a type that wraps user-generated content with localization support (source text, translated text, locale metadata). A `UGCTextFactory` namespace exposes a factory function that encapsulates the construction logic:

```graphql
type UGCText {
  source: String
  sourceLocale: String
  localizedString: String
}

type UGCTextFactory @namespaceType {
  fromSourceText(
    sourceText: UGCSourceTextInput!
    publishingKey: UGCPublishingKeyInput
    translateAsync: Boolean = false
  ): UGCText @resolver
}

extend type Query {
  ugcText: UGCTextFactory
}
```

### Producer (factory resolver)

The factory resolver owns the construction logic for `UGCText`. It receives the raw inputs, calls the translation pipeline, and builds the result. Consumers never need to know these implementation details:

```kotlin
@Resolver
class UGCTextFromSourceTextResolver @Inject constructor(
  val translationService: TranslationService
) : UGCTextFactoryResolvers.FromSourceText() {
    override suspend fun resolve(ctx: Context): UGCText? {
        val result = translationService.translate(
            ctx.arguments.sourceText,
            ctx.arguments.publishingKey
        )
        return UGCText.of(ctx) {
            source(result.source)
            sourceLocale(result.sourceLocale)
            localizedString(result.localizedString)
        }
    }
}
```

### Consumer (using rootFieldRef)

The consumer resolver needs to return a `UGCText` for a listing's title. Instead of duplicating the translation logic, it delegates to the factory via `rootFieldRef`:

```kotlin
@Resolver("fragment _ on Listing { description { name } }")
class ListingTitleResolver @Inject constructor() : ListingResolvers.Title() {
    override suspend fun resolve(ctx: Context): UGCText? {
        val name = ctx.getObjectValue().getDescriptionOrThrow()?.getNameOrThrow() ?: return null
        return ctx.rootFieldRef(
            UGCTextFactory.Fields.fromSourceText,
            UGCTextFactory_FromSourceText_Arguments.Builder(ctx)
                .sourceText(
                    UGCSourceTextInput.Builder(ctx)
                        .sourceText(name)
                        .build()
                )
                .build()
        )
    }
}
```

The consumer doesn't know how `UGCText` is constructed — it just provides the raw inputs and gets back a reference that the engine will resolve with whatever fields the client requested.

## Example: simple delegation with no arguments

```kotlin
@Resolver
class QueryProductResolver : QueryResolvers.Product() {
    override suspend fun resolve(ctx: Context): Product? {
        return ctx.rootFieldRef(
            ProductFactory.Fields.create,
            Arguments.NoArguments
        )
    }
}
```

## How it works

1. Your resolver calls `ctx.rootFieldRef(field, args)` and receives a GRT with no accessible fields.
2. Your resolver returns this GRT (directly or nested inside a builder).
3. The engine sees the reference and executes the target field's resolver, applying the selection set that the client originally requested for that position in the query.
4. The target resolver runs with full context: the correct arguments, its own required selection set, and the client's field selections.

Because resolution is deferred, the engine can batch and optimize — the target resolver only computes what the client actually selected.

## Comparison with other context methods

| Method | Use when |
|--------|----------|
| `ctx.nodeRef(id)` | Delegating to a node resolver by GlobalID |
| `ctx.rootFieldRef(field, args)` | Delegating to a root/namespace field resolver by field + arguments |
| `ctx.query(selections)` | You need to read fields from the result inside your resolver |

## Constraints

* The target field must be on the root Query type or reachable through `@namespaceType`-typed fields.
* The target field must have an object output type — scalar, enum, interface, and union fields are not supported.
* Fields on the returned GRT are not accessible in the calling resolver. If you need to inspect the result, use `ctx.query()`.
* `rootFieldRef` is currently marked `@ExperimentalApi`.
