---
title: Root Field References
description: Creating lazy references to root fields in resolvers
---


Resolvers can delegate construction of an object type to a *root object field* resolver. A root field is a field on the root query type or on a [`@namespaceType`](../namespace_types/index.md) reachable from the root query type. The generated API described below covers the `@namespaceType` case only.

Rather than executing a full [subquery](subqueries.md) and eagerly resolving the result, `ctx.ref()` returns a *lazy reference* that the engine resolves later with the client's selection set.

Like `ctx.nodeRef()`, `ctx.ref()` returns a lazy reference. The difference is `nodeRef` delegates to a node resolver, whereas `ctx.ref` delegates to a root object field resolver.

## When to use a root field reference

Use a root field reference when:

* You want to return an object type that another resolver knows how to construct, without coupling to that resolver's implementation.
* The target field lives on a `@namespaceType` reachable from the root Query type.
* You don't need to read fields from the result inside your resolver — you just need to pass it along.

If you need to read fields from the result in the same resolver, use [`ctx.query()`](subqueries.md) instead.

## API

Reference a field by calling it on its parent type and passing the result to `ctx.ref()`. The call does not execute the field; `ctx.ref()` creates the lazy reference.

A field is referenceable when it is declared on a `@namespaceType` reachable from the root Query type, carries `@resolver`, and has a non-list object type.

For a field **with arguments**, set each one inside the configuration lambda:

```kotlin
val ugcText = ctx.ref(
    UGCTextFactory.fromSourceText {
        sourceText(sourceTextInput)
        translateAsync(true)
    }
)
```

For a field **with no arguments**, pass the call straight to `ctx.ref()`:

```kotlin
val product = ctx.ref(ProductFactory.create())
```

`ctx.ref()` returns a GRT of the target field's output type. No fields are accessible on this object — attempting to read fields will throw an exception. The engine resolves the reference after your resolver returns, using the selection set from the client query.

## Example: delegating to a factory function

A common use case is *factory functions* — resolvers exposed via `@namespaceType` that encapsulate construction logic for a shared type. Consumers invoke the factory through `ctx.ref` without depending on how the type is built.

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

### Consumer (using `ctx.ref`)

The consumer resolver needs to return a `UGCText` for a listing's title. Instead of duplicating the translation logic, it delegates to the factory:

```kotlin
@Resolver("fragment _ on Listing { description { name } }")
class ListingTitleResolver @Inject constructor() : ListingResolvers.Title() {
    override suspend fun resolve(ctx: Context): UGCText? {
        val name = ctx.getObjectValue().getDescriptionOrThrow()?.getNameOrThrow() ?: return null
        return ctx.ref(
            UGCTextFactory.fromSourceText {
                sourceText(
                    UGCSourceTextInput.Builder(ctx)
                        .sourceText(name)
                        .build()
                )
            }
        )
    }
}
```

Set each argument inside the configuration lambda. Nested input objects are built with their own builders, using the same resolver context.

The consumer doesn't know how `UGCText` is constructed — it just provides the raw inputs and gets back a reference that the engine will resolve with whatever fields the client requested.

## Example: simple delegation with no arguments

```kotlin
@Resolver
class QueryProductResolver : QueryResolvers.Product() {
    override suspend fun resolve(ctx: Context): Product? {
        return ctx.ref(ProductFactory.create())
    }
}
```

## How it works

1. Calling the field captures it and your argument values. Nothing executes yet.
2. `ctx.ref(...)` turns that into a reference with no accessible fields, which your resolver returns — directly or nested inside a builder.
3. The engine sees the reference and executes the target field's resolver, applying the selection set that the client originally requested for that position in the query.
4. The target resolver runs with full context: the correct arguments, its own required selection set, and the client's field selections.

Because resolution is deferred, the engine can batch and optimize — the target resolver only computes what the client actually selected.

## Testing

The test harness does not resolve references, so a test asserts the calls the resolver made rather than reading a value back from one. Pass a `ReferenceSpy` to record them. See [Verifying root field references](../testing/index.md#verifying-root-field-references-ctxref).

## Comparison with other context methods

| Method | What you get |
|--------|--------------|
| `ctx.nodeRef(id)` | A reference to a node, resolved later by that node's resolver |
| `ctx.ref(call)` | A reference to a root field, resolved later by that field's resolver |
| `ctx.query()` | The query result, executed immediately — you can read fields from it inside your resolver |

## Constraints

* The target field must be declared on a `@namespaceType` that is reachable from the root Query type. Fields declared directly on `Query` cannot be referenced with `ctx.ref`.
* The target field must have an object output type — scalar, enum, interface, union, and list fields are not supported.
* Fields on the returned GRT are not accessible in the calling resolver. If you need to inspect the result, use `ctx.query()`.
* `ctx.ref` is currently marked `@ExperimentalApi`.
