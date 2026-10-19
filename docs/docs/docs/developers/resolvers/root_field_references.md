---
title: Root Field References
description: Creating lazy references to root fields in resolvers
---


Resolvers can delegate construction of an object type to a *root object field* resolver. A root field is a field on the root query type or on a [`@namespaceType`](../namespace_types/index.md) reachable from the root query type.

Rather than executing a full [subquery](subqueries.md) and eagerly resolving the result, `ctx.ref()` returns a *lazy reference* that the engine resolves later with the client's selection set.

`ctx.ref` is overloaded. Passing a [GlobalID](../globalids/index.md) delegates to a node resolver; passing a root field call delegates to a root object field resolver.

## When to use a root field reference

Use a root field reference when:

* You want to return an object type that another resolver knows how to construct, without coupling to that resolver's implementation.
* The target field is a root field — declared on the root Query type, or on a `@namespaceType` reachable from it.
* You don't need to read fields from the result inside your resolver — you just need to pass it along.

If you need to read fields from the result in the same resolver, use [`ctx.query()`](subqueries.md) instead.

## API

Reference a field by calling it on its parent type and passing the result to `ctx.ref()`. The call does not execute the field; `ctx.ref()` creates the lazy reference.

A field is referenceable when it is declared on the root Query type or on a `@namespaceType` reachable from it, and has a non-list object type.

For a field **with arguments**, Kotlin uses a configuration lambda; Java uses fluent setters followed by `build()`:

=== "Kotlin"

    ```kotlin
    val ugcText = ctx.ref(
        UGCTextFactory.fromSourceText {
            sourceText(sourceTextInput)
            translateAsync(true)
        }
    )
    ```

=== "Java"

    ```java
    UGCText ugcText = ctx.ref(
        UGCTextFactory.fromSourceText()
            .sourceText(sourceTextInput)
            .translateAsync(true)
            .build());
    ```


For a field **with no arguments**, pass the call straight to `ctx.ref()`:

=== "Kotlin"

    ```kotlin
    val product = ctx.ref(ProductFactory.create())
    ```

=== "Java"

    ```java
    Product product = ctx.ref(ProductFactory.create());
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

=== "Kotlin"

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

=== "Java"

    ```java
    @Resolver
    public final class UGCTextFromSourceTextResolver
        extends UGCTextFactoryResolvers.FromSourceText {
      private final TranslationService translationService;

      public UGCTextFromSourceTextResolver(TranslationService translationService) {
        this.translationService = translationService;
      }

      @Override
      public CompletableFuture<UGCText> resolve(Context ctx) {
        var result = translationService.translate(
            ctx.getArguments().getSourceText(), ctx.getArguments().getPublishingKey());
        return CompletableFuture.completedFuture(
            UGCText.builder(ctx)
                .source(result.source())
                .sourceLocale(result.sourceLocale())
                .localizedString(result.localizedString())
                .build());
      }
    }
    ```


### Consumer (using `ctx.ref`)

The consumer resolver needs to return a `UGCText` for a listing's title. Instead of duplicating the translation logic, it delegates to the factory:

=== "Kotlin"

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

=== "Java"

    ```java
    @Resolver(objectValueFragment = "fragment _ on Listing { description { name } }")
    public final class ListingTitleResolver extends ListingResolvers.Title {
      @Override
      public CompletableFuture<UGCText> resolve(Context ctx) {
        var description = ctx.getObjectValue().getDescription();
        String name = description == null ? null : description.getName();
        if (name == null) {
          return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.completedFuture(
            ctx.ref(
                UGCTextFactory.fromSourceText()
                    .sourceText(UGCSourceTextInput.builder(ctx).sourceText(name).build())
                    .build()));
      }
    }
    ```


Set each argument on the generated field call. Nested input objects use their own builders with the same resolver context. Java examples use `java.util.concurrent.CompletableFuture` and `viaduct.java.api.annotations.Resolver`. The factory service shown here is synchronous; compose a future if the service is asynchronous.

The consumer doesn't know how `UGCText` is constructed — it just provides the raw inputs and gets back a reference that the engine will resolve with whatever fields the client requested.

## Example: simple delegation with no arguments

=== "Kotlin"

    ```kotlin
    @Resolver
    class QueryProductResolver : QueryResolvers.Product() {
        override suspend fun resolve(ctx: Context): Product? {
            return ctx.ref(ProductFactory.create())
        }
    }
    ```

=== "Java"

    ```java
    @Resolver
    public final class QueryProductResolver extends QueryResolvers.Product {
      @Override
      public CompletableFuture<Product> resolve(Context ctx) {
        return CompletableFuture.completedFuture(ctx.ref(ProductFactory.create()));
      }
    }
    ```


## How it works

1. Calling the field captures it and your argument values. Nothing executes yet.
2. `ctx.ref(...)` turns that into a reference with no accessible fields, which your resolver returns — directly or nested inside a builder.
3. The engine sees the reference and executes the target field, applying the selection set that the client originally requested for that position in the query.
4. The target resolver runs with full context: the correct arguments, its own required selection set, and the client's field selections.

Because resolution is deferred, the engine can batch and optimize — the target resolver only computes what the client actually selected.

## Testing

=== "Kotlin"

    The Kotlin test harness does not resolve references, so a test asserts the calls the resolver made rather than reading a value back from one. Pass a `ReferenceSpy` to record them. See [Verifying root field references](../testing/index.md#verifying-root-field-references-ctxref).

=== "Java"

    The Java `ResolverTestBase` does not implement node or root field references and has no `ReferenceSpy`. Exercise references through the engine or application. The [Java root field reference contract test](https://github.com/airbnb/viaduct/blob/main/core/javaapi/runtime/src/test/java/viaduct/tenant/runtime/execution/rootfieldref/JavaRootFieldRefContractTest.java) covers argument builders, nested namespace references, and fields with no arguments.


## Comparison with other context methods

| Method | What you get |
|--------|--------------|
| `ctx.ref(id)` | A reference to a node, resolved later by that node's resolver |
| `ctx.ref(call)` | A reference to a root field, resolved later by that field's resolver |
| `ctx.query()` | The query result, executed immediately — you can read fields from it inside your resolver |

## Constraints

* The target field must be a root field — declared on the root Query type, or on a [`@namespaceType`](../namespace_types/index.md) reachable from it.
* The target field must have an object output type — scalar, enum, interface, union, and list fields are not supported.
* Fields on the returned GRT are not accessible in the calling resolver. If you need to inspect the result, use `ctx.query()`.
* `ctx.ref` is currently marked `@ExperimentalApi`.
