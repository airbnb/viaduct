package viaduct.engine.runtime.execution

import graphql.execution.DataFetcherResult
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.EngineConfiguration
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.mocks.MockFieldBatchResolverExecutor
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.MockTenantModuleBootstrapper
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.createRSS
import viaduct.engine.api.mocks.featureTestDefault
import viaduct.engine.api.mocks.fetchAs
import viaduct.engine.api.mocks.getAs
import viaduct.engine.api.spi.FieldSelectivityProvider
import viaduct.graphql.test.assertJson
import viaduct.graphql.test.assertMatches

interface SelectiveFieldExecutionContract : QueryExecutionContract {
    val selectiveFieldConfig: EngineConfiguration
        get() = EngineConfiguration.featureTestDefault

    @Test
    fun `nested typename is available when selective resolver omits it`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo { bar: Bar }
                type Bar { x: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, _, _ ->
                            createEngineObjectData(
                                schema.schema.getObjectType("Foo"),
                                mapOf("bar" to createEngineObjectData(schema.schema.getObjectType("Bar"), emptyMap())),
                            )
                        }
                    )
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout("{ foo { bar { kind: __typename } } }")
                .assertJson("{data: {foo: {bar: {kind: \"Bar\"}}}}")
        }
    }

    @Test
    fun `multiple query selections on selective field`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { b: Int, foo: Foo }
                type Foo { x: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, _, _ ->
                            createEngineObjectData(schema.schema.getObjectType("Foo"), mapOf("x" to 2))
                        }
                    )
                }
            }

            field("Query" to "b") {
                resolver {
                    querySelections("foo { x }, foo { x }")
                    fn { _, _, query, _, _ ->
                        query.fetchAs<EngineObjectData>("foo").fetchAs<Int>("x") * 3
                    }
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout("{b}")
                .assertJson("{data: {b: 6}}")
        }
    }

    @Test
    fun `selective resolver materializes list items`() {
        MockTenantModuleBootstrapper(
            """
            extend type Query { foo: [Foo] }
            type Foo { x:Int, y:Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, sels, _ ->
                            listOf(2, 3).map { x ->
                                createEngineObjectData(
                                    schema.schema.getObjectType("Foo"),
                                    buildMap {
                                        if (sels!!.containsField("Foo", "x")) {
                                            put("x", x)
                                        }
                                    }
                                )
                            }
                        }
                    )
                }
            }

            field("Foo" to "y") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        objectSelectionSet = createRSS("Foo", "x"),
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, obj, _, _, _ -> obj.fetchAs<Int>("x") * 5 }
                    )
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout("{ foo { y } }")
                .assertJson("{data: {foo: [{y: 10}, {y: 15}]}}")
        }
    }

    @Test
    fun `selective resolver materializes nested list items`() {
        MockTenantModuleBootstrapper(
            """
            extend type Query { foo: [[Foo]] }
            type Foo { x:Int, y:Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, sels, _ ->
                            listOf(
                                listOf(2, 3),
                                listOf(5, 7),
                            ).map { xs ->
                                xs.map { x ->
                                    createEngineObjectData(
                                        schema.schema.getObjectType("Foo"),
                                        buildMap {
                                            if (sels!!.containsField("Foo", "x")) {
                                                put("x", x)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    )
                }
            }

            field("Foo" to "y") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        objectSelectionSet = createRSS("Foo", "x"),
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, obj, _, _, _ ->
                            obj.fetchAs<Int>("x") * 11
                        }
                    )
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout("{ foo { y } }")
                .assertJson("{data: {foo: [[{y: 22}, {y: 33}], [{y: 55}, {y: 77}]]}}")
        }
    }

    @Test
    fun `rss selects sibling fields on selective object`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo { x: Int, y: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, sels, _ ->
                            createEngineObjectData(
                                schema.schema.getObjectType("Foo"),
                                buildMap {
                                    if (sels!!.containsField("Foo", "y")) {
                                        put("y", 2)
                                    }
                                }
                            )
                        }
                    )
                }
            }

            field("Foo" to "x") {
                resolver {
                    objectSelections("y")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("y") * 3 }
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout("{ foo { x } }")
                .assertJson("{data: {foo: {x: 6}}}")
        }
    }

    @Test
    fun `selective resolver is run once for simple queries`() {
        val fooCalls = AtomicInteger()

        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo { x: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, _, _ ->
                            fooCalls.incrementAndGet()
                            createEngineObjectData(schema.schema.getObjectType("Foo"), emptyMap())
                        }
                    )
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout("{ foo { __typename } }")
                .assertJson("{data: {foo: {__typename: \"Foo\"}}}")
        }

        assertEquals(1, fooCalls.get())
    }

    @Test
    fun `field selectivity provider enables selective execution`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo { x: String, y: String, z: String, w: String }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = false,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, selections, _ ->
                            createEngineObjectData(
                                schema.schema.getObjectType("Foo"),
                                buildMap {
                                    if (selections!!.containsField("Foo", "z")) {
                                        put("z", "z-value")
                                    }
                                    if (selections.containsField("Foo", "w")) {
                                        put("w", "w-value")
                                    }
                                },
                            )
                        },
                    )
                }
            }

            field("Foo" to "x") {
                resolver {
                    objectSelections("z")
                    fn { _, objectValue, _, _, _ ->
                        check(objectValue.fetchAs<String>("z") == "z-value")
                        "x-value"
                    }
                }
            }

            field("Foo" to "y") {
                resolver {
                    objectSelections("w")
                    fn { _, objectValue, _, _, _ ->
                        check(objectValue.fetchAs<String>("w") == "w-value")
                        "y-value"
                    }
                }
            }
        }.toEngineTestModule().runContractTest(
            engineConfig =
                selectiveFieldConfig.copy(
                    fieldSelectivityProvider =
                        FieldSelectivityProvider { coordinate ->
                            coordinate == ("Query" to "foo")
                        }
                )
        ) {
            withTimeout("{ foo { x y } }")
                .assertJson("{data: {foo: {x: \"x-value\", y: \"y-value\"}}}")
        }
    }

    @Test
    fun `statically skipped fragment spread does not shadow spreads of the same fragment`() {
        // The first Frag spread is statically skipped and creates a stub fragment definition.
        // The second Frag spread must replace that stub so the RSS still plans __typename.
        MockTenantModuleBootstrapper("extend type Query { x: Int }") {
            field("Query" to "x") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        objectSelectionSet = createRSS(
                            "Query",
                            """
                                fragment Main on Query {
                                  ...Frag @include(if: false)
                                  ... on Query { ...Frag }
                                }

                                fragment Frag on Query {
                                  __typename @include(if: true)
                                }
                            """.trimIndent()
                        ),
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, obj, _, _, _ ->
                            obj.fetchAs<String>("__typename")
                            1
                        }
                    )
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout("{ x }")
                .assertJson("{data: {x: 1}}")
        }
    }

    @Test
    fun `fully skipped selective field still resolves rss reads`() {
        // The client selects foo with one aliased selection removed by @skip(if: true).
        // In Engine1, this leaves an empty first materialization; Query.b's RSS then needs foo { y x }.
        // Mat must refetch that coverage. Retaining the skipped alias would route y to the empty
        // first result and produce a spurious null. Runtime2 closes the combined demand up front.
        MockTenantModuleBootstrapper(
            """
                extend type Query { b: Int, foo: Foo }
                type Foo { x: Int, y: Int!, z: Int }
            """.trimIndent()
        ) {
            field("Query" to "b") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        objectSelectionSet = createRSS(
                            "Query",
                            """
                            fragment Main on Query {
                              ...Fragment_R
                              ...Fragment_R
                            }

                            fragment Fragment_R on Query {
                              foo {
                                ... on Foo {
                                  y
                                  x
                                }
                              }
                              foo @skip(if: false) {
                                ... on Foo {
                                  aliasedZ: z @include(if: false)
                                }
                              }
                              foo {
                                ... {
                                  __typename
                                }
                              }
                            }
                            """.trimIndent()
                        ),
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, obj, _, _, _ ->
                            obj.fetchAs<EngineObjectData>("foo").fetchAs<Int>("y") * 5
                        }
                    )
                }
            }

            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, sels, _ ->
                            createEngineObjectData(
                                schema.schema.getObjectType("Foo"),
                                buildMap {
                                    if (sels!!.containsField("Foo", "x")) put("x", 1)
                                    if (sels.containsField("Foo", "y")) put("y", 2)
                                    if (sels.containsField("Foo", "z")) put("z", 3)
                                }
                            )
                        }
                    )
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout(
                """
                    {
                      b
                      foo {
                        aliasedY: y @skip(if: true)
                      }
                    }
                """.trimIndent()
            ).assertJson("{data: {b: 10, foo: {}}}")
        }
    }

    @Test
    fun `concrete field resolves after typename`() {
        MockTenantModuleBootstrapper(
            """
                interface Item { x:Int }
                type Foo implements Item { x:Int, y:Int }
                type Bar implements Item { x:Int }
                extend type Query { item:Item, trigger:Int }
            """.trimIndent()
        ) {
            field("Query" to "item") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, sels, _ ->
                            createEngineObjectData(
                                schema.schema.getObjectType("Foo"),
                                if (sels!!.containsField("Foo", "y")) {
                                    mapOf("y" to 2)
                                } else {
                                    emptyMap()
                                },
                            )
                        }
                    )
                }
            }

            field("Query" to "trigger") {
                resolver {
                    objectSelections("item { __typename }")
                    fn { _, _, _, _, _ -> 3 }
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            runQuery("{ trigger item { ... on Foo { y } } }")
                .assertJson("{data: {trigger: 3, item: {y: 2}}}")
        }
    }

    @Test
    fun `materialization preserves client directive variables`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo { bar: Bar }
                type Bar { x: Int, y: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, sels, _ ->
                            val ySelected = sels!!
                                .selectionSetForField("Foo", "bar")
                                .containsField("Bar", "y")
                            createEngineObjectData(
                                schema.schema.getObjectType("Foo"),
                                mapOf(
                                    "bar" to createEngineObjectData(
                                        schema.schema.getObjectType("Bar"),
                                        if (ySelected) {
                                            mapOf("y" to 2)
                                        } else {
                                            emptyMap()
                                        },
                                    )
                                ),
                            )
                        }
                    )
                }
            }

            field("Bar" to "x") {
                resolver {
                    objectSelections("y")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("y") * 3 }
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout(
                """
                    | query(${'$'}includeBar: Boolean!) {
                    |   foo {
                    |     bar @include(if: ${'$'}includeBar) {
                    |       x
                    |     }
                    |   }
                    | }
                """.trimMargin(),
                variables = mapOf("includeBar" to true),
            ).assertJson("{data: {foo: {bar: {x: 6}}}}")
        }
    }

    @Test
    fun `aliased rss reads materialized value by schema field name`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo { x: Int, y: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, sels, _ ->
                            createEngineObjectData(
                                schema.schema.getObjectType("Foo"),
                                buildMap {
                                    if (sels!!.containsField("Foo", "y")) {
                                        put("y", 2)
                                    }
                                }
                            )
                        }
                    )
                }
            }

            field("Foo" to "x") {
                resolver {
                    objectSelections("alias: y")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("alias") * 3 }
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout("{ foo { x } }")
                .assertJson("{data: {foo: {x: 6}}}")
        }
    }

    @Test
    fun `initially null selective result is not rematerialized`() {
        val fooCalls = AtomicInteger()

        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo { x: Int, y: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, _, _ ->
                            fooCalls.incrementAndGet()
                            null
                        }
                    )
                }
            }

            field("Foo" to "x") {
                resolver {
                    objectSelections("y")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("y") * 3 }
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout("{ foo { x } }")
                .assertJson("{data: {foo: null}}")
        }

        assertEquals(1, fooCalls.get())
    }

    @Test
    fun `selective child object can read its non-selective parent`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo { x: Int, bar: Bar }
                type Bar { parent: Foo @parent, y: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("Foo"), mapOf("x" to 2))
                    }
                }
            }

            field("Foo" to "bar") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, _, _ ->
                            createEngineObjectData(schema.schema.getObjectType("Bar"), emptyMap())
                        }
                    )
                }
            }

            field("Bar" to "y") {
                resolver {
                    objectSelections("parent { x }")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<EngineObjectData>("parent").fetchAs<Int>("x")
                    }
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout("{ foo { bar { y } } }")
                .assertJson("{data: {foo: {bar: {y: 2}}}}")
        }
    }

    @Test
    fun `parent traversal resolves a selective child field`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo { baz: Baz, bar: Bar }
                type Baz { x: Int }
                type Bar { parent: Foo @parent, y: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("Foo"), emptyMap())
                    }
                }
            }

            field("Foo" to "baz") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, selections, _ ->
                            createEngineObjectData(
                                schema.schema.getObjectType("Baz"),
                                buildMap {
                                    if (selections!!.containsField("Baz", "x")) {
                                        put("x", 2)
                                    }
                                }
                            )
                        }
                    )
                }
            }

            field("Foo" to "bar") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("Bar"), emptyMap())
                    }
                }
            }

            field("Bar" to "y") {
                resolver {
                    objectSelections("parent { baz { x } }")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<EngineObjectData>("parent")
                            .fetchAs<EngineObjectData>("baz")
                            .fetchAs<Int>("x")
                    }
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout("{ foo { bar { y } } }")
                .assertJson("{data: {foo: {bar: {y: 2}}}}")
        }
    }

    @Test
    fun `non-selective resolver restores parent traversal below selective ancestor`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo { bar: Bar }
                type Bar { x: Int, baz: Baz }
                type Baz { parent: Bar @parent, y: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, _, _ ->
                            createEngineObjectData(schema.schema.getObjectType("Foo"), emptyMap())
                        }
                    )
                }
            }

            field("Foo" to "bar") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("Bar"), mapOf("x" to 2))
                    }
                }
            }

            field("Bar" to "baz") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("Baz"), emptyMap())
                    }
                }
            }

            field("Baz" to "y") {
                resolver {
                    objectSelections("parent { x }")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<EngineObjectData>("parent").fetchAs<Int>("x")
                    }
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout("{ foo { bar { baz { y } } } }")
                .assertJson("{data: {foo: {bar: {baz: {y: 2}}}}}")
        }
    }

    @Test
    fun `covered nested rss reuses selective source`() {
        val fooCalls = AtomicInteger()

        MockTenantModuleBootstrapper(
            """
                extend type Query { foo:Foo }
                type Foo { x:Int, bar:Bar }
                type Bar { y:Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, sels, _ ->
                            fooCalls.incrementAndGet()
                            createEngineObjectData(
                                schema.schema.getObjectType("Foo"),
                                buildMap {
                                    if (sels!!.containsField("Foo", "bar")) {
                                        put("bar", mapOf("y" to 2))
                                    }
                                }
                            )
                        }
                    )
                }
            }

            field("Foo" to "x") {
                resolver {
                    objectSelections("bar { y }")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<EngineObjectData>("bar").fetchAs<Int>("y") * 3
                    }
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout("{ foo { bar { y } x } }")
                .assertJson("{data: {foo: {bar: {y: 2}, x: 6}}}")
        }

        assertEquals(1, fooCalls.get())
    }

    @Test
    fun `null list items remain null while siblings rematerialize`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo { bars: [Bar] }
                type Bar { x: Int, y: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, sels, _ ->
                            val barSelections = sels!!
                                .selectionSetForField("Foo", "bars")
                            val includeY = barSelections.containsField("Bar", "y")
                            createEngineObjectData(
                                schema.schema.getObjectType("Foo"),
                                mapOf(
                                    "bars" to listOf(
                                        createEngineObjectData(
                                            schema.schema.getObjectType("Bar"),
                                            if (includeY) mapOf("y" to 2) else emptyMap(),
                                        ),
                                        null,
                                        createEngineObjectData(
                                            schema.schema.getObjectType("Bar"),
                                            if (includeY) mapOf("y" to 3) else emptyMap(),
                                        ),
                                    )
                                ),
                            )
                        }
                    )
                }
            }

            field("Bar" to "x") {
                resolver {
                    objectSelections("y")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("y") * 5 }
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout("{ foo { bars { x } } }")
                .assertJson("{data: {foo: {bars: [{x: 10}, null, {x: 15}]}}}")
        }
    }

    @Test
    fun `one-level nested rss rematerializes selective parent`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo { bar: Bar }
                type Bar { x:Int, y:Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, sels, _ ->
                            val fooData = mutableMapOf<String, Any?>()
                            if (sels!!.containsField("Foo", "bar")) {
                                val barData = mutableMapOf<String, Any?>()
                                if (sels.selectionSetForField("Foo", "bar").containsField("Bar", "y")) {
                                    barData["y"] = 2
                                }
                                fooData["bar"] = createEngineObjectData(schema.schema.getObjectType("Bar"), barData)
                            }
                            createEngineObjectData(schema.schema.getObjectType("Foo"), fooData)
                        }
                    )
                }
            }

            field("Bar" to "x") {
                resolver {
                    objectSelections("y")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("y") * 3 }
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout("{ foo { bar { x } } }")
                .assertJson("{data: {foo: {bar: {x: 6}}}}")
        }
    }

    @Test
    fun `partial rss coverage within a list item type rematerializes every item of that type`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo { bars: [Bar] }
                type Bar { x: Int, y: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, sels, _ ->
                            val ySelected = sels!!
                                .selectionSetForField("Foo", "bars")
                                .containsField("Bar", "y")
                            createEngineObjectData(
                                schema.schema.getObjectType("Foo"),
                                mapOf(
                                    "bars" to listOf(
                                        createEngineObjectData(
                                            schema.schema.getObjectType("Bar"),
                                            mapOf("y" to if (ySelected) 2 else 7),
                                        ),
                                        createEngineObjectData(
                                            schema.schema.getObjectType("Bar"),
                                            if (ySelected) mapOf("y" to 3) else emptyMap(),
                                        ),
                                    )
                                ),
                            )
                        }
                    )
                }
            }

            field("Bar" to "x") {
                resolver {
                    objectSelections("y")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("y") * 5 }
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout("{ foo { bars { x } } }")
                .assertJson("{data: {foo: {bars: [{x: 10}, {x: 15}]}}}")
        }
    }

    @Test
    fun `recursive rss is materialized`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo:Foo }
                type Foo { x:Int, next:Foo }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, sels, _ ->
                            createEngineObjectData(
                                schema.schema.getObjectType("Foo"),
                                if (sels!!.containsField("Foo", "next")) {
                                    mapOf(
                                        "next" to createEngineObjectData(
                                            schema.schema.getObjectType("Foo"),
                                            mapOf(
                                                "next" to createEngineObjectData(
                                                    schema.schema.getObjectType("Foo"),
                                                    mapOf(
                                                        "next" to createEngineObjectData(schema.schema.getObjectType("Foo"), emptyMap())
                                                    )
                                                )
                                            )
                                        )
                                    )
                                } else {
                                    emptyMap()
                                }
                            )
                        }
                    )
                }
            }

            field("Foo" to "x") {
                resolver {
                    objectSelections("next { next { __typename } } next { next { next { __typename } } }")
                    fn { _, obj, _, _, _ ->
                        obj.fetch("next")
                        1
                    }
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout("{ foo { x } }")
                .assertJson("{data: {foo: {x: 1}}}")
        }
    }

    @Test
    fun `resolver hydrates unselected fields in its output selection set`() {
        val initialFooCall = AtomicBoolean(true)

        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo { x: Int, y: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, _, _ ->
                            createEngineObjectData(
                                schema.schema.getObjectType("Foo"),
                                mapOf("y" to if (initialFooCall.getAndSet(false)) 2 else 5)
                            )
                        }
                    )
                }
            }

            field("Foo" to "x") {
                resolver {
                    objectSelections("y")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("y") * 3 }
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout("{ foo { x } }")
                .assertJson("{data: {foo: {x: 6}}}")
        }
    }

    @Test
    fun `returned nested rss coverage reuses selective source`() {
        val fooCalls = AtomicInteger()

        MockTenantModuleBootstrapper(
            """
                extend type Query { foo:Foo }
                type Foo { x:Int, bar:Bar }
                type Bar { y:Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, _, _ ->
                            fooCalls.incrementAndGet()
                            createEngineObjectData(
                                schema.schema.getObjectType("Foo"),
                                mapOf("bar" to mapOf("y" to 2))
                            )
                        }
                    )
                }
            }

            field("Foo" to "x") {
                resolver {
                    objectSelections("bar { y }")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<EngineObjectData>("bar").fetchAs<Int>("y") * 3
                    }
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout("{ foo { x } }")
                .assertJson("{data: {foo: {x: 6}}}")
        }

        assertEquals(1, fooCalls.get())
    }

    @Test
    fun `selective field is skipped in RSS but selected in query`() {
        // This creates two planned executions of Query.a:
        // 1. a direct selection from the outer query
        // 2. a selection through Foo.z's RSS on Query, under the skipped `foo { z }` branch
        //
        // The `foo { z }` branch is skipped by a directive, so the second Query.a plan is created
        // but its branch never runs. Query.a's object RSS then requests `foo { x }`. If
        // RSS resolution looks up that RSS through the global QueryPlanIndex, it can pick
        // the child plan from the never-executed Query.a instead of the current root Query.a. The
        // Query.a resolver then waits forever when it fetches `foo`.
        MockTenantModuleBootstrapper(
            """
                extend type Query { a:Int, foo:Foo }
                type Foo { x:Int, z:Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, sels, _ ->
                            createEngineObjectData(
                                schema.schema.getObjectType("Foo"),
                                buildMap {
                                    if (sels!!.containsField("Foo", "x")) put("x", 2)
                                    if (sels.containsField("Foo", "z")) put("z", 3)
                                },
                            )
                        }
                    )
                }
            }

            field("Query" to "a") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        objectSelectionSet = createRSS("Query", "foo { x }"),
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, obj, _, _, _ ->
                            obj.fetchAs<EngineObjectData>("foo").fetchAs<Int>("x") * 3
                        }
                    )
                }
            }

            field("Foo" to "z") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        querySelectionSet = createRSS("Query", "a"),
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, query, _, _ ->
                            query.fetchAs<Int>("a") * 5
                        }
                    )
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout(
                """
                    query (${"$"}skipFoo: Boolean! = true) {
                      a
                      foo @skip(if: ${"$"}skipFoo) {
                        z
                      }
                    }
                """.trimIndent(),
            ).assertJson("{data: {a: 6}}")
        }
    }

    @Test
    fun `selective field resolver materialization does not repeat type checker`() {
        val fooCheckerCalls = AtomicInteger()

        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo { x: Int, y: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, sels, _ ->
                            createEngineObjectData(
                                schema.schema.getObjectType("Foo"),
                                buildMap {
                                    if (sels!!.containsField("Foo", "y")) {
                                        put("y", 2)
                                    }
                                }
                            )
                        }
                    )
                }
            }

            field("Foo" to "x") {
                resolver {
                    objectSelections("y")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("y") * 3 }
                }
            }

            type("Foo") {
                checker {
                    fn { _, _ ->
                        fooCheckerCalls.incrementAndGet()
                    }
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout("{ foo { x } }")
                .assertJson("{data: {foo: {x: 6}}}")
        }

        assertEquals(1, fooCheckerCalls.get())
    }

    @Test
    fun `type checker aliases field from selective list`() {
        val barCheckerCalls = AtomicInteger()

        MockTenantModuleBootstrapper(
            """
                extend type Query { bars:[Bar] }
                type Bar { x:Int }
            """.trimIndent()
        ) {
            field("Query" to "bars") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, sels, _ ->
                            listOf(1, 2).map { value ->
                                createEngineObjectData(
                                    schema.schema.getObjectType("Bar"),
                                    buildMap {
                                        if (sels!!.containsField("Bar", "x")) put("x", value)
                                    }
                                )
                            }
                        }
                    )
                }
            }

            type("Bar") {
                checker {
                    objectSelections("fields", "x, checked:x")
                    fn { _, _ -> barCheckerCalls.incrementAndGet() }
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout("{ bars { x } }")
                .assertJson(
                    """
                        {
                          data: {
                            bars: [{x:1}, {x:2}]
                          }
                        }
                    """.trimIndent()
                )
        }

        assertEquals(2, barCheckerCalls.get())
    }

    @Test
    fun `type checker failure after selective materialization is reported`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo { x: Int, y: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, sels, _ ->
                            createEngineObjectData(
                                schema.schema.getObjectType("Foo"),
                                buildMap {
                                    if (sels!!.containsField("Foo", "x")) put("x", 1)
                                    if (sels.containsField("Foo", "y")) put("y", 2)
                                }
                            )
                        }
                    )
                }
            }

            type("Foo") {
                checker {
                    objectSelections("fields", "x y")
                    fn { _, _ -> throw SecurityException("foo denied") }
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout("{ foo { y } }").assertMatches {
                "data" to {
                    "foo" to null
                }
                "errors" to arrayOf(
                    {
                        "message" to ".*foo denied.*"
                        "path" to listOf("foo")
                    }
                )
            }
        }
    }

    @Test
    fun `type checker query rss does not rematerialize selective field recursively`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo { y: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, _, _ ->
                            createEngineObjectData(schema.schema.getObjectType("Foo"), emptyMap())
                        },
                    )
                }
            }

            field("Foo" to "y") {
                resolverExecutor {
                    MockFieldBatchResolverExecutor(
                        resolverId = resolverId,
                        batchResolveFn = { selectors, _ ->
                            selectors.associateWith { Result.success(1) }
                        },
                    )
                }
            }

            type("Foo") {
                checker {
                    querySelections("query", "foo { y }")
                    querySelections("typename", "foo { __typename }")
                    fn { _, _ -> }
                }
            }
        }.toEngineTestModule().runContractTest {
            withTimeout("{ foo { __typename } }")
                .assertJson("{data: {foo: {__typename: \"Foo\"}}}")
        }
    }

    @Test
    fun `selective field skipped in query is selected in RSS`() {
        // This creates two planned executions of Foo.x:
        // 1. a direct selection through the outer query's `foo { x }`
        // 2. a selection through Query.b's object RSS `foo { x y }`, under the skipped `b` branch
        //
        // The `b` branch is skipped by a directive, so the second Foo.x plan is created but its
        // branch never runs. Foo.x's query RSS then requests `foo { y }`. If RSS resolution
        // looks up that RSS through the global QueryPlanIndex, it can pick the child plan from
        // the never-executed Foo.x instead of the current root Foo.x. The Foo.x resolver then
        // waits forever when it fetches `foo`.
        MockTenantModuleBootstrapper(
            """
                extend type Query { b: Int, foo: Foo }
                type Foo { x: Int, y: Int, z: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, sels, _ ->
                            createEngineObjectData(
                                schema.schema.getObjectType("Foo")!!,
                                buildMap {
                                    if (sels!!.containsField("Foo", "x")) put("x", 2)
                                    if (sels.containsField("Foo", "y")) put("y", 3)
                                    if (sels.containsField("Foo", "z")) put("z", 5)
                                },
                            )
                        }
                    )
                }
            }

            field("Query" to "b") {
                resolver {
                    objectSelections("foo { x y }")
                    fn { _, obj, _, _, _ ->
                        obj.fetch("foo")
                        2
                    }
                }
            }

            field("Foo" to "x") {
                resolver {
                    querySelections("foo { y }")
                    fn { _, _, query, _, _ ->
                        query.fetchAs<EngineObjectData>("foo").fetchAs<Int>("y") * 5
                    }
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout(
                """
                    query (${"$"}skipB: Boolean! = true) {
                      b @skip(if: ${"$"}skipB)
                      foo {
                        x
                      }
                    }
                """.trimIndent(),
            ).assertJson("{data: {foo: {x: 15}}}")
        }
    }

    @Test
    fun `selective field is selected in one RSS but in a skipped fragment in another RSS`() {
        // Query.b's object RSS fetches `foo.y` before `foo.x`. Resolving `foo.y` first exercises
        // a query RSS where `Query.foo` contains a statically skipped named fragment whose
        // definition selects `Foo.z`. The named fragment is important: the same shape written as
        // a skipped inline fragment does not hang.
        //
        // Query.b then fetches `foo.x`. Foo.x's query RSS requests `foo { z }`, and Foo.z's
        // object RSS requests `y`, so this second path needs an active Query.foo plan for
        // `foo { z }` and then a Foo.z plan that reads `y` from that object.
        //
        // With RSS resolution limited to direct child-plan lookup, this combination can
        // produce an empty or mismatched nested RSS. The selective Query.foo read then waits
        // for a key that no launched child plan will populate. The minimized
        // shape is sensitive: `foo { x y }` passes, replacing the skipped fragment's `z` with
        // `__typename` passes, and removing the indirect `x -> z -> y` dependency passes.
        MockTenantModuleBootstrapper(
            """
                extend type Query { b: Int, foo: Foo }
                type Foo { x: Int, y: Int, z: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, _, _ -> createEngineObjectData(schema.schema.getObjectType("Foo")!!, emptyMap()) }
                    )
                }
            }

            field("Query" to "b") {
                resolverExecutor {
                    val objectRss = createRSS("Query", "foo { y x }")
                    MockFieldUnbatchedResolverExecutor(
                        objectSelectionSet = objectRss,
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, obj, _, _, _ ->
                            val foo = obj.fetchAs<EngineObjectData>("foo")
                            foo.fetchAs<Int>("y") * foo.fetchAs<Int>("x")
                        }
                    )
                }
            }

            field("Foo" to "x") {
                resolverExecutor {
                    val queryRss = createRSS("Query", "foo { z }")
                    MockFieldUnbatchedResolverExecutor(
                        querySelectionSet = queryRss,
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, query, _, _ ->
                            query.fetchAs<EngineObjectData>("foo").fetchAs<Int>("z") * 5
                        }
                    )
                }
            }

            field("Foo" to "z") {
                resolverExecutor {
                    val objectRss = createRSS("Foo", "y")
                    MockFieldUnbatchedResolverExecutor(
                        objectSelectionSet = objectRss,
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, obj, _, _, _ ->
                            obj.fetchAs<Int>("y") * 3
                        }
                    )
                }
            }

            field("Foo" to "y") {
                resolverExecutor {
                    val queryRss = createRSS(
                        "Query",
                        """
                            fragment Main on Query {
                              foo {
                                ...Frag @skip(if: true)
                              }
                            }

                            fragment Frag on Foo {
                              z
                            }
                        """.trimIndent()
                    )
                    MockFieldUnbatchedResolverExecutor(
                        querySelectionSet = queryRss,
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, query, _, _ ->
                            query.fetchAs<EngineObjectData>("foo")
                            2
                        }
                    )
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout("{ b }")
                .assertJson("{data: {b: 60}}")
        }
    }

    @Test
    fun `surplus coverage uses values from the first covering result`() {
        val resultNumber = AtomicInteger()
        val firstResultConsumed = CompletableDeferred<Unit>()

        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo { x: Int, y: Int, z: Int, w: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, _, _ ->
                            createEngineObjectData(
                                schema.schema.getObjectType("Foo")!!,
                                when (resultNumber.getAndIncrement()) {
                                    0 -> emptyMap()
                                    1 -> mapOf("z" to 2, "w" to 3)
                                    else -> {
                                        firstResultConsumed.await()
                                        mapOf("z" to 4, "w" to 5)
                                    }
                                },
                            )
                        }
                    )
                }
            }

            field("Foo" to "x") {
                resolver {
                    objectSelections("z")
                    fn { _, obj, _, _, _ ->
                        val z = obj.fetchAs<Int>("z")
                        firstResultConsumed.complete(Unit)
                        z * 5
                    }
                }
            }

            field("Foo" to "y") {
                resolver {
                    objectSelections("w")
                    fn { _, obj, _, _, _ ->
                        val w = obj.fetchAs<Int>("w")
                        firstResultConsumed.complete(Unit)
                        w * 7
                    }
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout("{ foo { x y } }")
                .assertJson("{data: {foo: {x: 10, y: 21}}}")
        }
    }

    @Test
    fun `missing nested rss coverage rematerializes selective source`() {
        val fooCalls = AtomicInteger()

        MockTenantModuleBootstrapper(
            """
                extend type Query { foo:Foo }
                type Foo { x:Int, bar:Bar }
                type Bar { y: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, sels, _ ->
                            fooCalls.incrementAndGet()
                            val hasBarSelections = sels!!.containsField("Foo", "bar")
                            val barData = if (hasBarSelections) {
                                mapOf<String, Any?>("y" to 2)
                            } else {
                                emptyMap<String, Any?>()
                            }
                            createEngineObjectData(
                                schema.schema.getObjectType("Foo")!!,
                                mapOf("bar" to barData)
                            )
                        }
                    )
                }
            }

            field("Foo" to "x") {
                resolver {
                    objectSelections("bar { y }")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<EngineObjectData>("bar").fetchAs<Int>("y") * 3
                    }
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout("{ foo { x } }")
                .assertJson("{data: {foo: {x: 6}}}")
        }

        assertEquals(2, fooCalls.get())
    }

    @Test
    fun `recursive selective batching field supplies its own rss`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo:Foo }
                type Foo { next:Foo, y:Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("Foo")!!, mapOf("y" to 1))
                    }
                }
            }

            field("Foo" to "next") {
                resolverExecutor {
                    MockFieldBatchResolverExecutor(
                        objectSelectionSet = createRSS("Foo", "y"),
                        isSelective = true,
                        resolverId = resolverId,
                        batchResolveFn = { selectors, _ ->
                            selectors.associateWith { selector ->
                                runCatching {
                                    selector.syncObjectValueGetter().fetch("y")
                                    createEngineObjectData(
                                        schema.schema.getObjectType("Foo")!!,
                                        if (selector.selections!!.selections().isEmpty()) {
                                            mapOf("y" to 1)
                                        } else {
                                            emptyMap()
                                        }
                                    )
                                }
                            }
                        }
                    )
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout("{ foo { next { next { __typename } } } }")
                .assertJson("{data: {foo: {next: {next: {__typename: \"Foo\"}}}}}")
        }
    }

    @Test
    fun `partial rss coverage across list item types rematerializes only uncovered types`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo { bars: [Bar] }
                union Bar = Baz | Qux
                type Baz { x: Int, y: Int }
                type Qux { x: Int, y: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, sels, _ ->
                            val barSelections = sels!!
                                .selectionSetForField("Foo", "bars")
                            val bazYSelected = barSelections.containsField("Baz", "y")
                            val quxYSelected = barSelections.containsField("Qux", "y")
                            createEngineObjectData(
                                schema.schema.getObjectType("Foo")!!,
                                mapOf(
                                    "bars" to listOf(
                                        createEngineObjectData(
                                            schema.schema.getObjectType("Baz")!!,
                                            mapOf("y" to if (bazYSelected) 5 else 2),
                                        ),
                                        createEngineObjectData(
                                            schema.schema.getObjectType("Qux")!!,
                                            if (quxYSelected) mapOf("y" to 3) else emptyMap(),
                                        ),
                                    )
                                ),
                            )
                        }
                    )
                }
            }

            field("Baz" to "x") {
                resolver {
                    objectSelections("y")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("y") * 7 }
                }
            }

            field("Qux" to "x") {
                resolver {
                    objectSelections("y")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("y") * 11 }
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout(
                "{ foo { bars { ... on Baz { x } ... on Qux { x } } } }"
            ).assertJson("{data: {foo: {bars: [{x: 14}, {x: 33}]}}}")
        }
    }

    @Test
    fun `list size changes across materializations leave unmatched items unresolved`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo { bars: [Bar] }
                type Bar { x: Int, y: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, sels, _ ->
                            val ySelected = sels!!
                                .selectionSetForField("Foo", "bars")
                                .containsField("Bar", "y")
                            createEngineObjectData(
                                schema.schema.getObjectType("Foo")!!,
                                mapOf(
                                    "bars" to if (ySelected) {
                                        listOf(createEngineObjectData(schema.schema.getObjectType("Bar")!!, mapOf("y" to 2)))
                                    } else {
                                        listOf(
                                            createEngineObjectData(schema.schema.getObjectType("Bar")!!, emptyMap()),
                                            createEngineObjectData(schema.schema.getObjectType("Bar")!!, emptyMap()),
                                        )
                                    },
                                ),
                            )
                        }
                    )
                }
            }

            field("Bar" to "x") {
                resolver {
                    objectSelections("y")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("y") * 3 }
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout("{ foo { bars { x } } }").assertMatches {
                "data" to {
                    "foo" to {
                        "bars" to arrayOf(
                            { "x" to "6" },
                            { "x" to null },
                        )
                    }
                }
                "errors" to arrayOf(
                    {
                        "path" to listOf("foo", "bars", "1", "x")
                    }
                )
            }
        }
    }

    @Test
    fun `nested list item type changes across materializations report a field error`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo { bars: [Bar] }
                union Bar = Baz | Qux
                type Baz { x: Int, y: Int }
                type Qux { y: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, sels, _ ->
                            val ySelected = sels!!
                                .selectionSetForField("Foo", "bars")
                                .containsField("Baz", "y")
                            createEngineObjectData(
                                schema.schema.getObjectType("Foo")!!,
                                mapOf(
                                    "bars" to listOf(
                                        if (ySelected) {
                                            createEngineObjectData(schema.schema.getObjectType("Qux")!!, mapOf("y" to 2))
                                        } else {
                                            createEngineObjectData(schema.schema.getObjectType("Baz")!!, emptyMap())
                                        }
                                    )
                                ),
                            )
                        }
                    )
                }
            }

            field("Baz" to "x") {
                resolver {
                    objectSelections("y")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("y") * 3 }
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout(
                "{ foo { bars { ... on Baz { x } } } }"
            ).assertMatches {
                "data" to {
                    "foo" to {
                        "bars" to arrayOf(
                            { "x" to null },
                        )
                    }
                }
                "errors" to arrayOf(
                    {
                        "message" to ".*expected object of type `Baz`, found `Qux`.*"
                        "path" to listOf("foo", "bars", "0", "x")
                    }
                )
            }
        }
    }

    @Test
    fun `rss aliases with different arguments remain isolated across materializations`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo:Foo }
                type Foo { x:Int, y:Int, z(x:Int!):Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, sels, _ ->
                            val zSelection = sels!!.selections()
                                .singleOrNull { it.fieldName == "z" }
                            createEngineObjectData(
                                schema.schema.getObjectType("Foo")!!,
                                if (zSelection == null) {
                                    emptyMap()
                                } else {
                                    val x = sels
                                        .argumentsOfSelection("Foo", zSelection.selectionName)
                                        ?.get("x") as Int
                                    mapOf("z" to x * 5)
                                },
                            )
                        }
                    )
                }
            }

            field("Foo" to "x") {
                resolver {
                    objectSelections("a:z(x:2)")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("a") * 7 }
                }
            }

            field("Foo" to "y") {
                resolver {
                    objectSelections("b:z(x:3)")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("b") * 11 }
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout("{ foo { x y } }")
                .assertJson("{data: {foo: {x:70, y:165}}}")
        }
    }

    @Test
    fun `rss variables resolver failure during rematerialization reports a field error`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo { x: Int, y(x: Int!): Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, sels, _ ->
                            createEngineObjectData(
                                schema.schema.getObjectType("Foo")!!,
                                buildMap {
                                    if (sels!!.containsField("Foo", "y")) {
                                        put("y", 2)
                                    }
                                },
                            )
                        }
                    )
                }
            }

            field("Foo" to "x") {
                resolver {
                    objectSelections("y(x: \$x)") {
                        variables("x") { _, _ ->
                            error("rss variables resolver failed")
                        }
                    }
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("y") * 3 }
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout("{ foo { x } }").assertMatches {
                "data" to {
                    "foo" to {
                        "x" to null
                    }
                }
                "errors" to arrayOf(
                    {
                        "message" to ".*rss variables resolver failed.*"
                        "path" to listOf("foo", "x")
                    }
                )
            }
        }
    }

    @Test
    fun `selective-owned sibling supplies required rss variable`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo { bar: Bar }
                type Bar { x: Int, y(z: Int!): Int, z: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, sels, _ ->
                            val fooData = mutableMapOf<String, Any?>()
                            if (sels!!.containsField("Foo", "bar")) {
                                val barSelections = sels.selectionSetForField("Foo", "bar")
                                val barData = mutableMapOf<String, Any?>()
                                if (barSelections.containsField("Bar", "z")) {
                                    barData["z"] = 2
                                }
                                if (barSelections.containsField("Bar", "y")) {
                                    val z = barSelections.argumentsOfSelection("Bar", "y")?.get("z") as Int
                                    barData["y"] = z * 3
                                }
                                fooData["bar"] = createEngineObjectData(schema.schema.getObjectType("Bar")!!, barData)
                            }
                            createEngineObjectData(schema.schema.getObjectType("Foo")!!, fooData)
                        }
                    )
                }
            }

            field("Bar" to "x") {
                resolver {
                    objectSelections("y(z: ${"$"}z)") {
                        variables(
                            "z",
                            rss = createRSS("Bar", "z"),
                        ) { ctx, _ ->
                            mapOf("z" to ctx.objectData.fetchAs<Int>("z"))
                        }
                    }
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("y") * 5 }
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout("{ foo { bar { x } } }")
                .assertJson("{data: {foo: {bar: {x: 30}}}}")
        }
    }

    @Test
    fun `embedded materialization preserves fragment argument variables across child plans`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo { bar(y: Int!): Bar }
                type Bar { x: Int, y(x: Int!): Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, sels, _ ->
                            val barSelections = sels!!.selectionSetForField("Foo", "bar")
                            createEngineObjectData(
                                schema.schema.getObjectType("Foo")!!,
                                mapOf(
                                    "bar" to createEngineObjectData(
                                        schema.schema.getObjectType("Bar")!!,
                                        buildMap {
                                            if (barSelections.containsField("Bar", "y")) {
                                                val y = sels
                                                    .argumentsOfSelection("Foo", "bar")
                                                    ?.get("y") as Int
                                                val x = barSelections
                                                    .argumentsOfSelection("Bar", "y")
                                                    ?.get("x") as Int
                                                put("y", x * y)
                                            }
                                        },
                                    ),
                                ),
                            )
                        },
                    )
                }
            }

            field("Bar" to "x") {
                resolver {
                    objectSelections("y(x: \$x)") {
                        variables("x") { _, _ -> mapOf("x" to 3) }
                    }
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("y") * 5 }
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout(
                """
                    query(${"$"}x: Int!) {
                      foo {
                        ...FooFields
                      }
                    }

                    fragment FooFields on Foo {
                      bar(y: ${"$"}x) {
                        x
                      }
                    }
                """.trimIndent(),
                variables = mapOf("x" to 2),
            ).assertJson("{data: {foo: {bar: {x: 30}}}}")
        }
    }

    @Test
    fun `batched selective field resolver batches distinct rematerialization shapes`() {
        val batches = mutableListOf<List<Pair<Int, Set<String>>>>()

        MockTenantModuleBootstrapper(
            """
                extend type Query { foo(x: Int!): Foo }
                type Foo { x: Int, y: Int, z: Int, w: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldBatchResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        batchResolveFn = { selectors, _ ->
                            batches += selectors.map { selector ->
                                val x = selector.arguments.getAs<Int>("x")
                                val selectedFields = selector.selections!!
                                    .selections()
                                    .map { it.fieldName }
                                    .toSet()
                                x to selectedFields
                            }.sortedBy { it.first }

                            selectors.associateWith { selector ->
                                val x = selector.arguments.getAs<Int>("x")
                                val sels = selector.selections!!
                                Result.success(
                                    createEngineObjectData(
                                        schema.schema.getObjectType("Foo")!!,
                                        buildMap {
                                            if (sels.containsField("Foo", "z")) {
                                                put("z", x * 5)
                                            }
                                            if (sels.containsField("Foo", "w")) {
                                                put("w", x * 7)
                                            }
                                        },
                                    )
                                )
                            }
                        },
                    )
                }
            }

            field("Foo" to "x") {
                resolver {
                    objectSelections("z")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("z") * 11 }
                }
            }

            field("Foo" to "y") {
                resolver {
                    objectSelections("w")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("w") * 13 }
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout(
                """
                    {
                      a: foo(x: 2) { x }
                      b: foo(x: 3) { y }
                    }
                """.trimIndent()
            ).assertJson(
                """
                    {
                      data: {
                        a: {x: 110},
                        b: {y: 273},
                      }
                    }
                """.trimIndent()
            )
        }

        assertEquals(
            listOf(
                listOf(2 to setOf("x"), 3 to setOf("y")),
                listOf(2 to setOf("z"), 3 to setOf("w")),
            ),
            batches,
        )
    }

    @Test
    fun `null rematerialization reports a field error at nullable consumer`() {
        MockTenantModuleBootstrapper(
            """
            extend type Query { foo: Foo }
            type Foo { x:Int, y:Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, sels, _ ->
                            // Return an object for the client-selected shape, then null when
                            // Foo.y's RSS rematerializes `x`.
                            if (!sels!!.containsField("Foo", "x")) {
                                createEngineObjectData(
                                    schema.schema.getObjectType("Foo")!!,
                                    buildMap {
                                        if (sels.containsField("Foo", "y")) put("y", 2)
                                    }
                                )
                            } else {
                                null
                            }
                        }
                    )
                }
            }

            field("Foo" to "y") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        objectSelectionSet = createRSS("Foo", "x"),
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, obj, _, _, _ ->
                            obj.fetchAs<Int>("x") * 3
                        }
                    )
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout("{ foo { y } }").assertMatches {
                "data" to {
                    "foo" to {
                        "y" to null
                    }
                }
                "errors" to arrayOf(
                    {
                        "path" to listOf("foo", "y")
                    }
                )
            }
        }
    }

    @Test
    fun `null rematerialization bubbles through non-null consumer`() {
        MockTenantModuleBootstrapper(
            """
            extend type Query { foo: Foo }
            type Foo { x:Int, y:Int! }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, sels, _ ->
                            // Return an object for the client-selected shape, then null when
                            // Foo.y's RSS rematerializes `x`.
                            if (!sels!!.containsField("Foo", "x")) {
                                createEngineObjectData(
                                    schema.schema.getObjectType("Foo")!!,
                                    buildMap {
                                        if (sels.containsField("Foo", "y")) put("y", 2)
                                    }
                                )
                            } else {
                                null
                            }
                        }
                    )
                }
            }

            field("Foo" to "y") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        objectSelectionSet = createRSS("Foo", "x"),
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, obj, _, _, _ ->
                            obj.fetchAs<Int>("x") * 3
                        }
                    )
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout("{ foo { y } }").assertMatches {
                "data" to {
                    "foo" to null
                }
                "errors" to arrayOf(
                    {
                        "path" to listOf("foo", "y")
                    }
                )
            }
        }
    }

    @Test
    fun `type changes across root materializations report a field error`() {
        MockTenantModuleBootstrapper(
            """
            extend type Query { foo: Foo }
            union Foo = Bar | Baz
            type Bar { x: Int, y: Int }
            type Baz { z: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, sels, _ ->
                            if (!sels!!.containsField("Bar", "x")) {
                                createEngineObjectData(schema.schema.getObjectType("Bar")!!, emptyMap())
                            } else {
                                createEngineObjectData(schema.schema.getObjectType("Baz")!!, emptyMap())
                            }
                        }
                    )
                }
            }

            field("Bar" to "y") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        objectSelectionSet = createRSS("Bar", "x"),
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, obj, _, _, _ ->
                            val x = (obj.fetchOrNull("x") as? Int)
                            x?.let { it * 3 }
                        }
                    )
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout("{ foo { ... on Bar { y } } }").assertMatches {
                "data" to {
                    "foo" to {
                        "y" to null
                    }
                }
                "errors" to arrayOf(
                    {
                        "message" to ".*expected object of type `Bar`, found `Baz`.*"
                        "path" to listOf("foo", "y")
                    }
                )
            }
        }
    }

    @Test
    fun `type changes across nested object materializations report a field error`() {
        MockTenantModuleBootstrapper(
            """
            extend type Query { foo: Foo }
            type Foo { bar: Bar }
            union Bar = Baz | Qux
            type Baz { x: Int, y: Int }
            type Qux { z: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, sels, _ ->
                            val barSelections = sels!!
                                .selectionSetForField("Foo", "bar")
                            val bar = if (barSelections.containsField("Baz", "x")) {
                                createEngineObjectData(schema.schema.getObjectType("Qux")!!, emptyMap())
                            } else {
                                createEngineObjectData(schema.schema.getObjectType("Baz")!!, emptyMap())
                            }
                            createEngineObjectData(
                                schema.schema.getObjectType("Foo")!!,
                                mapOf("bar" to bar),
                            )
                        }
                    )
                }
            }

            field("Baz" to "y") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        objectSelectionSet = createRSS("Baz", "x"),
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, obj, _, _, _ ->
                            val x = (obj.fetchOrNull("x") as? Int)
                            x?.let { it * 3 }
                        }
                    )
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout(
                "{ foo { bar { ... on Baz { y } } } }"
            ).assertMatches {
                "data" to {
                    "foo" to {
                        "bar" to {
                            "y" to null
                        }
                    }
                }
                "errors" to arrayOf(
                    {
                        "message" to ".*expected object of type `Baz`, found `Qux`.*"
                        "path" to listOf("foo", "bar", "y")
                    }
                )
            }
        }
    }

    @Test
    fun `malformed nested object from rematerialization reports a field error`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo { bar: Bar }
                type Bar { x: Int, y: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, sels, _ ->
                            val barSelections = sels!!
                                .selectionSetForField("Foo", "bar")
                            createEngineObjectData(
                                schema.schema.getObjectType("Foo")!!,
                                mapOf(
                                    "bar" to if (barSelections.containsField("Bar", "y")) {
                                        2
                                    } else {
                                        createEngineObjectData(schema.schema.getObjectType("Bar")!!, emptyMap())
                                    }
                                ),
                            )
                        }
                    )
                }
            }

            field("Bar" to "x") {
                resolver {
                    objectSelections("y")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("y") * 3 }
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout("{ foo { bar { x } } }").assertMatches {
                "data" to {
                    "foo" to {
                        "bar" to {
                            "x" to null
                        }
                    }
                }
                "errors" to arrayOf(
                    {
                        "message" to ".*failed when materialized.*"
                        "path" to listOf("foo", "bar", "x")
                    }
                )
            }
        }
    }

    @Test
    fun `resolver exceptions during rematerialization report a field error`() {
        MockTenantModuleBootstrapper(
            """
            extend type Query { foo: Foo }
            type Foo { x: Int, y: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, sels, _ ->
                            if (sels!!.containsField("Foo", "y")) {
                                throw RuntimeException("foo second materialization failed")
                            }
                            createEngineObjectData(schema.schema.getObjectType("Foo")!!, emptyMap())
                        }
                    )
                }
            }

            field("Foo" to "x") {
                resolver {
                    objectSelections("y")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<Int>("y") * 3
                    }
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout("{ foo { x } }").assertMatches {
                "data" to {
                    "foo" to {
                        "x" to null
                    }
                }
                "errors" to arrayOf(
                    {
                        "message" to ".*failed when materialized.*"
                        "path" to listOf("foo", "x")
                    }
                )
            }
        }
    }

    @Test
    fun `selective resolver rematerializes DataFetcherResult list items`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: [Foo] }
                type Foo { x: Int, y: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, sels, _ ->
                            listOf(
                                DataFetcherResult.newResult<EngineObjectData>()
                                    .data(
                                        createEngineObjectData(
                                            schema.schema.getObjectType("Foo")!!,
                                            buildMap {
                                                if (sels!!.containsField("Foo", "x")) {
                                                    put("x", 2)
                                                }
                                            },
                                        )
                                    )
                                    .build()
                            )
                        }
                    )
                }
            }

            field("Foo" to "y") {
                resolver {
                    objectSelections("x")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("x") * 5 }
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout("{ foo { y } }")
                .assertJson("{data: {foo: [{y: 10}]}}")
        }
    }

    @Test
    fun `mat backed traversal keeps the selective resolver result as its source`() {
        val instrumentation = RecordingInstrumentation()
        lateinit var selectiveSource: EngineObjectData

        MockTenantModuleBootstrapper("extend type Query { foo: Foo } type Foo { x: Int }") {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, _, _ ->
                            createEngineObjectData(schema.schema.getObjectType("Foo")!!, mapOf("x" to 2))
                                .also { selectiveSource = it }
                        }
                    )
                }
            }
        }.toEngineTestModule().runContractTest(
            engineConfig = selectiveFieldConfig.copy(
                additionalInstrumentation = instrumentation,
            )
        ) {
            withTimeout("{ foo { x } }")
                .assertJson("{data: {foo: {x: 2}}}")
        }

        val xEnvironment = instrumentation.dataFetchingEnvironments.single {
            it.executionStepInfo.path.toString() == "/foo/x"
        }
        assertSame(selectiveSource, xEnvironment.getSource<EngineObjectData>())
    }

    @Test
    fun `selective field resolver materialization invokes field fetching instrumentation`() {
        val instrumentation = RecordingInstrumentation()

        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo { x: Int, y: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, sels, _ ->
                            createEngineObjectData(
                                schema.schema.getObjectType("Foo")!!,
                                buildMap {
                                    if (sels!!.containsField("Foo", "y")) {
                                        put("y", 2)
                                    }
                                }
                            )
                        }
                    )
                }
            }

            field("Foo" to "x") {
                resolver {
                    objectSelections("y")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("y") * 3 }
                }
            }
        }.toEngineTestModule().runContractTest(
            engineConfig = selectiveFieldConfig.copy(
                additionalInstrumentation = instrumentation,
            )
        ) {
            withTimeout("{ foo { x } }")
                .assertJson("{data: {foo: {x: 6}}}")
        }

        val fooFieldFetchingContexts = instrumentation.fieldFetchingContexts
            .filter {
                (it.parameters as InstrumentationFieldFetchParameters).executionStepInfo.path.toString() == "/foo"
            }

        assertEquals(2, fooFieldFetchingContexts.size)
        assertTrue(fooFieldFetchingContexts.all { it.onDispatchedCalled.get() }) {
            "Expected every /foo field fetching context to be dispatched"
        }
        assertTrue(fooFieldFetchingContexts.all { it.onCompletedCalled.get() }) {
            "Expected every /foo field fetching context to be completed"
        }
    }

    @Test
    fun `selective field materialization reuses field checker`() {
        val fooCheckerCalls = AtomicInteger()
        val fooResolverCalls = AtomicInteger()

        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo { x: Int, y: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                checker {
                    fn { _, _ ->
                        fooCheckerCalls.incrementAndGet()
                    }
                }
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, sels, _ ->
                            fooResolverCalls.incrementAndGet()
                            createEngineObjectData(
                                schema.schema.getObjectType("Foo")!!,
                                buildMap {
                                    if (sels!!.containsField("Foo", "y")) {
                                        put("y", 2)
                                    }
                                }
                            )
                        }
                    )
                }
            }

            field("Foo" to "x") {
                resolver {
                    objectSelections("y")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("y") * 3 }
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout("{foo { x } }")
                .assertJson("{data: {foo: {x: 6}}}")
        }

        assertEquals(1, fooCheckerCalls.get())
        assertEquals(2, fooResolverCalls.get())
    }

    @Test
    fun `field checker failure after selective materialization is reported`() {
        val fooCheckerCalls = AtomicInteger()
        val fooResolverCalls = AtomicInteger()

        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo { x: Int, y: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                checker {
                    objectSelections("fields", "foo { y }")
                    fn { _, objects ->
                        objects.getValue("fields")
                            .fetchAs<EngineObjectData>("foo")
                            .fetchAs<Int>("y")
                        fooCheckerCalls.incrementAndGet()
                        noAccess("foo denied")
                    }
                }
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, sels, _ ->
                            fooResolverCalls.incrementAndGet()
                            createEngineObjectData(
                                schema.schema.getObjectType("Foo")!!,
                                buildMap {
                                    if (sels!!.containsField("Foo", "y")) {
                                        put("y", 2)
                                    }
                                }
                            )
                        }
                    )
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout("{ foo { __typename } }").assertMatches {
                "data" to {
                    "foo" to null
                }
                "errors" to arrayOf(
                    {
                        "message" to ".*foo denied.*"
                        "path" to listOf("foo")
                    }
                )
            }
        }

        assertEquals(1, fooCheckerCalls.get())
        assertEquals(2, fooResolverCalls.get())
    }

    @Test
    fun `type checker reads multiple fields from selective source`() {
        val fooCalls = AtomicInteger()
        val fooCheckerCalls = AtomicInteger()

        MockTenantModuleBootstrapper(
            """
                extend type Query { foo:Foo }
                type Foo { x:Int, y:Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, sels, _ ->
                            fooCalls.incrementAndGet()
                            createEngineObjectData(
                                schema.schema.getObjectType("Foo")!!,
                                buildMap {
                                    if (sels!!.containsField("Foo", "x")) put("x", 1)
                                    if (sels.containsField("Foo", "y")) put("y", 2)
                                }
                            )
                        }
                    )
                }
            }

            type("Foo") {
                checker {
                    objectSelections("fields", "x y")
                    fn { _, _ -> fooCheckerCalls.incrementAndGet() }
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout("{ foo { y } }")
                .assertJson("{data: {foo: {y: 2}}}")
        }

        assertEquals(2, fooCalls.get())
        assertEquals(1, fooCheckerCalls.get())
    }

    @Test
    fun `field checker reads its selective field`() {
        val checkedY = AtomicInteger()
        val fooCheckerCalls = AtomicInteger()
        val fooResolverCalls = AtomicInteger()

        MockTenantModuleBootstrapper(
            """
                extend type Query { foo:Foo }
                type Foo { y:Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                checker {
                    objectSelections("fields", "foo { y }")
                    fn { _, objects ->
                        checkedY.set(
                            objects.getValue("fields")
                                .fetchAs<EngineObjectData>("foo")
                                .fetchAs("y")
                        )
                        fooCheckerCalls.incrementAndGet()
                    }
                }
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, sels, _ ->
                            fooResolverCalls.incrementAndGet()
                            createEngineObjectData(
                                schema.schema.getObjectType("Foo")!!,
                                buildMap {
                                    if (sels!!.containsField("Foo", "y")) {
                                        put("y", 1)
                                    }
                                }
                            )
                        }
                    )
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout("{ foo { __typename } }")
                .assertJson("{data: {foo: {__typename: \"Foo\"}}}")
        }

        assertEquals(1, checkedY.get())
        assertEquals(1, fooCheckerCalls.get())
        assertEquals(2, fooResolverCalls.get())
    }

    @Test
    fun `materialization preserves resolver selection directives`() {
        MockTenantModuleBootstrapper(
            """
                directive @matMarker on FIELD
                extend type Query { foo: Foo }
                type Foo { x: Int, y: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, sels, _ ->
                            createEngineObjectData(
                                schema.schema.getObjectType("Foo")!!,
                                buildMap {
                                    if (sels!!.printAsFieldSet().contains("@matMarker")) {
                                        put("y", 2)
                                    }
                                },
                            )
                        }
                    )
                }
            }

            field("Foo" to "x") {
                resolver {
                    objectSelections("y @matMarker")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("y") * 3 }
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout("{ foo { x } }")
                .assertJson("{data: {foo: {x: 6}}}")
        }
    }

    @Test
    fun `resolver does not hydrate a selected field in its output selection set`() {
        val initialFooCall = AtomicBoolean(true)

        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo { x: Int, y: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, _, _ ->
                            val data = if (initialFooCall.getAndSet(false)) {
                                emptyMap()
                            } else {
                                mapOf("y" to 2)
                            }
                            createEngineObjectData(schema.schema.getObjectType("Foo")!!, data)
                        }
                    )
                }
            }

            field("Foo" to "x") {
                resolver {
                    objectSelections("y")
                    fn { _, obj, _, _, _ -> (obj.fetchOrNull("y") as? Int ?: 2) * 3 }
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout("{ foo { y x } }")
                .assertJson("{data: {foo: {y: null, x: 6}}}")
        }
    }

    @Test
    fun `client and rss arguments remain isolated in output selection set`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo:Foo }
                type Foo { x:Int, y(z:Int!):Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, sels, _ ->
                            val ySelection = sels!!.selections()
                                .single { it.fieldName == "y" }
                            val z = sels
                                .argumentsOfSelection("Foo", ySelection.selectionName)
                                ?.get("z") as Int
                            createEngineObjectData(
                                schema.schema.getObjectType("Foo")!!,
                                mapOf("y" to z),
                            )
                        }
                    )
                }
            }

            field("Foo" to "x") {
                resolver {
                    objectSelections("y(z:2)")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("y") * 3 }
                }
            }
        }.toEngineTestModule().runContractTest(engineConfig = selectiveFieldConfig) {
            withTimeout("{ foo { y(z:1) x } }")
                .assertJson("{data: {foo: {y:1, x:6}}}")
        }
    }
}
