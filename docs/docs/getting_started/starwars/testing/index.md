---
title: "Testing"
description: "Resolver unit test examples using the Star Wars demo."
---


Star Wars resolver tests use `ResolverTestBase`. For setup, context construction, and the full
API reference see the [Testing guide](../../../docs/developers/testing/index.md).

## Field resolver

`CharacterDisplayNameResolver` resolves `displayName` from the `Character` GRT. The test uses
`runFieldResolver` and builds the input object with `Character.of(context)`, setting only the
fields the resolver reads.

{{ codetag("demoapps/starwars/src/test/kotlin/com/example/starwars/service/test/CharacterResolverUnitTests.kt", "field_resolver_example", lang="kotlin") }}

### With arguments

`CharacterFormattedDescriptionResolver` takes a `format` argument that controls the output
shape. The test builds the arguments object with `Character_FormattedDescription_Arguments.of(context)`
and passes it alongside the object value.

{{ codetag("demoapps/starwars/src/test/kotlin/com/example/starwars/service/test/CharacterResolverUnitTests.kt", "field_resolver_with_arguments_example", lang="kotlin") }}

---

## Field batch resolver

`CharacterFilmCountResolver` is a batching resolver (`@resolver(isBatching: true)`) that counts
how many films each character appears in. The test uses `runFieldBatchResolver`, passing a list
of `Character` objects as `objectValues`. Call `.get()` on each result to extract the value.

{{ codetag("demoapps/starwars/src/test/kotlin/com/example/starwars/service/test/ResolverApiUnitTests.kt", "field_batch_resolver_example", lang="kotlin") }}

---

## Node resolver

`FilmNodeResolver` fetches a `Film` by its `GlobalID`. The test uses `runNodeResolver` and
builds the ID with `globalIDFor(Film.Reflection, internalId)`. The `id` property on the spec
is required.

{{ codetag("demoapps/starwars/src/test/kotlin/com/example/starwars/service/test/QueryResolverUnitTests.kt", "test_node_resolver_example", lang="kotlin") }}

---

## Node batch resolver

`CharacterNodeResolver` supports batch resolution. The test uses `runNodeBatchResolver`, passing
a list of `GlobalID`s as `ids`. Call `.get()` on each result to extract the resolved object.

{{ codetag("demoapps/starwars/src/test/kotlin/com/example/starwars/service/test/CharacterResolverUnitTests.kt", "character_node_resolver_multiple_ids", lang="kotlin") }}

---

## Mutation resolver

`CreateCharacterMutation` creates a new character. The test uses `runMutationFieldResolver`,
building the input and arguments objects with `CreateCharacterInput.of(context)` and
`Mutation_CreateCharacter_Arguments.of(context)`.

{{ codetag("demoapps/starwars/src/test/kotlin/com/example/starwars/service/test/ResolverApiUnitTests.kt", "mutation_resolver_example", lang="kotlin") }}
