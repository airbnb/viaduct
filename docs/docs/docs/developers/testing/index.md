---
title: Testing
description: Testing Tenant Module Code
---


## Overview

=== "Kotlin"

    At their core, resolvers are functions: they take inputs and produce outputs. That makes them a natural fit for straightforward JUnit-style unit tests.

    The catch is that resolver inputs are highly stylized. Rather than plain method parameters, a resolver receives an `ExecutionContext` whose shape — which arguments are present, what the parent object looks like, what query values are available — is determined by the resolver's `@Resolver` annotation and the schema fields it serves. Constructing a valid `ExecutionContext` by hand is tedious and error-prone.

    `ResolverTestBase` removes that friction. It provides a type-safe DSL for building `ExecutionContext` values that exactly match what a given resolver expects, so tests stay focused on behavior rather than wiring.

    Consider the following schema, where `FooLabelResolver` derives `label` from the
    parent's `name`:

    ```graphql
    type Foo {
      name: String
      label: String @resolver
    }
    ```

    A minimal example:

    ```kotlin
    @OptIn(ExperimentalApi::class)
    class FooLabelResolverTest : ResolverTestBase() {
        @Test
        fun `returns label`() = runTest {
            val result = runFieldResolver(FooLabelResolver()) {
                objectValue = Foo.of(context) { name("bar") }
            }
            assertEquals("bar", result)
        }
    }
    ```

    The sections below cover each resolver type and the full set of available spec properties.

=== "Java"

    Java has a separate isolated harness, `viaduct.java.api.testing.ResolverTestBase`. Construct it with an `EngineSchema`, supply typed inputs, and await the returned future in your JUnit test. It does not start HTTP or dependency injection and does not run other resolvers to populate your inputs.

    For a generated `Foo` with `name` and resolver-owned `label`, a test has this shape:

    ```java
    @Test
    void returnsLabel() {
      Foo foo = Foo.builder(test.context()).name("bar").build();
      var inputs = new ResolverTestBase.FieldInputs<Foo, Query, Arguments.NoArguments>()
          .objectValue(foo);

      assertThat(test.runFieldResolver(new FooLabelResolver(), inputs).join()).isEqualTo("bar");
    }
    ```

    The Java harness supports ordinary and batched field and node resolvers, mutation fields, request context, and prepared query / mutation futures. References require engine or application execution, and selective resolvers are currently disabled in Java.

---


## `ResolverTestBase` *(Experimental)*

=== "Kotlin"

    > **Note:** These APIs are marked `@ExperimentalApi` and may change in future releases.

=== "Java"

    The Java helper is a test fixture in the Java Tenant API module, not the Kotlin `ResolverTestBase` DSL. The constructor and supported inputs are described below. See its [source](https://github.com/airbnb/viaduct/blob/main/core/javaapi/api/src/testFixtures/java/viaduct/java/api/testing/ResolverTestBase.java) and [JUnit examples](https://github.com/airbnb/viaduct/blob/main/core/javaapi/api/src/test/java/viaduct/java/api/testing/ResolverTestBaseTest.java).

### Setup

=== "Kotlin"

    Extend `ResolverTestBase` — no additional configuration needed. Resolvers run in isolation with no HTTP, DI framework, or Spring startup. The schema loads automatically from classpath resources.

    ```kotlin
    @OptIn(ExperimentalApi::class)
    class FooResolverTest : ResolverTestBase() {
        @Test
        fun `name of test`() = runTest {
            val result = runFieldResolver(FooLabelResolver()) {
                objectValue = ...
                arguments = ...
            }
            assertEquals(..., result)
        }
    }
    ```

    The spec lambda (`{ objectValue = ..., arguments = ... }`) is how you supply the resolver's inputs. The next section explains how to construct those values.

=== "Java"

    Add the Java Tenant API **test-fixtures variant** to your test dependencies at the same Viaduct version as the application, together with JUnit 5 and AssertJ. The helper lives in `core/javaapi/api/src/testFixtures`; it is not part of the main API jar.

    Construct `ResolverTestBase` instead of extending it. Pass the `EngineSchema` for the resolver's schema; Java does not load it automatically from the classpath. For a small unit test, the schema can be constructed explicitly, following the Java harness's own tests:

    ```java
    final class FooLabelResolverTest {
      private static final GraphQLObjectType FOO = GraphQLObjectType.newObject()
          .name("Foo")
          .field(GraphQLFieldDefinition.newFieldDefinition().name("name").type(Scalars.GraphQLString))
          .field(GraphQLFieldDefinition.newFieldDefinition().name("label").type(Scalars.GraphQLString))
          .build();
      private static final EngineSchema SCHEMA = new EngineSchema(
          GraphQLSchema.newSchema()
              .query(GraphQLObjectType.newObject().name("Query")
                  .field(GraphQLFieldDefinition.newFieldDefinition().name("foo").type(FOO)))
              .build());
      private final ResolverTestBase test = new ResolverTestBase(SCHEMA);

      @Test
      void returnsLabel() {
        var inputs = new ResolverTestBase.FieldInputs<Foo, Query, Arguments.NoArguments>()
            .objectValue(Foo.builder(test.context()).name("bar").build());
        assertThat(test.runFieldResolver(new FooLabelResolver(), inputs).join()).isEqualTo("bar");
      }
    }
    ```

    `Foo`, `Query`, and the resolver base are generated from your tenant module's schema. Import those generated classes from your module. The subsequent examples assume `test` was initialized with the schema for the resolver being tested, including that field's arguments and any node or mutation types. A full application schema avoids maintaining a duplicate test schema.

### Constructing Test Inputs

=== "Kotlin"

    The `context: ExecutionContext` property is available in every test. Use it with the `Type.of(context) { … }` DSL to build GRT objects:

    ```kotlin
    val foo = Foo.of(context) { name("bar") }
    ```

    The builder form also works but is more verbose:

    ```kotlin
    val foo = Foo.Builder(context).name("bar").build()
    ```

    Fields that take arguments have a generated arguments type, built the same way — see [With arguments](#with-arguments).

=== "Java"

    Use `test.context()` with the generated Java builders:

    ```java
    Foo foo = Foo.builder(test.context()).name("bar").build();
    ```

    `FieldInputs<O, Q, A>` matches the resolver's parent object, Query, and arguments types. Set `objectValue` when the resolver declares an object-value fragment and `queryValue` for a query-value fragment. The harness rejects absent required values and values supplied when no corresponding fragment exists. Build the selected fields explicitly; the harness does not fetch dependencies or apply fragments for you.

### Building GlobalIDs

=== "Kotlin"

    ```kotlin
    val id = globalIDFor(Foo.Reflection, "some-internal-id")
    ```

    Use `globalIDFor` directly on the test class — it is a convenience wrapper around `context.globalIDFor`.

=== "Java"

    Use the Java type descriptor and the test helper:

    ```java
    GlobalID<Foo> id = test.globalIDFor(Type.ofClass(Foo.class), "some-internal-id");
    ```

    Import `viaduct.java.api.globalid.GlobalID` and `viaduct.java.api.reflect.Type`. The node type must exist in the supplied schema. The default codec can be replaced by passing a `GlobalIDCodec` as the second constructor argument.

### APIs

=== "Kotlin"

    Each method runs a specific resolver type via a typed **spec** lambda. The compiler enforces that you only set properties that match the resolver's declared types.

    | Method | Spec properties |
    |---|---|
    | `runFieldResolver` | `objectValue`, `queryValue`, `arguments`, `contextQueryValues`, `referenceSpy` |
    | `runFieldBatchResolver` | `objectValues`, `queryValues`, `referenceSpy` |
    | `runNodeResolver` | `id` *(required)*, `referenceSpy` |
    | `runNodeBatchResolver` | `ids`, `referenceSpy` |
    | `runMutationFieldResolver` | `queryValue`, `arguments`, `contextQueryValues`, `contextMutationValues`, `referenceSpy` |

    Every spec also has `requestContext` for seeding header/scope data.

=== "Java"

    Java methods take explicit inputs and return `CompletableFuture` values:

    | Method | Input | Result |
    |---|---|---|
    | `runFieldResolver` | `FieldInputs<O, Q, A>` | `CompletableFuture<T>` |
    | `runFieldBatchResolver` | `List<FieldInputs<O, Q, A>>` | `CompletableFuture<List<FieldValue<T>>>` |
    | `runNodeResolver` | `NodeInputs<R>` with an ID | `CompletableFuture<R>` |
    | `runNodeBatchResolver` | `List<NodeInputs<R>>` | `CompletableFuture<List<FieldValue<R>>>` |
    | `runMutationFieldResolver` | `FieldInputs<O, Q, A>` | `CompletableFuture<T>` |

    All inputs support `requestContext(value)`, `onQuery(...)`, and `onMutation(...)`. Mutation stubs can only be executed by mutation resolvers. The harness invokes generated resolver adapters and rejects a method that does not match the resolver's batching or mutation kind. Tests may use `join()` or a bounded `get(...)`; application resolver code should compose futures.

---


## Field resolver

=== "Kotlin"

    Use `runFieldResolver` and set `objectValue` to a GRT built with `Type.of(context)`.

    ```kotlin
    @Test
    fun `returns label`() = runTest {
        val result = runFieldResolver(FooLabelResolver()) {
            objectValue = Foo.of(context) { name("bar") }
        }
        assertEquals("bar", result)
    }
    ```

=== "Java"

    Supply the fields in the resolver's required selection set as `objectValue`:

    ```java
    @Test
    void returnsLabel() {
      var inputs = new ResolverTestBase.FieldInputs<Foo, Query, Arguments.NoArguments>()
          .objectValue(Foo.builder(test.context()).name("bar").build());
      String result = test.runFieldResolver(new FooLabelResolver(), inputs).join();
      assertThat(result).isEqualTo("bar");
    }
    ```

### With arguments

=== "Kotlin"

    Set `arguments` alongside `objectValue` using the generated `Type_Field_Arguments.of(context)` DSL.

    Schema:

    ```graphql
    type Foo {
      name: String
      label(uppercase: Boolean): String @resolver
    }
    ```

    ```kotlin
    @Test
    fun `returns uppercase label when flag is set`() = runTest {
        val result = runFieldResolver(FooLabelResolver()) {
            objectValue = Foo.of(context) { name("bar") }
            arguments = Foo_Label_Arguments.of(context) { uppercase(true) }
        }
        assertEquals("BAR", result)
    }
    ```

=== "Java"

    Build the generated Java arguments type and pass it with the object value. The field in the supplied schema must declare the same argument:

    ```java
    @Test
    void returnsUppercaseLabel() {
      var inputs = new ResolverTestBase.FieldInputs<Foo, Query, Foo_Label_Arguments>()
          .objectValue(Foo.builder(test.context()).name("bar").build())
          .arguments(Foo_Label_Arguments.builder(test.context()).uppercase(true).build());

      assertThat(test.runFieldResolver(new FooLabelResolver(), inputs).join()).isEqualTo("BAR");
    }
    ```

    For fields with arguments, Java requires an explicit arguments object, even when every argument is optional. For fields without arguments, leave `arguments` unset or use `Arguments.None`.

### Mocking `ctx.query`

=== "Kotlin"

    Set `contextQueryValues` to stub the results of `ctx.query(...)` calls the resolver
    makes during execution. Pass a single `Query` built with `Query.of(context)` to return
    the same value for every query the resolver issues; a `ctx.query(...)` call with no
    stubbed value throws.

    Schema:

    ```graphql
    type Query {
      node(id: ID!): Node @resolver
    }
    ```

    ```kotlin
    @Test
    fun `reads label from ctx query`() = runTest {
        val queryValue = Query.of(context) {
            node(Foo.of(context) { name("bar") })
        }

        val result = runFieldResolver(FooLabelResolver()) {
            contextQueryValues = listOf(queryValue)
        }

        assertEquals("bar", result)
    }
    ```

    To return different values depending on the selection set the resolver requests, wrap
    each `Query` in a `QueryForSelection(selections, query)`. Lookups match on the rendered
    selection set; an unwrapped `Query` acts as the fallback for any selection. At most one
    unwrapped `Query` may be supplied.

    ```kotlin
    contextQueryValues = listOf(
        QueryForSelection("node { id }", queryWithId),
        QueryForSelection("node { label }", queryWithLabel),
    )
    ```

=== "Java"

    Prepare an exact query result using `onQuery(document, variables, resultClass, future)`. For an annotated operation, use `operation.getOperationText()`; for a string overload, use the same string the resolver passes. Lookups match query versus mutation, exact document text, variables, and target GRT class. There is no wildcard fallback.

    For a resolver that calls `ctx.query(LabelQuery.INSTANCE, Map.of("id", id))`:

    ```java
    var queryResult = Query.builder(test.context())
        .node(Foo.builder(test.context()).name("bar").build())
        .build();
    var inputs = new ResolverTestBase.FieldInputs<Foo, Query, Arguments.NoArguments>()
        .onQuery(
            LabelQuery.INSTANCE.getOperationText(),
            Map.of("id", id),
            Query.class,
            CompletableFuture.completedFuture(queryResult));

    assertThat(test.runFieldResolver(new FooLabelResolver(), inputs).join()).isEqualTo("bar");
    ```

    Also supply any object, query-value, or argument inputs the resolver declares. An unstubbed call fails. Pass an incomplete `CompletableFuture` to verify asynchronous completion or `CompletableFuture.failedFuture(error)` to exercise failure handling. These stubs return prepared values; they do not validate or execute GraphQL.

### Verifying root field references (`ctx.ref`)

=== "Kotlin"

    Set `referenceSpy` to a `ReferenceSpy()` to record the [root field references](../resolvers/root_field_references.md)
    the resolver creates during execution with `ctx.ref(...)`. A root field reference points at a
    field on a factory (namespace) type reachable from the root — for example `LabelFactory.format`,
    exposed via `Query.labelFactory`.

    References are not stubbed. The resolver receives an unresolved reference, exactly as it would in
    production, and that reference holds no data — reading a field from it throws. Assertions therefore
    run after the resolver returns, against the calls the spy recorded.

    Schema:

    ```graphql
    extend type Query {
      labelFactory: LabelFactory
    }

    type LabelFactory @namespaceType {
      format(text: String!): FormattedLabel @resolver
    }
    ```

    `assertCalledExactly` takes the same generated factory calls production code uses, and compares
    them in order, including repeats:

    ```kotlin
    @Test
    fun `formats the label through the label factory`() = runTest {
        val spy = ReferenceSpy()

        runFieldResolver(FooLabelResolver()) {
            objectValue = Foo.of(context) { name("bar") }
            referenceSpy = spy
        }

        spy.assertCalledExactly(
            LabelFactory.format { text("bar") },
        )
    }
    ```

    When the expectation cannot name an exact value — a substring of a string the resolver assembled,
    say — assert a property of the recorded arguments instead. `assertCallArgumentsOf` selects every
    call to one root field, in call order, and infers the arguments type from the generated field:

    ```kotlin
    spy.assertCallArgumentsOf(LabelFactory.Fields.format) { args ->
        args.any { it.text.contains("bar") }
    }
    ```

    `assertCallArgumentsOfFirst` is the same for a single call, and fails when the resolver created
    none:

    ```kotlin
    spy.assertCallArgumentsOfFirst(LabelFactory.Fields.format) { it.text == "bar" }
    ```

    A resolver that creates a reference without a `referenceSpy` to record it fails with an error
    naming the field it referenced.

=== "Java"

    The Java isolated harness does not support `ctx.ref(GlobalID)` or `ctx.ref(RootFieldCall)`; both throw `UnsupportedOperationException`. There is no Java `ReferenceSpy` counterpart.

    Test node and root field references through engine-level feature-app execution or an application integration test. Assert the GraphQL response at the referenced field, including the target resolver's arguments and fields. The [Java root field reference contract test](https://github.com/airbnb/viaduct/blob/main/core/javaapi/runtime/src/test/java/viaduct/tenant/runtime/execution/rootfieldref/JavaRootFieldRefContractTest.java) demonstrates nested references and argument conversion. The [Star Wars Java integration test](https://github.com/airbnb/viaduct/blob/main/demoapps/starwars-java/src/test/java/com/example/starwars/service/StarWarsJavaIntegrationTest.java) covers references across tenant modules.

---


## Field batch resolver

=== "Kotlin"

    Use `runFieldBatchResolver` and pass a list of objects as `objectValues`. Call `.get()` on each result to extract the value.

    Schema:

    ```graphql
    type Foo {
      name: String
      label: String @resolver(isBatching: true)
    }
    ```

    ```kotlin
    @Test
    fun `resolves label for each foo in batch`() = runTest {
        val foos = listOf("a", "b", "c").map { Foo.of(context) { name(it) } }

        val results = runFieldBatchResolver(FooLabelBatchResolver()) {
            objectValues = foos
        }

        assertEquals(3, results.size)
        results.forEach { fv -> assertNotNull(fv.get()) }
    }
    ```

    `objectValues.size` must equal `queryValues.size` when `queryValues` is provided.

=== "Java"

    Pass one `FieldInputs` object per input context. Although Java production field batches return a context-keyed map, the test harness returns `FieldValue` results in the **input order**:

    ```java
    @Test
    void resolvesEveryFoo() {
      var inputs = List.of("a", "b", "c").stream()
          .map(name -> new ResolverTestBase.FieldInputs<Foo, Query, Arguments.NoArguments>()
              .objectValue(Foo.builder(test.context()).name(name).build()))
          .toList();

      var results = test.runFieldBatchResolver(new FooLabelBatchResolver(), inputs).join();
      assertThat(results).hasSize(3);
      assertThat(results.stream().map(value -> value.get()).toList()).containsExactly("a", "b", "c");
    }
    ```

    Adjust expected labels for your resolver's behavior. Supply query values and arguments on each input if required. Test missing / foreign map keys and per-item `FieldValue.ofError` paths where your resolver supports them.

---


## Node resolver

=== "Kotlin"

    Use `runNodeResolver` and set `id` using `globalIDFor` — this property is required.

    Schema:

    ```graphql
    type Foo implements Node @resolver {
      id: ID!
      name: String
      label: String
    }
    ```

    ```kotlin
    @Test
    fun `fetches foo by id`() = runTest {
        val result = runNodeResolver(FooNodeResolver()) {
            id = globalIDFor(Foo.Reflection, "42")
        }
        assertEquals("42", result.getIdOrThrow())
    }
    ```

=== "Java"

    Create a `NodeInputs` with an ID of the resolver's node type:

    ```java
    @Test
    void fetchesFoo() {
      var id = test.globalIDFor(Type.ofClass(Foo.class), "42");
      var result = test.runNodeResolver(
          new FooNodeResolver(), new ResolverTestBase.NodeInputs<Foo>(id)).join();

      assertThat(result.getId().getInternalID()).isEqualTo("42");
    }
    ```

    Use `requestContext(...)` on the input when the resolver reads request-scoped data. The harness does not populate node fields; arrange the resolver's repository or service dependencies separately.

---


## Node batch resolver

=== "Kotlin"

    Use `runNodeBatchResolver` and pass a list of `GlobalID`s as `ids`. Call `.get()` on each result to extract the resolved object.

    Schema:

    ```graphql
    type Foo implements Node @resolver(isBatching: true) {
      id: ID!
      name: String
      label: String
    }
    ```

    ```kotlin
    @Test
    fun `fetches multiple foos by id`() = runTest {
        val ids = listOf("1", "2").map { globalIDFor(Foo.Reflection, it) }

        val results = runNodeBatchResolver(FooNodeResolver()) {
            this.ids = ids
        }

        assertEquals(2, results.size)
    }
    ```

=== "Java"

    Supply one `NodeInputs` per ID. The harness converts the context-keyed production map to a list in the same order as these inputs:

    ```java
    @Test
    void fetchesMultipleFoos() {
      var inputs = List.of("1", "2").stream()
          .map(id -> new ResolverTestBase.NodeInputs<Foo>(
              test.globalIDFor(Type.ofClass(Foo.class), id)))
          .toList();

      var results = test.runNodeBatchResolver(new FooNodeResolver(), inputs).join();
      assertThat(results).hasSize(2);
      assertThat(results.get(0).get().getId().getInternalID()).isEqualTo("1");
      assertThat(results.get(1).get().getId().getInternalID()).isEqualTo("2");
    }
    ```

    Call `FieldValue.get()` to obtain each successful value or surface its per-node failure. Use engine-level tests to verify batching schedules or deduplication; the isolated harness invokes the supplied batch directly.

---


## Mutation resolver

=== "Kotlin"

    Use `runMutationFieldResolver` and build the input and arguments with the generated `.of(context)` DSL.

    Schema:

    ```graphql
    type Mutation {
      createFoo(input: CreateFooInput!): Foo @resolver
    }
    ```

    ```kotlin
    @Test
    fun `creates foo and returns it`() = runTest {
        val input = CreateFooInput.of(context) { label("bar") }

        val result = runMutationFieldResolver(CreateFooMutation()) {
            arguments = Mutation_CreateFoo_Arguments.of(context) { input(input) }
        }

        assertEquals("bar", result!!.getLabelOrThrow())
    }
    ```

=== "Java"

    Use `runMutationFieldResolver` with the generated arguments type. Build input objects using `test.context()`:

    ```java
    @Test
    void createsFoo() {
      var input = CreateFooInput.builder(test.context()).label("bar").build();
      var arguments = Mutation_CreateFoo_Arguments.builder(test.context()).input(input).build();
      var inputs = new ResolverTestBase.FieldInputs<Mutation, Query, Mutation_CreateFoo_Arguments>()
          .arguments(arguments);

      var result = test.runMutationFieldResolver(new CreateFooMutation(), inputs).join();
      assertThat(result.getLabel()).isEqualTo("bar");
    }
    ```

    Prepare submutation results with `inputs.onMutation(operation.getOperationText(), variables, Mutation.class, future)` and subqueries with `onQuery`. The harness permits mutation execution only through `runMutationFieldResolver`. A mutation returning a node reference needs an engine or application test because isolated references are unsupported.

---

For worked examples against a real schema, see the [Star Wars Testing examples](../../../getting_started/starwars/testing/index.md).
