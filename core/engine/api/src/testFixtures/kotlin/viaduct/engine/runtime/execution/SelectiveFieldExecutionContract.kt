package viaduct.engine.runtime.execution

import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.engine.EngineConfiguration
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.MockTenantModuleBootstrapper
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.createRSS
import viaduct.engine.api.mocks.featureTestDefault
import viaduct.engine.api.mocks.fetchAs
import viaduct.engine.api.spi.FieldSelectivityProvider
import viaduct.graphql.test.assertJson

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
}
