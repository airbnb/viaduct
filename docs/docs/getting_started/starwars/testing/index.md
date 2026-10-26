---
title: "Testing"
description: "Resolver unit test examples using the Star Wars demo."
---


Kotlin Star Wars resolver tests use `ResolverTestBase`. The Java demo uses JUnit 5, AssertJ, and Micronaut HTTP integration tests; focused Java unit tests can use `viaduct.java.api.testing.ResolverTestBase` through composition, with `FieldInputs` and `NodeInputs` instead of Kotlin’s DSL. The examples below show the demo’s existing HTTP tests; the [Testing guide](../../../docs/developers/testing/index.md) documents the Java fixture and its limitations. For setup, context construction, and the full API reference see the [Testing guide](../../../docs/developers/testing/index.md).

=== "Kotlin"

    Run `./gradlew test` from your Kotlin demo checkout.

=== "Java"

    Run the existing integration suite from `demoapps/starwars-java`:

    ```shell
    USE_VIADUCT_SNAPSHOT_REPO=true ./gradlew test
    ```

    `StarWarsJavaIntegrationTest` starts a Micronaut server on an available port, injects an HTTP client, and sends requests through the real `/graphql` controller. This exercises generated types, resolver discovery, dependency injection, scopes, and request context together.

    {{ codefile("demoapps/starwars-java/src/test/java/com/example/starwars/service/StarWarsJavaIntegrationTest.java", start=21, end=28, lang="java") | indent(4) }}

    The Java examples share this HTTP helper and response assertions:

    {{ codefile("demoapps/starwars-java/src/test/java/com/example/starwars/service/StarWarsJavaIntegrationTest.java", start=432, end=462, lang="java") | indent(4) }}

## Field resolver

`CharacterDisplayNameResolver` resolves `displayName` from the `Character` GRT. The Kotlin test uses `runFieldResolver` and builds the input object with `Character.of(context)`, setting only the fields the resolver reads.

=== "Kotlin"

    {{ codetag("demoapps/starwars/src/test/kotlin/com/example/starwars/service/test/CharacterResolverUnitTests.kt", "field_resolver_example", lang="kotlin") | indent(4) }}

=== "Java"

    The Java integration test selects computed fields and asserts the returned values. It exercises real resolver wiring rather than constructing a Kotlin resolver-test spec.

    {{ codefile("demoapps/starwars-java/src/test/java/com/example/starwars/service/StarWarsJavaIntegrationTest.java", start=242, end=273, lang="java") | indent(4) }}

### With arguments

`CharacterFormattedDescriptionResolver` takes a `format` argument that controls the output shape. The Kotlin test builds the arguments object with `Character_FormattedDescription_Arguments.of(context)` and passes it alongside the object value.

=== "Kotlin"

    {{ codetag("demoapps/starwars/src/test/kotlin/com/example/starwars/service/test/CharacterResolverUnitTests.kt", "field_resolver_with_arguments_example", lang="kotlin") | indent(4) }}

=== "Java"

    Select the field with different arguments in one operation. The aliases distinguish the two results, including the `includeDetails` branches.

    {{ codefile("demoapps/starwars-java/src/test/java/com/example/starwars/service/StarWarsJavaIntegrationTest.java", start=242, end=273, lang="java") | indent(4) }}

---

## Field batch resolver

`CharacterFilmCountResolver` is a batching resolver (`@resolver(isBatching: true)`) that counts how many films each character appears in. The Kotlin test uses `runFieldBatchResolver`, passing a list of `Character` objects as `objectValues`. Call `.get()` on each result to extract the value.

=== "Kotlin"

    {{ codetag("demoapps/starwars/src/test/kotlin/com/example/starwars/service/test/ResolverApiUnitTests.kt", "field_batch_resolver_example", lang="kotlin") | indent(4) }}

=== "Java"

    The Java integration suite selects `filmCount` alongside relationships in a real request. To verify a batching optimization, additionally instrument the repository and assert the number of bulk calls; a correct response alone does not prove the batching behavior.

    {{ codefile("demoapps/starwars-java/src/test/java/com/example/starwars/service/StarWarsJavaIntegrationTest.java", start=30, end=69, lang="java") | indent(4) }}

---

## Node resolver

`FilmNodeResolver` fetches a `Film` by its `GlobalID`. The Kotlin test uses `runNodeResolver` and builds the ID with `globalIDFor(Film.Reflection, internalId)`. The `id` property on the spec is required.

=== "Kotlin"

    {{ codetag("demoapps/starwars/src/test/kotlin/com/example/starwars/service/test/QueryResolverUnitTests.kt", "test_node_resolver_example", lang="kotlin") | indent(4) }}

=== "Java"

    Fetch IDs from a query first, then use them in `node(id:)`. The Java test covers the batched character resolver and the single film resolver.

    {{ codefile("demoapps/starwars-java/src/test/java/com/example/starwars/service/StarWarsJavaIntegrationTest.java", start=71, end=98, lang="java") | indent(4) }}

---

## Node batch resolver

`CharacterNodeResolver` supports batch resolution. The Kotlin test uses `runNodeBatchResolver`, passing a list of `GlobalID`s as `ids`. Call `.get()` on each result to extract the resolved object.

=== "Kotlin"

    {{ codetag("demoapps/starwars/src/test/kotlin/com/example/starwars/service/test/CharacterResolverUnitTests.kt", "character_node_resolver_multiple_ids", lang="kotlin") | indent(4) }}

=== "Java"

    The same operation selects two independent nodes, including the batched `CharacterNodeResolver`. Add several character IDs when testing map completeness; Java batch resolvers must return an entry for each original context.

    {{ codefile("demoapps/starwars-java/src/test/java/com/example/starwars/service/StarWarsJavaIntegrationTest.java", start=71, end=98, lang="java") | indent(4) }}

---

## Mutation resolver

`CreateCharacterMutation` creates a new character. The Kotlin test uses `runMutationFieldResolver`, building the input and arguments objects with `CreateCharacterInput.of(context)` and `Mutation_CreateCharacter_Arguments.of(context)`.

=== "Kotlin"

    {{ codetag("demoapps/starwars/src/test/kotlin/com/example/starwars/service/test/ResolverApiUnitTests.kt", "mutation_resolver_example", lang="kotlin") | indent(4) }}

=== "Java"

    The Java test checks denied access, creates a character with the admin header, then executes a typed submutation and verifies the result. The suite also covers duplicate relationships, deletion cleanup, and invalid IDs.

    {{ codefile("demoapps/starwars-java/src/test/java/com/example/starwars/service/StarWarsJavaIntegrationTest.java", start=299, end=323, lang="java") | indent(4) }}
