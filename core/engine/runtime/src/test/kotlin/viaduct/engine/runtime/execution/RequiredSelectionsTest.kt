package viaduct.engine.runtime.execution

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContains
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.EngineConfiguration
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.MockLegacyTenantModuleBootstrapper
import viaduct.engine.api.mocks.MockVariablesResolver
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.createRSS
import viaduct.engine.api.mocks.featureTestDefault
import viaduct.engine.api.mocks.fetchAs
import viaduct.engine.api.mocks.getAs
import viaduct.engine.api.mocks.runFeatureTest
import viaduct.engine.runtime.tenantloading.RequiredSelectionsAreInvalid
import viaduct.graphql.scopes.ScopedSchemaBuilder
import viaduct.graphql.test.assertJson
import viaduct.service.api.spi.mocks.MockFlagManager

@ExperimentalCoroutinesApi
class RequiredSelectionsTest {
    @Test
    fun `resolve field with required sibling field`() =
        MockLegacyTenantModuleBootstrapper("extend type Query { foo: String, bar: String }") {
            fieldWithValue("Query" to "bar", "BAR")
            field("Query" to "foo") {
                resolver {
                    objectSelections("bar")
                    fn { _, obj, _, _, _ -> (obj.fetch("bar") as String).reversed() }
                }
            }
        }.runFeatureTest {
            runQuery("{foo}")
                .assertJson("""{"data": {"foo": "RAB"}}""")
        }

    @Test
    fun `resolve field with transitive required selections`() =
        MockLegacyTenantModuleBootstrapper("extend type Query { foo: Int, bar: Int, baz: Int }") {
            fieldWithValue("Query" to "baz", 2)
            field("Query" to "bar") {
                resolver {
                    objectSelections("baz")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("baz") * 3 }
                }
            }
            field("Query" to "foo") {
                resolver {
                    objectSelections("bar")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("bar") * 5 }
                }
            }
        }.runFeatureTest {
            runQuery("{foo}")
                .assertJson("""{"data": {"foo": 30}}""")
        }

    @Test
    fun `required selections use aliases`() =
        MockLegacyTenantModuleBootstrapper("extend type Query { foo: Int, bar: Int }") {
            fieldWithValue("Query" to "bar", 3)
            field("Query" to "foo") {
                resolver {
                    objectSelections("aliasedBar: bar")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("aliasedBar") * 2 }
                }
            }
        }.runFeatureTest {
            runQuery("{foo}")
                .assertJson("""{"data": {"foo": 6}}""")
        }

    @Test
    fun `required selections use deep aliases`() =
        MockLegacyTenantModuleBootstrapper("extend type Query { string1: String, bar: Bar } type Bar { value: String }") {
            field("Query" to "bar") {
                resolver {
                    fn { _, _, _, _, _ -> mapOf("value" to "B") }
                }
            }
            field("Query" to "string1") {
                resolver {
                    objectSelections("aliasedBar: bar { aliasedValue: value }")
                    fn { _, obj, _, _, _ ->
                        val bar = obj.fetchAs<EngineObjectData>("aliasedBar")
                        val value = bar.fetch("aliasedValue")
                        "A:$value"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{string1}")
                .assertJson("""{"data": {"string1": "A:B"}}""")
        }

    @Test
    fun `required selections use arguments`() =
        MockLegacyTenantModuleBootstrapper("extend type Query { foo: Int, bar(x:Int):Int }") {
            field("Query" to "foo") {
                resolver {
                    objectSelections("bar(x:3)")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("bar") * 2 }
                }
            }
            field("Query" to "bar") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("x") * 5 }
                }
            }
        }.runFeatureTest {
            runQuery("{foo}")
                .assertJson("""{"data": {"foo": 30}}""")
        }

    @Test
    fun `required selections use aliases and arguments`() =
        MockLegacyTenantModuleBootstrapper("extend type Query { foo: Int, bar(x:Int):Int }") {
            field("Query" to "foo") {
                resolver {
                    objectSelections("aliasedBar:bar(x:3)")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("aliasedBar") * 2 }
                }
            }
            field("Query" to "bar") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("x") * 5 }
                }
            }
        }.runFeatureTest {
            runQuery("{foo}")
                .assertJson("""{"data": {"foo": 30}}""")
        }

    @Test
    fun `required selections select an argumented field multiple times`() =
        MockLegacyTenantModuleBootstrapper("extend type Query { foo: Int, bar(x:Int):Int }") {
            field("Query" to "foo") {
                resolver {
                    objectSelections("b1:bar(x:3), b2:bar(x:5)")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<Int>("b1") * obj.fetchAs<Int>("b2")
                    }
                }
            }
            field("Query" to "bar") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("x") * 2 }
                }
            }
        }.runFeatureTest {
            runQuery("{foo}")
                .assertJson("""{"data": {"foo": 60}}""")
        }

    @Test
    fun `required selections use fragments`() =
        MockLegacyTenantModuleBootstrapper("extend type Query { foo: Int, bar: Int }") {
            fieldWithValue("Query" to "bar", 3)
            field("Query" to "foo") {
                resolver {
                    objectSelections("fragment _ on Query { bar }")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("bar") * 2 }
                }
            }
        }.runFeatureTest {
            runQuery("{foo}")
                .assertJson("""{"data": {"foo": 6}}""")
        }

    @Test
    fun `required selections use untyped inline fragments`() =
        MockLegacyTenantModuleBootstrapper("extend type Query { foo: Int, bar: Int }") {
            fieldWithValue("Query" to "bar", 3)
            field("Query" to "foo") {
                resolver {
                    objectSelections("... { bar }")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("bar") * 2 }
                }
            }
        }.runFeatureTest {
            runQuery("{foo}")
                .assertJson("""{"data": {"foo": 6}}""")
        }

    @Test
    fun `required selections use typed inline fragments`() =
        MockLegacyTenantModuleBootstrapper("extend type Query { foo: Int, bar: Int }") {
            fieldWithValue("Query" to "bar", 3)
            field("Query" to "foo") {
                resolver {
                    objectSelections("... on Query { bar }")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("bar") * 2 }
                }
            }
        }.runFeatureTest {
            runQuery("{foo}")
                .assertJson("""{"data": {"foo": 6}}""")
        }

    @Test
    fun `resolve fields with shared requirement`() {
        val bazCount = AtomicInteger()
        MockLegacyTenantModuleBootstrapper("extend type Query { foo: Int, bar: Int, baz: Int }") {
            field("Query" to "baz") {
                resolver {
                    fn { _, _, _, _, _ -> bazCount.incrementAndGet().let { 5 } }
                }
            }
            field("Query" to "bar") {
                resolver {
                    objectSelections("baz")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("baz") * 3 }
                }
            }
            field("Query" to "foo") {
                resolver {
                    objectSelections("baz")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("baz") * 2 }
                }
            }
        }.runFeatureTest {
            runQuery("{foo bar}")
                .assertJson("""{"data": {"foo": 10, "bar": 15}}""")
                .also { assertEquals(1, bazCount.get()) }
        }
    }

    @Test
    fun `selective field executes once for a single selection shape`() {
        val detailsCount = AtomicInteger()
        val detailsSelections = ConcurrentHashMap.newKeySet<String>()

        MockLegacyTenantModuleBootstrapper(
            """
            extend type Query { details: Details }
            type Details { a: Int, b: Int }
            """.trimIndent()
        ) {
            field("Query" to "details") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, selections, _ ->
                            val requestedSelections = selections
                                ?.selections()
                                ?.map { it.selectionName }
                                ?.toSortedSet()
                                .orEmpty()
                            detailsCount.incrementAndGet()
                            detailsSelections.add(requestedSelections.joinToString(" "))
                            createEngineObjectData(
                                schema.schema.getObjectType("Details"),
                                buildMap {
                                    if ("a" in requestedSelections) put("a", 1)
                                    if ("b" in requestedSelections) put("b", 2)
                                }
                            )
                        }
                    )
                }
            }
        }.runFeatureTest {
            runQuery("{ details { a } }")
                .assertJson("""{"data": {"details": {"a": 1}}}""")
        }

        assertEquals(1, detailsCount.get())
        assertEquals(setOf("a"), detailsSelections)
    }

    @Test
    fun `selective field executes separately for client and resolver rss shapes`() {
        val detailsCount = AtomicInteger()
        val detailsSelections = ConcurrentHashMap.newKeySet<String>()

        MockLegacyTenantModuleBootstrapper(
            """
            extend type Query { details: Details, fromB: Int }
            type Details { a: Int, b: Int }
            """.trimIndent()
        ) {
            field("Query" to "details") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, selections, _ ->
                            val requestedSelections = selections
                                ?.selections()
                                ?.map { it.selectionName }
                                ?.toSortedSet()
                                .orEmpty()
                            detailsCount.incrementAndGet()
                            detailsSelections.add(requestedSelections.joinToString(" "))
                            createEngineObjectData(
                                schema.schema.getObjectType("Details"),
                                buildMap {
                                    if ("a" in requestedSelections) put("a", 1)
                                    if ("b" in requestedSelections) put("b", 2)
                                }
                            )
                        }
                    )
                }
            }
            field("Query" to "fromB") {
                resolver {
                    querySelections("details { b }")
                    fn { _, _, qry, _, _ ->
                        qry.fetchAs<EngineObjectData>("details").fetchAs<Int>("b")
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ details { a } fromB }")
                .assertJson("""{"data": {"details": {"a": 1}, "fromB": 2}}""")
        }

        assertEquals(2, detailsCount.get())
        assertEquals(setOf("a", "b"), detailsSelections)
    }

    @Test
    fun `selective required selection is resolved independently from client query selection`() {
        val detailsCount = AtomicInteger()
        val detailsSelections = ConcurrentHashMap.newKeySet<String>()

        MockLegacyTenantModuleBootstrapper(
            """
            extend type Query { container: Container }
            type Container { details: Details, summary: Int }
            type Details { a: Int, b: Int }
            """.trimIndent()
        ) {
            field("Query" to "container") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("Container"), emptyMap())
                    }
                }
            }
            field("Container" to "details") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, selections, _ ->
                            val requestedSelections = selections
                                ?.selections()
                                ?.map { it.selectionName }
                                ?.toSortedSet()
                                .orEmpty()
                            detailsCount.incrementAndGet()
                            detailsSelections.add(requestedSelections.joinToString(" "))
                            createEngineObjectData(
                                schema.schema.getObjectType("Details"),
                                buildMap {
                                    if ("a" in requestedSelections) put("a", 1)
                                    if ("b" in requestedSelections) put("b", 2)
                                }
                            )
                        }
                    )
                }
            }
            field("Container" to "summary") {
                resolver {
                    objectSelections("details { b }")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<EngineObjectData>("details").fetchAs<Int>("b") * 10
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ container { details { a } summary } }")
                .assertJson("""{"data": {"container": {"details": {"a": 1}, "summary": 20}}}""")
        }

        assertEquals(2, detailsCount.get())
        assertEquals(setOf("a", "b"), detailsSelections)
    }

    @Test
    fun `disabling selective resolver keys causes required selections to reuse client selection shape`() {
        val detailsCount = AtomicInteger()
        val detailsSelections = ConcurrentHashMap.newKeySet<String>()
        val engineConfig = EngineConfiguration.featureTestDefault.copy(flagManager = MockFlagManager.Disabled)

        MockLegacyTenantModuleBootstrapper(
            """
            extend type Query { container: Container }
            type Container { details: Details, summary: Int }
            type Details { a: Int, b: Int }
            """.trimIndent()
        ) {
            field("Query" to "container") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("Container"), emptyMap())
                    }
                }
            }
            field("Container" to "details") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, selections, _ ->
                            val requestedSelections = selections
                                ?.selections()
                                ?.map { it.selectionName }
                                ?.toSortedSet()
                                .orEmpty()
                            detailsCount.incrementAndGet()
                            detailsSelections.add(requestedSelections.joinToString(" "))
                            createEngineObjectData(
                                schema.schema.getObjectType("Details"),
                                buildMap {
                                    if ("a" in requestedSelections) put("a", 1)
                                    if ("b" in requestedSelections) put("b", 2)
                                }
                            )
                        }
                    )
                }
            }
            field("Container" to "summary") {
                resolver {
                    objectSelections("details { b }")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<EngineObjectData>("details").fetchAs<Int>("b") * 10
                    }
                }
            }
        }.runFeatureTest(engineConfig = engineConfig) {
            val result = runQuery("{ container { details { a } summary } }")

            assertEquals(
                mapOf(
                    "container" to mapOf(
                        "details" to mapOf("a" to 1),
                        "summary" to null,
                    )
                ),
                result.getData()
            )
            assertEquals(1, result.errors.size)
            assertContains(result.errors.first().message, "null cannot be cast to non-null type kotlin.Int")
        }

        assertEquals(1, detailsCount.get())
        assertEquals(setOf("a"), detailsSelections)
    }

    @Test
    fun `non-selective required selection is shared across client query and dependency selections`() {
        val detailsCount = AtomicInteger()
        val detailsSelections = ConcurrentHashMap.newKeySet<String>()

        MockLegacyTenantModuleBootstrapper(
            """
            extend type Query { container: Container }
            type Container { details: Details, summary: Int }
            type Details { a: Int, b: Int }
            """.trimIndent()
        ) {
            field("Query" to "container") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("Container"), emptyMap())
                    }
                }
            }
            field("Container" to "details") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, selections, _ ->
                            val requestedSelections = selections
                                ?.selections()
                                ?.map { it.selectionName }
                                ?.toSortedSet()
                                .orEmpty()
                            detailsCount.incrementAndGet()
                            detailsSelections.add(requestedSelections.joinToString(" "))
                            createEngineObjectData(
                                schema.schema.getObjectType("Details"),
                                mapOf("a" to 1, "b" to 2)
                            )
                        }
                    )
                }
            }
            field("Container" to "summary") {
                resolver {
                    objectSelections("details { b }")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<EngineObjectData>("details").fetchAs<Int>("b") * 10
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ container { details { a } summary } }")
                .assertJson("""{"data": {"container": {"details": {"a": 1}, "summary": 20}}}""")
        }

        assertEquals(1, detailsCount.get())
        assertEquals(setOf("a"), detailsSelections)
    }

    @Test
    fun `selective required selection is resolved independently across resolver rss variants`() {
        val detailsCount = AtomicInteger()
        val detailsSelections = ConcurrentHashMap.newKeySet<String>()

        MockLegacyTenantModuleBootstrapper(
            """
            extend type Query { container: Container }
            type Container { details: Details, fromA: Int, fromB: Int }
            type Details { a: Int, b: Int }
            """.trimIndent()
        ) {
            field("Query" to "container") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("Container"), emptyMap())
                    }
                }
            }
            field("Container" to "details") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, selections, _ ->
                            val requestedSelections = selections
                                ?.selections()
                                ?.map { it.selectionName }
                                ?.toSortedSet()
                                .orEmpty()
                            detailsCount.incrementAndGet()
                            detailsSelections.add(requestedSelections.joinToString(" "))
                            createEngineObjectData(
                                schema.schema.getObjectType("Details"),
                                buildMap {
                                    if ("a" in requestedSelections) put("a", 1)
                                    if ("b" in requestedSelections) put("b", 2)
                                }
                            )
                        }
                    )
                }
            }
            field("Container" to "fromA") {
                resolver {
                    objectSelections("details { a }")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<EngineObjectData>("details").fetchAs<Int>("a")
                    }
                }
            }
            field("Container" to "fromB") {
                resolver {
                    objectSelections("details { b }")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<EngineObjectData>("details").fetchAs<Int>("b")
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ container { fromA fromB } }")
                .assertJson("""{"data": {"container": {"fromA": 1, "fromB": 2}}}""")
        }

        assertEquals(2, detailsCount.get())
        assertEquals(setOf("a", "b"), detailsSelections)
    }

    @Test
    fun `object rss node selection ignores nested fragment on other implementation`() {
        // Query.foo's object RSS reads Query.node with a selection whose outer fragment is on Bar,
        // but the node resolves to Foo. The resolver-facing object value should therefore expose no
        // child selections for the Foo node.
        //
        // This used to time out because EngineSelectionSet widened the nested `... on Node`
        // fragment inside `... on Bar`, so fetching `id` through the Foo proxy waited on an OER
        // field that the planned traversal correctly never wrote.
        MockLegacyTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo implements Node { id: ID! }
                type Bar implements Node { id: ID! }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    val objectRss = createRSS(
                        "Query",
                        """
                            fragment Main on Query {
                              node(id: "Rm9vOlQrZw==") {
                                ...BarNodeFields
                              }
                            }

                            fragment BarNodeFields on Bar {
                              ... on Node {
                                id
                              }
                            }
                        """.trimIndent()
                    )
                    MockFieldUnbatchedResolverExecutor(
                        objectSelectionSet = objectRss,
                        isSelective = false,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, obj, _, _, ctx ->
                            val node = obj.fetchAs<EngineObjectData>("node")
                            assertNull(withTimeout(1_000) { node.fetchOrNull("id") })
                            ctx.createNodeReference("foo", schema.schema.getObjectType("Foo")!!)
                        }
                    )
                }
            }

            type("Foo") {
                nodeUnbatchedExecutor { id, _, _ ->
                    createEngineObjectData(
                        objectType,
                        mapOf("id" to id)
                    )
                }
            }
        }.runFeatureTest {
            runQuery(
                """
                    query {
                      foo {
                        __typename
                      }
                    }
                """.trimIndent()
            ).assertJson(
                """
                    {
                      data: {
                        foo: {
                          __typename: "Foo"
                        }
                      }
                    }
                """.trimIndent()
            )
        }
    }

    @Test
    fun `selective query field resolves matching resolver and checker selections independently`() {
        val detailsCount = AtomicInteger()
        val detailsSelections = ConcurrentHashMap.newKeySet<String>()
        val checkerCount = AtomicInteger()

        MockLegacyTenantModuleBootstrapper(
            """
            extend type Query { details: Details, fromObjectB: Int, fromQueryB: Int, checked: Int }
            type Details { a: Int, b: Int }
            """.trimIndent()
        ) {
            field("Query" to "details") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, selections, _ ->
                            val requestedSelections = selections
                                ?.selections()
                                ?.map { it.selectionName }
                                ?.toSortedSet()
                                .orEmpty()
                            detailsCount.incrementAndGet()
                            detailsSelections.add(requestedSelections.joinToString(" "))
                            createEngineObjectData(
                                schema.schema.getObjectType("Details"),
                                buildMap {
                                    if ("a" in requestedSelections) put("a", 1)
                                    if ("b" in requestedSelections) put("b", 2)
                                }
                            )
                        }
                    )
                }
            }
            field("Query" to "fromObjectB") {
                resolver {
                    objectSelections("details { b }")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<EngineObjectData>("details").fetchAs<Int>("b")
                    }
                }
            }
            field("Query" to "fromQueryB") {
                resolver {
                    querySelections("details { b }")
                    fn { _, _, qry, _, _ ->
                        qry.fetchAs<EngineObjectData>("details").fetchAs<Int>("b")
                    }
                }
            }
            field("Query" to "checked") {
                value(1)
                checker {
                    querySelections("key", "fragment _ on Query { details { b } }")
                    fn { _, objectDataMap ->
                        checkerCount.incrementAndGet()
                        objectDataMap["key"]!!
                            .fetchAs<EngineObjectData>("details")
                            .fetchAs<Int>("b")
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ details { a } fromObjectB fromQueryB checked }")
                .assertJson("""{"data": {"details": {"a": 1}, "fromObjectB": 2, "fromQueryB": 2, "checked": 1}}""")
        }

        assertEquals(1, checkerCount.get())
        assertEquals(4, detailsCount.get())
        assertEquals(setOf("a", "b"), detailsSelections)
    }

    @Test
    fun `selective required selection resolves resolver and type checker selections independently`() {
        val detailsCount = AtomicInteger()
        val detailsSelections = ConcurrentHashMap.newKeySet<String>()
        val checkerCount = AtomicInteger()

        MockLegacyTenantModuleBootstrapper(
            """
            extend type Query { container: Container }
            type Container { details: Details, fromObjectB: Int }
            type Details { a: Int, b: Int }
            """.trimIndent()
        ) {
            field("Query" to "container") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("Container"), emptyMap())
                    }
                }
            }
            field("Container" to "details") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, selections, _ ->
                            val requestedSelections = selections
                                ?.selections()
                                ?.map { it.selectionName }
                                ?.toSortedSet()
                                .orEmpty()
                            detailsCount.incrementAndGet()
                            detailsSelections.add(requestedSelections.joinToString(" "))
                            createEngineObjectData(
                                schema.schema.getObjectType("Details"),
                                buildMap {
                                    if ("a" in requestedSelections) put("a", 1)
                                    if ("b" in requestedSelections) put("b", 2)
                                }
                            )
                        }
                    )
                }
            }
            field("Container" to "fromObjectB") {
                resolver {
                    objectSelections("details { b }")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<EngineObjectData>("details").fetchAs<Int>("b")
                    }
                }
            }
            type("Container") {
                checker {
                    objectSelections("key", "fragment _ on Container { details { b } }")
                    fn { _, objectDataMap ->
                        checkerCount.incrementAndGet()
                        objectDataMap["key"]!!
                            .fetchAs<EngineObjectData>("details")
                            .fetchAs<Int>("b")
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ container { details { a } fromObjectB } }")
                .assertJson("""{"data": {"container": {"details": {"a": 1}, "fromObjectB": 2}}}""")
        }

        assertEquals(1, checkerCount.get())
        assertEquals(3, detailsCount.get())
        assertEquals(setOf("a", "b"), detailsSelections)
    }

    @Test
    fun `selective required selection through interface inline fragment uses concrete runtime keying`() {
        val detailsCount = AtomicInteger()

        MockLegacyTenantModuleBootstrapper(
            """
            interface Container { details: Details }
            extend type Query { container: Container }
            type ConcreteContainer implements Container { details: Details, fromObjectB: Int }
            type Details { a: Int, b: Int }
            """.trimIndent()
        ) {
            field("Query" to "container") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("ConcreteContainer"),
                            emptyMap()
                        )
                    }
                }
            }
            field("ConcreteContainer" to "details") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, selections, _ ->
                            val requestedSelections = selections
                                ?.selections()
                                ?.map { it.selectionName }
                                ?.toSet()
                                .orEmpty()
                            detailsCount.incrementAndGet()
                            createEngineObjectData(
                                schema.schema.getObjectType("Details"),
                                buildMap {
                                    if ("a" in requestedSelections) put("a", 1)
                                    if ("b" in requestedSelections) put("b", 2)
                                }
                            )
                        }
                    )
                }
            }
            field("ConcreteContainer" to "fromObjectB") {
                resolver {
                    objectSelections("... on Container { details { b } }")
                    fn { _, obj, _, _, _ ->
                        withTimeout(1_000) {
                            obj.fetchAs<EngineObjectData>("details").fetchAs<Int>("b")
                        }
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ container { ... on ConcreteContainer { fromObjectB } } }")
                .assertJson("""{"data": {"container": {"fromObjectB": 2}}}""")
        }

        assertEquals(1, detailsCount.get())
    }

    @Test
    fun `descendant fields of a selective resolver are not keyed selectively`() {
        MockLegacyTenantModuleBootstrapper(
            """
            extend type Query { details: Details, fromProfileName: String }
            type Details { profile: Profile }
            type Profile { name: String }
            """.trimIndent()
        ) {
            field("Query" to "details") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, selections, _ ->
                            val requestedSelections = selections
                                ?.selections()
                                ?.map { it.selectionName }
                                ?.toSet()
                                .orEmpty()
                            createEngineObjectData(
                                schema.schema.getObjectType("Details"),
                                buildMap {
                                    if ("profile" in requestedSelections) {
                                        put("profile", mapOf("name" to "Ada"))
                                    }
                                }
                            )
                        }
                    )
                }
            }
            field("Query" to "fromProfileName") {
                resolver {
                    objectSelections("details { profile { name } }")
                    fn { _, obj, _, _, _ ->
                        withTimeout(200) {
                            obj.fetchAs<EngineObjectData>("details")
                                .fetchAs<EngineObjectData>("profile")
                                .fetchAs<String>("name")
                        }
                    }
                }
            }
        }.runFeatureTest {
            val result = runQuery("{ fromProfileName }")
            result.assertJson("""{"data": {"fromProfileName": "Ada"}}""")
        }
    }

    @Test
    fun `resolve field with multiple requirements`() =
        MockLegacyTenantModuleBootstrapper("extend type Query { foo: Int, bar: Int, baz: Int }") {
            fieldWithValue("Query" to "baz", 5)
            fieldWithValue("Query" to "bar", 3)
            field("Query" to "foo") {
                resolver {
                    objectSelections("bar baz")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<Int>("bar") * obj.fetchAs<Int>("baz")
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{foo}")
                .assertJson("""{"data": {"foo": 15}}""")
        }

    @Test
    fun `resolve fields multiple mergeable requirements`() {
        val barCount = AtomicInteger()
        MockLegacyTenantModuleBootstrapper("extend type Query { foo: Int, bar: Int }") {
            field("Query" to "bar") {
                resolver {
                    fn { _, _, _, _, _ -> 3.also { barCount.incrementAndGet() } }
                }
            }
            field("Query" to "foo") {
                resolver {
                    objectSelections(
                        """
                        fragment F on Query { bar }
                        fragment Main on Query {
                          bar
                          aliasedBar: bar
                          ... {
                            bar
                            ... {
                              bar
                              ... F
                            }
                          }
                          ... on Query {
                            bar
                            ... on Query {
                              bar
                              ... F
                            }
                          }
                          ... F
                        }
                        """.trimIndent()
                    )
                    fn { _, obj, _, _, _ ->
                        // make sure we wait for aliasedBar
                        obj.fetchAs<Int>("aliasedBar")

                        // but ultimately just return 2 * "bar"
                        obj.fetchAs<Int>("bar") * 2
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{foo bar}")
                .assertJson("""{"data": {"foo": 6, "bar": 3}}""")
                .also { assertEquals(2, barCount.get()) }
        }
    }

    @Test
    fun `proxy engine object data reads required selections through two nested client and rss merges`() {
        val outerCount = AtomicInteger()
        val middleCount = AtomicInteger()
        val innerCount = AtomicInteger()
        val innerSelections = ConcurrentHashMap.newKeySet<String>()

        MockLegacyTenantModuleBootstrapper(
            """
            extend type Query { summary: Int, outer: Outer }
            type Outer { middle: Middle }
            type Middle { inner: Inner }
            type Inner { a: Int, b: Int }
            """.trimIndent()
        ) {
            field("Query" to "outer") {
                resolver {
                    fn { _, _, _, _, _ ->
                        outerCount.incrementAndGet()
                        createEngineObjectData(schema.schema.getObjectType("Outer"), emptyMap())
                    }
                }
            }
            field("Outer" to "middle") {
                resolver {
                    fn { _, _, _, _, _ ->
                        middleCount.incrementAndGet()
                        createEngineObjectData(schema.schema.getObjectType("Middle"), emptyMap())
                    }
                }
            }
            field("Middle" to "inner") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, selections, _ ->
                            val requestedSelections = selections
                                ?.selectionSetForType("Inner")
                                ?.selections()
                                ?.map { it.selectionName }
                                ?.toSortedSet()
                                .orEmpty()
                            innerCount.incrementAndGet()
                            innerSelections.add(requestedSelections.joinToString(" "))
                            createEngineObjectData(
                                schema.schema.getObjectType("Inner"),
                                buildMap {
                                    if ("a" in requestedSelections) put("a", 1)
                                    if ("b" in requestedSelections) put("b", 2)
                                }
                            )
                        }
                    )
                }
            }
            field("Query" to "summary") {
                resolver {
                    objectSelections("outer { middle { inner { b } } }")
                    fn { _, obj, _, _, _ ->
                        withTimeout(200) {
                            obj.fetchAs<EngineObjectData>("outer")
                                .fetchAs<EngineObjectData>("middle")
                                .fetchAs<EngineObjectData>("inner")
                                .fetchAs<Int>("b")
                        }
                    }
                }
            }
        }.runFeatureTest {
            runQuery(
                """
                query {
                  outer {
                    middle {
                      inner {
                        a
                      }
                    }
                  }
                  summary
                }
                """.trimIndent()
            ).assertJson("""{"data": {"outer": {"middle": {"inner": {"a": 1}}}, "summary": 2}}""")
        }

        assertEquals(1, outerCount.get())
        assertEquals(1, middleCount.get())
        assertEquals(2, innerCount.get())
        assertEquals(setOf("a", "b"), innerSelections)
    }

    @Test
    fun `variable resolver rss reads through multiple selective fields including abstract hop`() {
        val middleCount = AtomicInteger()
        val nodeCount = AtomicInteger()

        MockLegacyTenantModuleBootstrapper(
            """
            extend type Query { outer: Outer, compute(x: Int!): Int!, result: Int! }
            type Outer { middle: Middle }
            type Middle { node: AbstractNode }
            interface AbstractNode { id: ID! }
            type ConcreteNode implements AbstractNode { id: ID!, value: Int! }
            """.trimIndent()
        ) {
            field("Query" to "outer") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Outer"),
                            emptyMap(),
                        )
                    }
                }
            }
            field("Outer" to "middle") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = "Outer.middle",
                        unbatchedResolveFn = { _, _, _, _, _ ->
                            middleCount.incrementAndGet()
                            createEngineObjectData(
                                schema.schema.getObjectType("Middle"),
                                emptyMap(),
                            )
                        }
                    )
                }
            }
            field("Middle" to "node") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = "Middle.node",
                        unbatchedResolveFn = { _, _, _, selections, _ ->
                            val requestedSelections = selections
                                ?.selectionSetForType("ConcreteNode")
                                ?.selections()
                                ?.map { it.selectionName }
                                ?.toSet()
                                .orEmpty()
                            nodeCount.incrementAndGet()
                            createEngineObjectData(
                                schema.schema.getObjectType("ConcreteNode"),
                                buildMap {
                                    if ("id" in requestedSelections) put("id", "n1")
                                    if ("value" in requestedSelections) put("value", 7)
                                },
                            )
                        }
                    )
                }
            }
            field("Query" to "compute") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("x") + 1 }
                }
            }
            field("Query" to "result") {
                resolver {
                    objectSelections("compute(x:\$value)") {
                        variables(
                            "value",
                            rss = createRSS(
                                "Query",
                                "outer { middle { node { ... on ConcreteNode { value } } } }",
                            ),
                        ) { ctx, _ ->
                            val value = withTimeout(200) {
                                ctx.objectData
                                    .fetchAs<EngineObjectData>("outer")
                                    .fetchAs<EngineObjectData>("middle")
                                    .fetchAs<EngineObjectData>("node")
                                    .fetchAs<Int>("value")
                            }
                            mapOf("value" to value)
                        }
                    }
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("compute") }
                }
            }
        }.runFeatureTest {
            runQuery(
                """
                query {
                  outer {
                    middle {
                        node {
                        ... on ConcreteNode {
                          id
                        }
                      }
                    }
                  }
                  result
                }
                """.trimIndent()
            ).assertJson(
                """
                {"data": {
                  "outer": {"middle": {"node": {"id": "n1"}}},
                  "result": 8
                }}
                """.trimIndent()
            )
        }

        assertEquals(2, middleCount.get())
        assertEquals(2, nodeCount.get())
    }

    @Test
    fun `two resolvers with structurally-equivalent variable-resolver RSSes both resolve correctly`() {
        // Setup: two resolver fields (foo1, foo2) whose objectSelections RSS is structurally
        // identical — both select `y(a:$vara)` and both reference the same shared
        // MockVariablesResolver (whose nested RSS selects `z`). The resolver for y returns its
        // argument unchanged, so each foo returns whatever z produces. If the nested RSS id rebind
        // were missing/wrong, one of foo1/foo2 would fail to fetch `vara` at runtime.
        val sharedNestedRss = createRSS("Query", "z")
        val sharedVariablesResolver = MockVariablesResolver(
            "vara",
            requiredSelectionSet = sharedNestedRss,
        ) { ctx, _ -> mapOf("vara" to ctx.objectData.fetchAs<Int>("z")) }
        val sharedResolvers = listOf(sharedVariablesResolver)

        MockLegacyTenantModuleBootstrapper(
            "extend type Query { foo1: Int, foo2: Int, y(a:Int): Int, z: Int }"
        ) {
            fieldWithValue("Query" to "z", 7)
            field("Query" to "y") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("a") }
                }
            }
            field("Query" to "foo1") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        objectSelectionSet = createRSS("Query", "y(a:\$vara)", sharedResolvers),
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, obj, _, _, _ -> obj.fetchAs<Int>("y") },
                    )
                }
            }
            field("Query" to "foo2") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        objectSelectionSet = createRSS("Query", "y(a:\$vara)", sharedResolvers),
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, obj, _, _, _ -> obj.fetchAs<Int>("y") },
                    )
                }
            }
        }.runFeatureTest {
            runQuery("{ foo1 foo2 }")
                .assertJson("""{"data": {"foo1": 7, "foo2": 7}}""")
        }
    }

    @Test
    fun `resolve private field in RSS`() {
        // Need to set up both full schema and scoped schema
        val fullSchemaSDL = """
            extend type Query @scope(to: ["*"]) { _: String }
            extend type Query @scope(to: ["scoped"]) { foo: Int }
            extend type Query @scope(to: ["private"]) { bar: Int }
        """

        val bootstrapper = MockLegacyTenantModuleBootstrapper(fullSchemaSDL) {
            fieldWithValue("Query" to "bar", 3)
            field("Query" to "foo") {
                resolver {
                    objectSelections("bar")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("bar") + 1 }
                }
            }
        }

        val privateSchema = ViaductSchema(
            ScopedSchemaBuilder(
                inputSchema = bootstrapper.fullSchema.schema,
                additionalVisitorConstructors = emptyList(),
                validScopes = sortedSetOf("scoped", "private")
            ).applyScopes(setOf("scoped")).filtered
        )

        bootstrapper.runFeatureTest(schema = privateSchema) {
            runQuery("{foo}")
                .assertJson("{data: {foo: 4}}")
        }
    }

    @Test
    fun `resolve field with queryValueFragment - simple field access`() =
        MockLegacyTenantModuleBootstrapper("extend type Query { currentUser: String, userGreeting: String }") {
            fieldWithValue("Query" to "currentUser", "Alice")
            field("Query" to "userGreeting") {
                resolver {
                    querySelections("currentUser")
                    fn { _, _, qry, _, _ ->
                        val user = qry.fetchAs<String>("currentUser")
                        "Hello, $user!"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{userGreeting}")
                .assertJson("""{"data": {"userGreeting": "Hello, Alice!"}}""")
        }

    @Test
    fun `objectSelections conditional directives honor per-item variables`() {
        val selectedValueCount = AtomicInteger()

        MockLegacyTenantModuleBootstrapper(
            """
            extend type Query {
                items: [Item!]!
            }

            type Item {
                includeSelectedValue: Boolean!
                selectedValue: String
                summary: String
            }
            """.trimIndent()
        ) {
            field("Query" to "items") {
                resolver {
                    fn { _, _, _, _, _ ->
                        listOf(
                            createEngineObjectData(
                                schema.schema.getObjectType("Item"),
                                mapOf("includeSelectedValue" to true)
                            ),
                            createEngineObjectData(
                                schema.schema.getObjectType("Item"),
                                mapOf("includeSelectedValue" to false)
                            )
                        )
                    }
                }
            }
            field("Item" to "selectedValue") {
                resolver {
                    fn { _, _, _, _, _ ->
                        selectedValueCount.incrementAndGet()
                        "selected"
                    }
                }
            }
            field("Item" to "summary") {
                resolver {
                    objectSelections("includeSelectedValue selectedValue @include(if: ${'$'}includeSelectedValue)") {
                        variables("includeSelectedValue", rss = createRSS("Item", "includeSelectedValue")) { resolveCtx, _ ->
                            mapOf(
                                "includeSelectedValue" to resolveCtx.objectData.fetchAs<Boolean>("includeSelectedValue")
                            )
                        }
                    }
                    fn { _, obj, _, _, _ ->
                        if (obj.fetchAs<Boolean>("includeSelectedValue")) {
                            obj.fetchAs<String>("selectedValue")
                        } else {
                            "skipped"
                        }
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ items { summary } }")
                .assertJson("""{"data": {"items": [{"summary": "selected"}, {"summary": "skipped"}]}}""")
        }

        assertEquals(1, selectedValueCount.get())
    }

    @Test
    fun `resolve field with queryValueFragment - with aliases`() =
        MockLegacyTenantModuleBootstrapper("extend type Query { currentUser: String, userCount: Int, summary: String }") {
            fieldWithValue("Query" to "currentUser", "Bob")
            fieldWithValue("Query" to "userCount", 42)
            field("Query" to "summary") {
                resolver {
                    querySelections("user: currentUser, count: userCount")
                    fn { _, _, qry, _, _ ->
                        val user = qry.fetchAs<String>("user")
                        val count = qry.fetchAs<Int>("count")
                        "$user has $count items"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{summary}")
                .assertJson("""{"data": {"summary": "Bob has 42 items"}}""")
        }

    @Test
    fun `resolve field with queryValueFragment - with arguments`() =
        MockLegacyTenantModuleBootstrapper("extend type Query { user(id: String!): String, userMessage: String }") {
            field("Query" to "user") {
                resolver {
                    fn { args, _, _, _, _ ->
                        val id = args.getAs<String>("id")
                        "User-$id"
                    }
                }
            }
            field("Query" to "userMessage") {
                resolver {
                    querySelections("user(id: \"123\")")
                    fn { _, _, qry, _, _ ->
                        val user = qry.fetchAs<String>("user")
                        "Message for: $user"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{userMessage}")
                .assertJson("""{"data": {"userMessage": "Message for: User-123"}}""")
        }

    @Test
    fun `resolve field with queryValueFragment - using fragments`() =
        MockLegacyTenantModuleBootstrapper("extend type Query { userName: String, userEmail: String, profile: String }") {
            fieldWithValue("Query" to "userName", "Charlie")
            fieldWithValue("Query" to "userEmail", "charlie@example.com")
            field("Query" to "profile") {
                resolver {
                    querySelections("fragment UserInfo on Query { userName userEmail }")
                    fn { _, _, qry, _, _ ->
                        val name = qry.fetchAs<String>("userName")
                        val email = qry.fetchAs<String>("userEmail")
                        "Name: $name, Email: $email"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{profile}")
                .assertJson("""{"data": {"profile": "Name: Charlie, Email: charlie@example.com"}}""")
        }

    @Test
    fun `resolve field with queryValueFragment and objectValueFragment together`() =
        MockLegacyTenantModuleBootstrapper("extend type Query { globalConfig: String, baz: Baz } type Baz { x: Int, y: String }") {
            fieldWithValue("Query" to "globalConfig", "Premium")
            fieldWithValue("Query" to "baz", createEngineObjectData(schema.schema.getObjectType("Baz"), mapOf("x" to 100)))
            field("Baz" to "y") {
                resolver {
                    objectSelections("x")
                    querySelections("globalConfig")
                    fn { _, obj, qry, _, _ ->
                        val config = qry.fetchAs<String>("globalConfig")
                        val x = obj.fetchAs<Int>("x")
                        "$config item with value $x"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{baz { y }}")
                .assertJson("{data: {baz: {y: \"Premium item with value 100\"}}}")
        }

    @Test
    fun `resolve field with queryValueFragment - transitive dependencies`() =
        MockLegacyTenantModuleBootstrapper("extend type Query { baseValue: Int, multipliedValue: Int, finalValue: Int }") {
            fieldWithValue("Query" to "baseValue", 5)
            field("Query" to "multipliedValue") {
                resolver {
                    querySelections("baseValue")
                    fn { _, _, qry, _, _ ->
                        qry.fetchAs<Int>("baseValue") * 2
                    }
                }
            }
            field("Query" to "finalValue") {
                resolver {
                    querySelections("multipliedValue")
                    fn { _, _, qry, _, _ ->
                        qry.fetchAs<Int>("multipliedValue") + 10
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{finalValue}")
                .assertJson("""{"data": {"finalValue": 20}}""")
        }

    @Test
    fun `resolve field with queryValueFragment - multiple query selections`() {
        val userCount = AtomicInteger()
        val configCount = AtomicInteger()
        MockLegacyTenantModuleBootstrapper("extend type Query { currentUser: String, globalConfig: String, combined: String }") {
            field("Query" to "currentUser") {
                resolver {
                    fn { _, _, _, _, _ ->
                        userCount.incrementAndGet()
                        "David"
                    }
                }
            }
            field("Query" to "globalConfig") {
                resolver {
                    fn { _, _, _, _, _ ->
                        configCount.incrementAndGet()
                        "Advanced"
                    }
                }
            }
            field("Query" to "combined") {
                resolver {
                    querySelections("currentUser globalConfig")
                    fn { _, _, qry, _, _ ->
                        val user = qry.fetchAs<String>("currentUser")
                        val config = qry.fetchAs<String>("globalConfig")
                        "$user - $config mode"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{combined}")
                .assertJson("""{"data": {"combined": "David - Advanced mode"}}""")
                .also {
                    assertEquals(1, userCount.get(), "currentUser should be resolved only once")
                    assertEquals(1, configCount.get(), "globalConfig should be resolved only once")
                }
        }
    }

    @Test
    fun `resolve field with queryValueFragment - inline fragment without type condition`() =
        MockLegacyTenantModuleBootstrapper("extend type Query { isEnabled: Boolean, config: String, result: String }") {
            fieldWithValue("Query" to "isEnabled", true)
            fieldWithValue("Query" to "config", "production")
            field("Query" to "result") {
                resolver {
                    querySelections("... { isEnabled config }")
                    fn { _, _, qry, _, _ ->
                        val enabled = qry.fetchAs<Boolean>("isEnabled")
                        val config = qry.fetchAs<String>("config")
                        if (enabled) "Running in $config" else "Disabled"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{result}")
                .assertJson("""{"data": {"result": "Running in production"}}""")
        }

    @Test
    fun `resolve field with queryValueFragment - handles null gracefully`() =
        MockLegacyTenantModuleBootstrapper("extend type Query { optionalValue: String, result: String }") {
            fieldWithValue("Query" to "optionalValue", null)
            field("Query" to "result") {
                resolver {
                    querySelections("optionalValue")
                    fn { _, _, qry, _, _ ->
                        val value = qry.fetch("optionalValue") as? String
                        value ?: "No value provided"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{result}")
                .assertJson("""{"data": {"result": "No value provided"}}""")
        }

    @Test
    fun `resolve mutation with queryValueFragment`() =
        MockLegacyTenantModuleBootstrapper("extend type Query { string1: String } extend type Mutation { string1: String }") {
            fieldWithValue("Query" to "string1", "InitialValue")
            field("Mutation" to "string1") {
                resolver {
                    querySelections("string1")
                    fn { _, _, qry, _, _ ->
                        val currentValue = qry.fetchAs<String>("string1")
                        "Mutated from: $currentValue"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("mutation { string1 }")
                .assertJson("{data: {string1: \"Mutated from: InitialValue\"}}")
        }

    @Test
    fun `resolve field with queryValueFragment - nested object access`() =
        MockLegacyTenantModuleBootstrapper("extend type Query { bar: Bar, baz: Baz } type Bar { value: String } type Baz { x: Int, y: String }") {
            fieldWithValue("Query" to "bar", createEngineObjectData(schema.schema.getObjectType("Bar"), mapOf()))
            fieldWithValue("Bar" to "value", "BarValue")
            fieldWithValue("Query" to "baz", createEngineObjectData(schema.schema.getObjectType("Baz"), mapOf()))
            field("Baz" to "y") {
                resolver {
                    querySelections("bar { value }")
                    fn { _, _, qry, _, _ ->
                        val bar = qry.fetchAs<EngineObjectData>("bar")
                        val barValue = bar.fetch("value")
                        "Baz sees bar value: $barValue"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{baz { y }}")
                .assertJson("{data: {baz: {y: \"Baz sees bar value: BarValue\"}}}")
        }

    @Test
    fun `resolve field with queryValueFragment - typed inline fragment`() =
        MockLegacyTenantModuleBootstrapper("extend type Query { enabled: Boolean, message: String, status: String }") {
            fieldWithValue("Query" to "enabled", false)
            fieldWithValue("Query" to "message", "System offline")
            field("Query" to "status") {
                resolver {
                    querySelections("... on Query { enabled message }")
                    fn { _, _, qry, _, _ ->
                        val enabled = qry.fetchAs<Boolean>("enabled")
                        val message = qry.fetchAs<String>("message")
                        if (!enabled) message else "OK"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{status}")
                .assertJson("""{"data": {"status": "System offline"}}""")
        }

    @Test
    fun `queryValueFragment with unclosed brace should fail at build time`() {
        assertThrows<IllegalArgumentException> {
            MockLegacyTenantModuleBootstrapper("extend type Query { field: String, result: String }") {
                fieldWithValue("Query" to "field", "value")
                field("Query" to "result") {
                    resolver {
                        querySelections("{ field") // Missing closing brace
                        fn { _, _, _, _, _ -> "should not execute" }
                    }
                }
            }.runFeatureTest { }
        }
    }

    @Test
    fun `queryValueFragment with invalid field syntax should fail at build time`() {
        assertThrows<IllegalArgumentException> {
            MockLegacyTenantModuleBootstrapper("extend type Query { field: String, result: String }") {
                fieldWithValue("Query" to "field", "value")
                field("Query" to "result") {
                    resolver {
                        querySelections("field(") // Invalid - parenthesis without arguments
                        fn { _, _, _, _, _ -> "should not execute" }
                    }
                }
            }
        }
    }

    @Test
    fun `queryValueFragment referencing non-existent field should fail at build time`() {
        assertThrows<RequiredSelectionsAreInvalid> {
            MockLegacyTenantModuleBootstrapper("extend type Query { existingField: String, result: String }") {
                fieldWithValue("Query" to "existingField", "value")
                field("Query" to "result") {
                    resolver {
                        querySelections("nonExistentField") // Field doesn't exist in schema
                        fn { _, _, _, _, _ -> "should not execute" }
                    }
                }
            }.runFeatureTest { }
        }
    }

    @Test
    fun `queryValueFragment with invalid fragment syntax should fail at build time`() {
        assertThrows<IllegalArgumentException> {
            MockLegacyTenantModuleBootstrapper("extend type Query { field: String, result: String }") {
                fieldWithValue("Query" to "field", "value")
                field("Query" to "result") {
                    resolver {
                        querySelections("fragment on Query { field }") // Missing fragment name
                        fn { _, _, _, _, _ -> "should not execute" }
                    }
                }
            }
        }
    }

    @Test
    fun `queryValueFragment with invalid variable syntax should fail at build time`() {
        assertThrows<IllegalArgumentException> {
            MockLegacyTenantModuleBootstrapper("extend type Query { field(arg: Int!): String, result: String }") {
                fieldWithValue("Query" to "field", "value")
                field("Query" to "result") {
                    resolver {
                        querySelections("field(arg: $)") // Invalid variable syntax
                        fn { _, _, _, _, _ -> "should not execute" }
                    }
                }
            }
        }
    }

    @Test
    fun `queryValueFragment with empty selection set should fail at build time`() {
        assertThrows<IllegalArgumentException> {
            MockLegacyTenantModuleBootstrapper("extend type Query { result: String }") {
                field("Query" to "result") {
                    resolver {
                        querySelections("{}") // Empty selection set
                        fn { _, _, _, _, _ -> "should not execute" }
                    }
                }
            }.runFeatureTest { }
        }
    }

    @Test
    fun `queryValueFragment with wrong type condition should fail at build time`() {
        assertThrows<RequiredSelectionsAreInvalid> {
            MockLegacyTenantModuleBootstrapper("extend type Query { field: String, result: String } extend type Mutation { dummy: String }") {
                fieldWithValue("Query" to "field", "value")
                field("Query" to "result") {
                    resolver {
                        querySelections("... on Mutation { field }") // Wrong type - should be Query
                        fn { _, _, _, _, _ -> "should not execute" }
                    }
                }
            }.runFeatureTest { }
        }
    }
}
