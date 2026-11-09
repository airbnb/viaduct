@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.execution

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import viaduct.arbitrary.common.CheckedArb
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.common.DeepArbSuite
import viaduct.arbitrary.common.withCheck
import viaduct.arbitrary.graphql.CheckerErrorWeight
import viaduct.arbitrary.graphql.CheckerExceptionWeight
import viaduct.arbitrary.graphql.CheckerExecutor
import viaduct.arbitrary.graphql.CheckerExecutorFactory
import viaduct.arbitrary.graphql.DeterministicResolveWeight
import viaduct.arbitrary.graphql.FieldCheckerWeight
import viaduct.arbitrary.graphql.FieldResolver
import viaduct.arbitrary.graphql.FieldResolverExceptionWeight
import viaduct.arbitrary.graphql.FieldResolverFactory
import viaduct.arbitrary.graphql.NodeResolverExceptionWeight
import viaduct.arbitrary.graphql.ResolverFieldRefWeight
import viaduct.arbitrary.graphql.SelectedTypeBias
import viaduct.arbitrary.graphql.SelectiveResolverWeight
import viaduct.arbitrary.graphql.TypeCheckerWeight
import viaduct.arbitrary.graphql.UndeclaredFieldResolverWeight
import viaduct.arbitrary.graphql.VariablesResolverExceptionWeight
import viaduct.arbitrary.graphql.asViaductSchema
import viaduct.arbitrary.graphql.viaduct
import viaduct.arbitrary.graphql.viaductExecutionInput
import viaduct.engine.EngineConfiguration
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelection
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.FromObjectFieldVariable
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.MockFieldBatchResolverExecutor
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.MockTenantModuleBootstrapper
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.createRSS
import viaduct.engine.api.mocks.featureTestDefault
import viaduct.engine.api.mocks.fetchAs
import viaduct.engine.api.mocks.getAs
import viaduct.engine.api.select.SelectionsParser
import viaduct.engine.api.spi.VariableFromFieldDefinitions
import viaduct.engine.runtime.execution.SelectiveFieldExecutionContract
import viaduct.engine.runtime2.execution.testing.Runtime2FeatureTest
import viaduct.engine.runtime2.execution.testing.runRuntime2FeatureTest as runWithRuntime2
import viaduct.graphql.test.assertMatches
import viaduct.service.api.ExecutionInput
import viaduct.service.api.Viaduct

/**
 * # About This Test Suite
 *
 * This suite includes property tests that can be used to discover new regressions.
 * These tests follow a convention of being named 'ArbitraryTests', and are
 * modeled as a [Nested] class that implements [DeepArbSuite].
 *
 * See the docs on [DeepArbSuite] for more information on how to use each of its
 * run modes
 */
class SelectiveFieldExecutionContractTest : SelectiveFieldExecutionContract, Engine2QueryExecutionContract {
    @Test
    fun `selective field resolvers receive each input's requested fields and dependencies`() {
        val receivedDetailsSelections = ConcurrentLinkedQueue<Pair<Int, Set<String>>>()
        val module = selectiveProductDetails(
            resolveDetails = { key, requestedFields ->
                receivedDetailsSelections += key to requestedFields
                mapOf("url" to "url-$key", "caption" to "caption-$key")
                    .filterKeys { it in requestedFields }
            },
            // label needs details.caption even though the client never requests caption directly.
            labelRequiredSelections = "details { caption }",
            resolveLabel = { product ->
                val details = product.get("details") as EngineObjectData.Sync
                "label:${details.get("caption")}"
            },
        )

        module.runWithRuntime2 {
            val result = runQueryWithTimeout(
                """
                {
                  first: product(key: 1) { details { url } label }
                  second: product(key: 2) { details { url } }
                }
                """.trimIndent(),
            )

            result.assertJson(
                """
                {
                  "data": {
                    "first": {"details": {"url": "url-1"}, "label": "label:caption-1"},
                    "second": {"details": {"url": "url-2"}}
                  }
                }
                """.trimIndent(),
            )
            assertEquals(
                listOf(
                    1 to setOf("url", "caption"),
                    2 to setOf("url"),
                ),
                receivedDetailsSelections.sortedBy { it.first },
            )
        }
    }

    private fun selectiveProductDetails(
        resolveDetails: suspend (Int, Set<String>) -> Map<String, Any?>,
        labelRequiredSelections: String,
        resolveLabel: suspend (EngineObjectData.Sync) -> String,
    ): EngineTestModule =
        EngineTestModule(
            """
            extend type Query {
                product(key: Int!): Product @resolver
            }
            type Product {
                key: Int!
                details: Details @resolver
                label: String @resolver
            }
            type Details {
                url: String
                caption: String
            }
            """.trimIndent(),
        ) {
            field("Query" to "product") {
                resolver {
                    fn { arguments, _, _, _, _ ->
                        val key = arguments.getValue("key")
                        createEngineObjectData("Product", mapOf("key" to key))
                    }
                }
            }
            field("Product" to "details") {
                resolverExecutor {
                    MockFieldBatchResolverExecutor(
                        resolverId = resolverId,
                        isSelective = true,
                        objectSelectionSet = createRSS("Product", "key"),
                    ) { selectors, _ ->
                        selectors.reversed().associateWith { selector ->
                            val key = selector.syncObjectValueGetter().get("key") as Int
                            val requestedFields = requireNotNull(selector.selections).selections().map { it.fieldName }.toSet()
                            Result.success(createEngineObjectData("Details", resolveDetails(key, requestedFields)))
                        }
                    }
                }
            }
            field("Product" to "label") {
                resolver {
                    objectSelections(labelRequiredSelections)
                    fn { _, product, _, _, _ -> resolveLabel(product) }
                }
            }
        }

    @Engine2Disabled(
        Engine2DisabledKind.ALT,
        "Production excludes resolver-owned Foo.x from selective parent output; runtime2 permits argumentless registered descendants to be source-owned, so its one-shot alternative omits x from the parent result"
    )
    override fun `selective field skipped in query is selected in RSS`() {}

    @Engine2Disabled(Engine2DisabledKind.TODO, "Registry rejects structural resolver cycles even when an RSS edge is statically skipped")
    override fun `selective field is selected in one RSS but in a skipped fragment in another RSS`() {}

    @Engine2Disabled(
        Engine2DisabledKind.ALT,
        "Tests precedence across multiple covering materializations; runtime2 produces the same values from one closed-demand application"
    )
    override fun `surplus coverage uses values from the first covering result`() {}

    @Engine2Disabled(
        Engine2DisabledKind.ALT,
        "Expects a second selective-source materialization for nested RSS coverage; runtime2 closes that demand before one application"
    )
    override fun `missing nested rss coverage rematerializes selective source`() {}

    @Engine2Disabled(Engine2DisabledKind.TODO, "Batching")
    override fun `recursive selective batching field supplies its own rss`() {}

    @Engine2Disabled(
        Engine2DisabledKind.ALT,
        "Tests per-runtime-type rematerialization coverage; runtime2 supplies both concrete dependencies from one conservative application"
    )
    override fun `partial rss coverage across list item types rematerializes only uncovered types`() {}

    @Engine2Disabled(
        Engine2DisabledKind.ALT,
        "Tests positional reconciliation when repeated materializations change list size; runtime2 represents the same final item outcomes in one stable result"
    )
    override fun `list size changes across materializations leave unmatched items unresolved`() {}

    @Engine2Disabled(
        Engine2DisabledKind.ALT,
        "Tests a runtime-type change between materializations; runtime2 produces the same final nullable leaf from one stable runtime type"
    )
    override fun `nested list item type changes across materializations report a field error`() {}

    @Engine2Disabled(
        Engine2DisabledKind.ALT,
        "Expects argument-distinct passive values from separate materializations; runtime2 resolves each argument-bearing field actively under one parent application"
    )
    override fun `rss aliases with different arguments remain isolated across materializations`() {}

    @Engine2Disabled(
        Engine2DisabledKind.ALT,
        "Routes an argument-bearing RSS field through passive rematerialization; runtime2 resolves it actively and preserves the provider failure at the consumer field"
    )
    override fun `rss variables resolver failure during rematerialization reports a field error`() {}

    @Engine2Disabled(
        Engine2DisabledKind.ALT,
        "Uses a callback-owned RSS and asks a selective owner to supply an argument-bearing field; runtime2 uses a declarative provider and resolves argument-bearing fields actively"
    )
    override fun `selective-owned sibling supplies required rss variable`() {}

    @Engine2Disabled(
        Engine2DisabledKind.ALT,
        "Supplies argument-bearing child fields through embedded materialization; runtime2 resolves those fields actively beneath one selective parent application"
    )
    override fun `embedded materialization preserves fragment argument variables across child plans`() {}

    @Engine2Disabled(
        Engine2DisabledKind.ALT,
        "Supplies an argument-bearing child field through embedded materialization; runtime2 rejects resolver output for argument-bearing fields and resolves them actively beneath one selective parent application"
    )
    override fun `embedded materialization preserves ancestor argument variables across child plans`() {}

    @Engine2Disabled(
        Engine2DisabledKind.ALT,
        "Uses a callback-owned RSS for a conditional dependency chain; runtime2 expresses the chain as a declarative object-field provider and resolves it one-shot"
    )
    override fun `variable rss does not use skipped child object plan`() {}

    @Engine2Disabled(Engine2DisabledKind.TODO, "ProviderRSS: Function variables providers with their own required selections are unsupported")
    override fun `type checker reads conditional field from selective source`() {}

    @Engine2Disabled(Engine2DisabledKind.TODO, "Batching")
    override fun `batched selective field resolver batches distinct rematerialization shapes`() {}

    @Engine2Disabled(
        Engine2DisabledKind.ALT,
        "Tests a null second materialization; runtime2 represents the same nullable-consumer result from one stable object with a null dependency"
    )
    override fun `null rematerialization reports a field error at nullable consumer`() {}

    @Engine2Disabled(
        Engine2DisabledKind.ALT,
        "Tests a null second materialization beneath a non-null consumer; runtime2 produces the same bubbling from one stable object with a null dependency"
    )
    override fun `null rematerialization bubbles through non-null consumer`() {}

    @Engine2Disabled(
        Engine2DisabledKind.ALT,
        "Tests a root runtime-type change between materializations; runtime2 preserves the final nullable leaf and error path from one stable runtime type"
    )
    override fun `type changes across root materializations report a field error`() {}

    @Engine2Disabled(
        Engine2DisabledKind.ALT,
        "Tests a nested runtime-type change between materializations; runtime2 preserves the final nullable leaf and error path from one stable runtime type"
    )
    override fun `type changes across nested object materializations report a field error`() {}

    @Engine2Disabled(
        Engine2DisabledKind.ALT,
        "Tests malformed nested output only on a second materialization; runtime2 preserves the final nullable leaf and error path from one stable malformed dependency value"
    )
    override fun `malformed nested object from rematerialization reports a field error`() {}

    @Engine2Disabled(
        Engine2DisabledKind.ALT,
        "Tests a selective producer exception on a second materialization; runtime2 preserves the final consumer error from one stable producer result"
    )
    override fun `resolver exceptions during rematerialization report a field error`() {}

    @Engine2Disabled(Engine2DisabledKind.TODO, "ErrorData")
    override fun `selective resolver rematerializes DataFetcherResult list items`() {}

    @Engine2Disabled(Engine2DisabledKind.NA, "Asserts production materialization source identity through GraphQL field instrumentation")
    override fun `mat backed traversal keeps the selective resolver result as its source`() {}

    @Engine2Disabled(Engine2DisabledKind.NA, "Asserts two production field-fetch callbacks for staged rematerialization; Resolution resolves once")
    override fun `selective field resolver materialization invokes field fetching instrumentation`() {}

    @Engine2Disabled(Engine2DisabledKind.TODO, "AccessChk")
    override fun `selective field materialization reuses field checker`() {}

    @Engine2Disabled(Engine2DisabledKind.TODO, "AccessChk")
    override fun `field checker failure after selective materialization is reported`() {}

    @Engine2Disabled(Engine2DisabledKind.TODO, "AccessChk")
    override fun `type checker reads multiple fields from selective source`() {}

    @Engine2Disabled(Engine2DisabledKind.TODO, "AccessChk")
    override fun `field checker reads its selective field`() {}

    @Engine2Disabled(Engine2DisabledKind.TODO, "Directive: Canonical demand conversion does not preserve custom resolver-directive spelling")
    override fun `materialization preserves resolver selection directives`() {}

    @Engine2Disabled(
        Engine2DisabledKind.ALT,
        "Uses a later materialization to distinguish client and RSS hydration of one selected field; runtime2 requires one explicit passive value"
    )
    override fun `resolver does not hydrate a selected field in its output selection set`() {}

    @Engine2Disabled(
        Engine2DisabledKind.ALT,
        "Expects argument-distinct passive values from separate output selection sets; runtime2 resolves both argument-bearing occurrences actively"
    )
    override fun `client and rss arguments remain isolated in output selection set`() {}

    @Nested
    inner class RssTests {
        @Engine2Alternate(original = "selective field skipped in query is selected in RSS")
        fun `ALTERNATIVE selective field skipped in query is selected in RSS`() {
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
                                    "Foo",
                                    buildMap {
                                        if (sels!!.containsField("Foo", "y")) put("y", 3)
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
            }.runRuntime2FeatureTest {
                runQueryWithTimeout(
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
    }

    @Nested
    inner class CoverageTests {
        @Engine2Alternate(original = "surplus coverage uses values from the first covering result")
        fun `ALTERNATIVE surplus coverage uses values from the first covering result`() {
            val fooCalls = AtomicInteger()

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
                                fooCalls.incrementAndGet()
                                createEngineObjectData("Foo", mapOf("z" to 2, "w" to 3))
                            }
                        )
                    }
                }

                field("Foo" to "x") {
                    resolver {
                        objectSelections("z")
                        fn { _, obj, _, _, _ -> obj.fetchAs<Int>("z") * 5 }
                    }
                }

                field("Foo" to "y") {
                    resolver {
                        objectSelections("w")
                        fn { _, obj, _, _, _ -> obj.fetchAs<Int>("w") * 7 }
                    }
                }
            }.runRuntime2FeatureTest {
                runQueryWithTimeout("{ foo { x y } }")
                    .assertJson("{data: {foo: {x: 10, y: 21}}}")
            }

            assertEquals(1, fooCalls.get())
        }

        @Engine2Alternate(original = "missing nested rss coverage rematerializes selective source")
        fun `ALTERNATIVE missing nested rss coverage rematerializes selective source`() {
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
                                val barData = if (sels!!.containsField("Foo", "bar")) {
                                    mapOf<String, Any?>("y" to 2)
                                } else {
                                    emptyMap()
                                }
                                createEngineObjectData("Foo", mapOf("bar" to barData))
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
            }.runRuntime2FeatureTest {
                runQueryWithTimeout("{ foo { x } }")
                    .assertJson("{data: {foo: {x: 6}}}")
            }

            assertEquals(1, fooCalls.get())
        }
    }

    @Nested
    inner class ListTests {
        @Engine2Alternate(original = "partial rss coverage across list item types rematerializes only uncovered types")
        fun `ALTERNATIVE partial rss coverage across list item types rematerializes only uncovered types`() {
            val fooCalls = AtomicInteger()

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
                            unbatchedResolveFn = { _, _, _, _, _ ->
                                fooCalls.incrementAndGet()
                                createEngineObjectData(
                                    "Foo",
                                    mapOf(
                                        "bars" to listOf(
                                            createEngineObjectData("Baz", mapOf("y" to 2)),
                                            createEngineObjectData("Qux", mapOf("y" to 3)),
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
            }.runRuntime2FeatureTest {
                runQueryWithTimeout(
                    "{ foo { bars { ... on Baz { x } ... on Qux { x } } } }"
                ).assertJson("{data: {foo: {bars: [{x: 14}, {x: 33}]}}}")
            }

            assertEquals(1, fooCalls.get())
        }

        @Engine2Alternate(original = "list size changes across materializations leave unmatched items unresolved")
        fun `ALTERNATIVE list size changes across materializations leave unmatched items unresolved`() {
            val fooCalls = AtomicInteger()

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
                            unbatchedResolveFn = { _, _, _, _, _ ->
                                fooCalls.incrementAndGet()
                                createEngineObjectData(
                                    "Foo",
                                    mapOf(
                                        "bars" to listOf(
                                            createEngineObjectData("Bar", mapOf("y" to 2)),
                                            createEngineObjectData("Bar", mapOf("y" to null)),
                                        ),
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
            }.runRuntime2FeatureTest {
                runQueryWithTimeout("{ foo { bars { x } } }").assertMatches {
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

            assertEquals(1, fooCalls.get())
        }

        @Engine2Alternate(original = "nested list item type changes across materializations report a field error")
        fun `ALTERNATIVE nested list item type changes across materializations report a field error`() {
            val fooCalls = AtomicInteger()

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
                            unbatchedResolveFn = { _, _, _, _, _ ->
                                fooCalls.incrementAndGet()
                                createEngineObjectData(
                                    "Foo",
                                    mapOf(
                                        "bars" to listOf(
                                            createEngineObjectData("Baz", mapOf("y" to null)),
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
            }.runRuntime2FeatureTest {
                runQueryWithTimeout(
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
                            "path" to listOf("foo", "bars", "0", "x")
                        }
                    )
                }
            }

            assertEquals(1, fooCalls.get())
        }
    }

    @Nested
    inner class VariablesTests {
        @Engine2Disabled(
            Engine2DisabledKind.ALT,
            "Runtime2 closes selective demand before its one producer invocation instead of rematerializing the producer"
        )
        fun `selective field inputs survive rematerialization`() {
            var initialRequestContext: Any? = null

            MockTenantModuleBootstrapper(
                """
                    extend type Query { x: Int, foo(y: Int!): Foo }
                    type Foo { x: Int, y: Int }
                """.trimIndent()
            ) {
                field("Query" to "x") {
                    resolver {
                        fn { _, _, _, _, _ -> 2 }
                    }
                }

                field("Query" to "foo") {
                    resolverExecutor {
                        MockFieldUnbatchedResolverExecutor(
                            isSelective = true,
                            objectSelectionSet = createRSS("Query", "x"),
                            resolverId = resolverId,
                            unbatchedResolveFn = { arguments, obj, _, sels, context ->
                                val isMaterialization = sels!!.containsField("Foo", "y")
                                if (isMaterialization) {
                                    check(context.requestContext === initialRequestContext)
                                } else {
                                    initialRequestContext = context.requestContext
                                }
                                createEngineObjectData(
                                    "Foo",
                                    buildMap {
                                        if (isMaterialization) {
                                            put(
                                                "y",
                                                obj.fetchAs<Int>("x") * arguments.getAs<Int>("y"),
                                            )
                                        }
                                    },
                                )
                            }
                        )
                    }
                }

                field("Foo" to "x") {
                    resolver {
                        objectSelections("y")
                        fn { _, obj, _, _, _ -> obj.fetchAs<Int>("y") * 5 }
                    }
                }
            }.runRuntime2FeatureTest {
                runQueryWithTimeout(
                    "query(\$y: Int!) { foo(y: \$y) { x } }",
                    variables = mapOf("y" to 3),
                ).assertJson("{data: {foo: {x: 30}}}")
            }
        }

        @Engine2Alternate(original = "selective field inputs survive rematerialization")
        fun `ALTERNATIVE selective field inputs receive closed demand`() {
            MockTenantModuleBootstrapper(
                """
                    extend type Query { x: Int, foo(y: Int!): Foo }
                    type Foo { x: Int, y: Int }
                """.trimIndent()
            ) {
                field("Query" to "x") {
                    resolver {
                        fn { _, _, _, _, _ -> 2 }
                    }
                }

                field("Query" to "foo") {
                    resolverExecutor {
                        MockFieldUnbatchedResolverExecutor(
                            isSelective = true,
                            objectSelectionSet = createRSS("Query", "x"),
                            resolverId = resolverId,
                            unbatchedResolveFn = { arguments, obj, _, sels, context ->
                                check(sels!!.containsField("Foo", "y"))
                                checkNotNull(context.requestContext)
                                createEngineObjectData(
                                    "Foo",
                                    mapOf(
                                        "y" to obj.fetchAs<Int>("x") * arguments.getAs<Int>("y"),
                                    ),
                                )
                            }
                        )
                    }
                }

                field("Foo" to "x") {
                    resolver {
                        objectSelections("y")
                        fn { _, obj, _, _, _ -> obj.fetchAs<Int>("y") * 5 }
                    }
                }
            }.runRuntime2FeatureTest {
                runQueryWithTimeout(
                    "query(\$y: Int!) { foo(y: \$y) { x } }",
                    variables = mapOf("y" to 3),
                ).assertJson("{data: {foo: {x: 30}}}")
            }
        }

        @Engine2Alternate(original = "rss aliases with different arguments remain isolated across materializations")
        fun `ALTERNATIVE rss aliases with different arguments remain isolated across materializations`() {
            val fooCalls = AtomicInteger()

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
                            unbatchedResolveFn = { _, _, _, _, _ ->
                                fooCalls.incrementAndGet()
                                createEngineObjectData("Foo")
                            }
                        )
                    }
                }

                field("Foo" to "z") {
                    resolver {
                        fn { args, _, _, _, _ -> args.getAs<Int>("x") * 5 }
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
            }.runRuntime2FeatureTest {
                runQueryWithTimeout("{ foo { x y } }")
                    .assertJson("{data: {foo: {x:70, y:165}}}")
            }

            assertEquals(1, fooCalls.get())
        }

        @Engine2Alternate(original = "rss variables resolver failure during rematerialization reports a field error")
        fun `ALTERNATIVE rss variables resolver failure during rematerialization reports a field error`() {
            val fooCalls = AtomicInteger()

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
                            unbatchedResolveFn = { _, _, _, _, _ ->
                                fooCalls.incrementAndGet()
                                createEngineObjectData("Foo")
                            }
                        )
                    }
                }

                field("Foo" to "y") {
                    resolver {
                        fn { args, _, _, _, _ -> args.getAs<Int>("x") }
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
            }.runRuntime2FeatureTest {
                runQueryWithTimeout("{ foo { x } }").assertMatches {
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

            assertEquals(1, fooCalls.get())
        }

        @Engine2Alternate(original = "variable rss does not use skipped child object plan")
        fun `ALTERNATIVE variable rss does not use skipped child object plan`() {
            MockTenantModuleBootstrapper(
                """
                    extend type Query { a: Int, b: Int, foo: Foo! @resolver, sink(x: Boolean): Int }
                    type Foo { y: Int, z: Boolean! }
                """.trimIndent()
            ) {
                field("Query" to "b") {
                    resolverExecutor {
                        val objectSelections = SelectionsParser.parse(
                            "Query",
                            "sink(x: \$includeFoo), foo { z y }",
                        )
                        val variablesResolvers = VariablesResolver.fromSelectionSetVariables(
                            objectSelections = objectSelections,
                            querySelections = null,
                            variables = listOf(
                                FromObjectFieldVariable("includeFoo", "foo.z"),
                            ),
                            forChecker = false,
                        )
                        MockFieldUnbatchedResolverExecutor(
                            objectFieldVariables = VariableFromFieldDefinitions(mapOf("includeFoo" to "foo.z")),
                            objectSelectionSet = RequiredSelectionSet(
                                selections = objectSelections,
                                variablesResolvers = variablesResolvers,
                                forChecker = false,
                            ),
                            resolverId = resolverId,
                            unbatchedResolveFn = { _, _, _, _, _ -> 2 },
                        )
                    }
                }

                field("Query" to "sink") {
                    resolverExecutor {
                        MockFieldUnbatchedResolverExecutor(
                            resolverId = resolverId,
                            unbatchedResolveFn = { _, _, _, _, _ -> 0 },
                        )
                    }
                }

                field("Query" to "a") {
                    resolverExecutor {
                        MockFieldUnbatchedResolverExecutor(
                            objectSelectionSet = createRSS("Query", "b, foo { z y }"),
                            isSelective = true,
                            resolverId = resolverId,
                            unbatchedResolveFn = { _, obj, _, _, _ ->
                                obj.fetch("b")
                                val foo = obj.fetchAs<EngineObjectData>("foo")
                                foo.fetch("y")
                                foo.fetch("z")
                                1
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
                                val values = buildMap {
                                    if (sels!!.containsField("Foo", "y")) {
                                        put("y", 4)
                                    }
                                }
                                createEngineObjectData("Foo", values)
                            }
                        )
                    }
                }

                field("Foo" to "z") {
                    resolverExecutor {
                        MockFieldUnbatchedResolverExecutor(
                            objectSelectionSet = createRSS("Foo", "y"),
                            isSelective = true,
                            resolverId = resolverId,
                            unbatchedResolveFn = { _, obj, _, _, _ ->
                                obj.fetchAs<Int>("y")
                                false
                            }
                        )
                    }
                }
            }.runRuntime2FeatureTest {
                runQueryWithTimeout(
                    """
                        query (${"$"}includeA: Boolean! = false) {
                          b
                          a @include(if: ${"$"}includeA)
                        }
                    """.trimIndent()
                ).assertJson("{data: {b: 2}}")
            }
        }

        @Engine2Alternate(original = "embedded materialization preserves ancestor argument variables across child plans")
        fun `ALTERNATIVE embedded materialization preserves ancestor argument variables across child plans`() {
            MockTenantModuleBootstrapper(
                """
                    extend type Query { foo: Foo }
                    type Foo { bar(y: Int!): Bar }
                    type Bar { x: Int, y: Int }
                """.trimIndent()
            ) {
                field("Query" to "foo") {
                    resolverExecutor {
                        MockFieldUnbatchedResolverExecutor(
                            isSelective = true,
                            resolverId = resolverId,
                            unbatchedResolveFn = { _, _, _, _, _ ->
                                createEngineObjectData("Foo")
                            },
                        )
                    }
                }

                field("Foo" to "bar") {
                    resolver {
                        fn { args, _, _, _, _ ->
                            createEngineObjectData(
                                "Bar",
                                mapOf("y" to args.getAs<Int>("y")),
                            )
                        }
                    }
                }

                field("Bar" to "x") {
                    resolver {
                        objectSelections("y")
                        fn { _, obj, _, _, _ -> obj.fetchAs<Int>("y") * 5 }
                    }
                }
            }.runRuntime2FeatureTest {
                runQueryWithTimeout(
                    "query(\$y: Int!) { foo { bar(y: \$y) { x } } }",
                    variables = mapOf("y" to 2),
                ).assertJson("{data: {foo: {bar: {x: 10}}}}")
            }
        }

        @Engine2Alternate(original = "selective-owned sibling supplies required rss variable")
        fun `ALTERNATIVE selective-owned sibling supplies required rss variable`() {
            val fooCalls = AtomicInteger()

            MockTenantModuleBootstrapper(
                """
                    extend type Query { foo: Foo }
                    type Foo { bar: Bar }
                    type Bar { x: Int, y(z: Int!): Int, z: Int! }
                """.trimIndent()
            ) {
                field("Query" to "foo") {
                    resolverExecutor {
                        MockFieldUnbatchedResolverExecutor(
                            isSelective = true,
                            resolverId = resolverId,
                            unbatchedResolveFn = { _, _, _, sels, _ ->
                                fooCalls.incrementAndGet()
                                val fooData = mutableMapOf<String, Any?>()
                                if (sels!!.containsField("Foo", "bar")) {
                                    val barSelections = sels.selectionSetForField("Foo", "bar")
                                    val barData = mutableMapOf<String, Any?>()
                                    if (barSelections.containsField("Bar", "z")) {
                                        barData["z"] = 2
                                    }
                                    fooData["bar"] = createEngineObjectData("Bar", barData)
                                }
                                createEngineObjectData("Foo", fooData)
                            }
                        )
                    }
                }

                field("Bar" to "y") {
                    resolver {
                        fn { args, _, _, _, _ -> args.getAs<Int>("z") * 3 }
                    }
                }

                field("Bar" to "x") {
                    resolverExecutor {
                        val objectSelections = SelectionsParser.parse("Bar", "y(z: \$z), z")
                        val variablesResolvers = VariablesResolver.fromSelectionSetVariables(
                            objectSelections = objectSelections,
                            querySelections = null,
                            variables = listOf(FromObjectFieldVariable("z", "z")),
                            forChecker = false,
                        )
                        MockFieldUnbatchedResolverExecutor(
                            objectFieldVariables = VariableFromFieldDefinitions(mapOf("z" to "z")),
                            objectSelectionSet = RequiredSelectionSet(
                                selections = objectSelections,
                                variablesResolvers = variablesResolvers,
                                forChecker = false,
                            ),
                            resolverId = resolverId,
                            unbatchedResolveFn = { _, obj, _, _, _ -> obj.fetchAs<Int>("y") * 5 },
                        )
                    }
                }
            }.runRuntime2FeatureTest {
                runQueryWithTimeout("{ foo { bar { x } } }")
                    .assertJson("{data: {foo: {bar: {x: 30}}}}")
            }

            assertEquals(1, fooCalls.get())
        }

        @Engine2Alternate(original = "embedded materialization preserves fragment argument variables across child plans")
        fun `ALTERNATIVE embedded materialization preserves fragment argument variables across child plans`() {
            val fooCalls = AtomicInteger()

            MockTenantModuleBootstrapper(
                """
                    extend type Query { foo: Foo }
                    type Foo { bar(y: Int!): Bar }
                    type Bar { x: Int, y(x: Int!): Int, sourceY: Int! }
                """.trimIndent()
            ) {
                field("Query" to "foo") {
                    resolverExecutor {
                        MockFieldUnbatchedResolverExecutor(
                            isSelective = true,
                            resolverId = resolverId,
                            unbatchedResolveFn = { _, _, _, _, _ ->
                                fooCalls.incrementAndGet()
                                createEngineObjectData("Foo")
                            },
                        )
                    }
                }

                field("Foo" to "bar") {
                    resolver {
                        fn { args, _, _, _, _ ->
                            createEngineObjectData(
                                "Bar",
                                mapOf("sourceY" to args.getAs<Int>("y")),
                            )
                        }
                    }
                }

                field("Bar" to "y") {
                    resolver {
                        objectSelections("sourceY")
                        fn { args, obj, _, _, _ ->
                            args.getAs<Int>("x") * obj.fetchAs<Int>("sourceY")
                        }
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
            }.runRuntime2FeatureTest {
                runQueryWithTimeout(
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

            assertEquals(1, fooCalls.get())
        }
    }

    @Nested
    inner class ConsistencyTests {
        @Engine2Alternate(original = "null rematerialization reports a field error at nullable consumer")
        fun `ALTERNATIVE null rematerialization reports a field error at nullable consumer`() {
            verifyNullSourceCompletion(nonNullConsumer = false)
        }

        @Engine2Alternate(original = "null rematerialization bubbles through non-null consumer")
        fun `ALTERNATIVE null rematerialization bubbles through non-null consumer`() {
            verifyNullSourceCompletion(nonNullConsumer = true)
        }

        private fun verifyNullSourceCompletion(nonNullConsumer: Boolean) {
            val fooCalls = AtomicInteger()
            val consumerType = if (nonNullConsumer) "Int!" else "Int"

            MockTenantModuleBootstrapper(
                """
                extend type Query { foo: Foo }
                type Foo { x:Int, y:$consumerType }
                """.trimIndent()
            ) {
                field("Query" to "foo") {
                    resolverExecutor {
                        MockFieldUnbatchedResolverExecutor(
                            isSelective = true,
                            resolverId = resolverId,
                            unbatchedResolveFn = { _, _, _, _, _ ->
                                fooCalls.incrementAndGet()
                                createEngineObjectData("Foo", mapOf("x" to null))
                            }
                        )
                    }
                }

                field("Foo" to "y") {
                    resolverExecutor {
                        MockFieldUnbatchedResolverExecutor(
                            objectSelectionSet = createRSS("Foo", "x"),
                            resolverId = resolverId,
                            unbatchedResolveFn = { _, obj, _, _, _ -> obj.fetchAs<Int>("x") * 3 }
                        )
                    }
                }
            }.runRuntime2FeatureTest {
                runQueryWithTimeout("{ foo { y } }").assertMatches {
                    "data" to {
                        if (nonNullConsumer) {
                            "foo" to null
                        } else {
                            "foo" to {
                                "y" to null
                            }
                        }
                    }
                    "errors" to arrayOf(
                        {
                            "path" to listOf("foo", "y")
                        }
                    )
                }
            }

            assertEquals(1, fooCalls.get())
        }

        @Engine2Alternate(original = "type changes across root materializations report a field error")
        fun `ALTERNATIVE type changes across root materializations report a field error`() {
            val fooCalls = AtomicInteger()

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
                            unbatchedResolveFn = { _, _, _, _, _ ->
                                fooCalls.incrementAndGet()
                                createEngineObjectData("Bar", mapOf("x" to null))
                            }
                        )
                    }
                }

                field("Bar" to "y") {
                    resolverExecutor {
                        MockFieldUnbatchedResolverExecutor(
                            objectSelectionSet = createRSS("Bar", "x"),
                            resolverId = resolverId,
                            unbatchedResolveFn = { _, obj, _, _, _ -> obj.fetchAs<Int>("x") * 3 }
                        )
                    }
                }
            }.runRuntime2FeatureTest {
                runQueryWithTimeout("{ foo { ... on Bar { y } } }").assertMatches {
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

            assertEquals(1, fooCalls.get())
        }

        @Engine2Alternate(original = "type changes across nested object materializations report a field error")
        fun `ALTERNATIVE type changes across nested object materializations report a field error`() {
            val fooCalls = AtomicInteger()

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
                            unbatchedResolveFn = { _, _, _, _, _ ->
                                fooCalls.incrementAndGet()
                                createEngineObjectData(
                                    "Foo",
                                    mapOf("bar" to createEngineObjectData("Baz", mapOf("x" to null))),
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
                            unbatchedResolveFn = { _, obj, _, _, _ -> obj.fetchAs<Int>("x") * 3 }
                        )
                    }
                }
            }.runRuntime2FeatureTest {
                runQueryWithTimeout(
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
                            "path" to listOf("foo", "bar", "y")
                        }
                    )
                }
            }

            assertEquals(1, fooCalls.get())
        }

        @Engine2Alternate(original = "malformed nested object from rematerialization reports a field error")
        fun `ALTERNATIVE malformed nested object from rematerialization reports a field error`() {
            val fooCalls = AtomicInteger()

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
                            unbatchedResolveFn = { _, _, _, _, _ ->
                                fooCalls.incrementAndGet()
                                createEngineObjectData(
                                    "Foo",
                                    mapOf(
                                        "bar" to createEngineObjectData("Bar", mapOf("y" to null)),
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
            }.runRuntime2FeatureTest {
                runQueryWithTimeout("{ foo { bar { x } } }").assertMatches {
                    "data" to {
                        "foo" to {
                            "bar" to {
                                "x" to null
                            }
                        }
                    }
                    "errors" to arrayOf(
                        {
                            "path" to listOf("foo", "bar", "x")
                        }
                    )
                }
            }

            assertEquals(1, fooCalls.get())
        }

        @Engine2Alternate(original = "resolver exceptions during rematerialization report a field error")
        fun `ALTERNATIVE resolver exceptions during rematerialization report a field error`() {
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
                                createEngineObjectData("Foo", mapOf("y" to 2))
                            }
                        )
                    }
                }

                field("Foo" to "x") {
                    resolver {
                        objectSelections("y")
                        fn { _, _, _, _, _ -> throw RuntimeException("one-shot dependent resolver failed") }
                    }
                }
            }.runRuntime2FeatureTest {
                runQueryWithTimeout("{ foo { x } }").assertMatches {
                    "data" to {
                        "foo" to {
                            "x" to null
                        }
                    }
                    "errors" to arrayOf(
                        {
                            "path" to listOf("foo", "x")
                        }
                    )
                }
            }

            assertEquals(1, fooCalls.get())
        }
    }

    @Nested
    inner class ResultMetadataTests {
        @Engine2Alternate(original = "selective resolver rematerializes DataFetcherResult list items")
        fun `ALTERNATIVE selective resolver rematerializes DataFetcherResult list items`() {
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
                                    createEngineObjectData(
                                        "Foo",
                                        buildMap {
                                            if (sels!!.containsField("Foo", "x")) {
                                                put("x", 2)
                                            }
                                        },
                                    )
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
            }.runRuntime2FeatureTest {
                runQueryWithTimeout("{ foo { y } }")
                    .assertJson("{data: {foo: [{y: 10}]}}")
            }
        }
    }

    @Nested
    inner class CheckerTests {
        @Nested
        inner class ArbitraryTests :
            SelectiveFieldArbTest(
                """
                    | extend type Query { foo:Foo, bars:[Bar] }
                    | type Foo { x:Int, y:Int, bar:Bar }
                    | type Bar { x:Int, y:Int }
                """.trimMargin(),
                cfg = defaultCfg + (FieldCheckerWeight to .8) + (TypeCheckerWeight to .8),
            )
    }

    @Nested
    inner class SelectionSetTests {
        @Engine2Disabled(
            Engine2DisabledKind.ALT,
            "Expects post-materialization runtime types to narrow later selection sets; runtime2 conservatively requests possible concrete demand before its one application"
        )
        fun `materialization output selections preserve path-specific concrete ownership`() {
            var materializationSelections: EngineSelectionSet? = null

            MockTenantModuleBootstrapper(
                """
                    extend type Query { foo: Foo }
                    type Foo { x: Int @resolver, bar: Qux, baz: Qux }
                    interface Qux { x: Int, y: Int }
                    type QuxA implements Qux { x: Int, y: Int @resolver }
                    type QuxB implements Qux { x: Int @resolver, y: Int }
                """.trimIndent()
            ) {
                field("Query" to "foo") {
                    resolverExecutor {
                        MockFieldUnbatchedResolverExecutor(
                            isSelective = true,
                            resolverId = resolverId,
                            unbatchedResolveFn = { _, _, _, sels, _ ->
                                val values = buildMap {
                                    if (sels!!.containsField("Foo", "bar")) {
                                        put(
                                            "bar",
                                            createEngineObjectData("QuxA", mapOf("x" to 2))
                                        )
                                    }

                                    if (sels.containsField("Foo", "baz")) {
                                        put(
                                            "baz",
                                            createEngineObjectData("QuxB", mapOf("y" to 3))
                                        )
                                    }
                                }
                                if (values.isNotEmpty()) {
                                    materializationSelections = sels
                                }
                                createEngineObjectData("Foo", values)
                            }
                        )
                    }
                }

                field("Foo" to "x") {
                    resolver {
                        objectSelections(
                            """
                                bar { x }
                                baz { y }
                            """.trimIndent()
                        )
                        fn { _, obj, _, _, _ ->
                            val bar = obj.fetchAs<EngineObjectData>("bar")
                            val baz = obj.fetchAs<EngineObjectData>("baz")
                            bar.fetchAs<Int>("x") * baz.fetchAs<Int>("y")
                        }
                    }
                }

                field("QuxA" to "y") {
                    resolver { fn { _, _, _, _, _ -> 5 } }
                }

                field("QuxB" to "x") {
                    resolver { fn { _, _, _, _, _ -> 7 } }
                }
            }.runRuntime2FeatureTest {
                runQueryWithTimeout("{ foo { x } }")
                    .assertJson("{data: {foo: {x: 6}}}")
            }

            val selections = checkNotNull(materializationSelections)

            assertEquals(
                mapOf(
                    "bar" to setOf(EngineSelection("QuxA", "x", "x")),
                    "baz" to setOf(EngineSelection("QuxB", "y", "y")),
                ),
                mapOf(
                    "bar" to
                        selections
                            .selectionSetForField("Foo", "bar")
                            .selections().toSet(),
                    "baz" to
                        selections
                            .selectionSetForField("Foo", "baz")
                            .selections().toSet(),
                ),
            )
        }

        @Engine2Alternate(original = "materialization output selections preserve path-specific concrete ownership")
        fun `ALTERNATIVE materialization output selections preserve path-specific concrete ownership`() {
            val fooCalls = AtomicInteger()

            MockTenantModuleBootstrapper(
                """
                    extend type Query { foo: Foo }
                    type Foo { x: Int @resolver, bar: Qux, baz: Qux }
                    interface Qux { x: Int, y: Int }
                    type QuxA implements Qux { x: Int, y: Int @resolver }
                    type QuxB implements Qux { x: Int @resolver, y: Int }
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
                                    "Foo",
                                    buildMap {
                                        if (sels!!.containsField("Foo", "bar")) {
                                            put("bar", createEngineObjectData("QuxA", mapOf("x" to 2)))
                                        }
                                        if (sels.containsField("Foo", "baz")) {
                                            put("baz", createEngineObjectData("QuxB", mapOf("y" to 3)))
                                        }
                                    },
                                )
                            }
                        )
                    }
                }

                field("Foo" to "x") {
                    resolver {
                        objectSelections(
                            """
                                bar { x }
                                baz { y }
                            """.trimIndent()
                        )
                        fn { _, obj, _, _, _ ->
                            val bar = obj.fetchAs<EngineObjectData>("bar")
                            val baz = obj.fetchAs<EngineObjectData>("baz")
                            bar.fetchAs<Int>("x") * baz.fetchAs<Int>("y")
                        }
                    }
                }

                field("QuxA" to "y") {
                    resolver { fn { _, _, _, _, _ -> 5 } }
                }

                field("QuxB" to "x") {
                    resolver { fn { _, _, _, _, _ -> 7 } }
                }
            }.runRuntime2FeatureTest {
                runQueryWithTimeout("{ foo { x } }")
                    .assertJson("{data: {foo: {x: 6}}}")
            }

            assertEquals(1, fooCalls.get())
        }

        @Engine2Alternate(original = "resolver does not hydrate a selected field in its output selection set")
        fun `ALTERNATIVE resolver does not hydrate a selected field in its output selection set`() {
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
                                createEngineObjectData("Foo", mapOf("y" to null))
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
            }.runRuntime2FeatureTest {
                runQueryWithTimeout("{ foo { y x } }")
                    .assertJson("{data: {foo: {y: null, x: 6}}}")
            }

            assertEquals(1, fooCalls.get())
        }

        @Engine2Alternate(original = "client and rss arguments remain isolated in output selection set")
        fun `ALTERNATIVE client and rss arguments remain isolated in output selection set`() {
            val fooCalls = AtomicInteger()

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
                            unbatchedResolveFn = { _, _, _, _, _ ->
                                fooCalls.incrementAndGet()
                                createEngineObjectData("Foo")
                            }
                        )
                    }
                }

                field("Foo" to "y") {
                    resolver {
                        fn { args, _, _, _, _ -> args.getAs<Int>("z") }
                    }
                }

                field("Foo" to "x") {
                    resolver {
                        objectSelections("y(z:2)")
                        fn { _, obj, _, _, _ -> obj.fetchAs<Int>("y") * 3 }
                    }
                }
            }.runRuntime2FeatureTest {
                runQueryWithTimeout("{ foo { y(z:1) x } }")
                    .assertJson("{data: {foo: {y:1, x:6}}}")
            }

            assertEquals(1, fooCalls.get())
        }

        @Engine2Disabled(
            Engine2DisabledKind.ALT,
            "Production ignores a selective producer value outside its output selection set; runtime2's one-shot ownership contract requires the producer not to return that surplus field"
        )
        fun `resolver hydrates unselected fields outside its output selection set`() {
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
                                createEngineObjectData("Foo", mapOf("x" to 2))
                            }
                        )
                    }
                }

                field("Foo" to "x") {
                    resolver {
                        fn { _, _, _, _, _ -> 3 }
                    }
                }
            }.runRuntime2FeatureTest {
                runQueryWithTimeout("{ foo { x } }")
                    .assertJson("{data: {foo: {x: 3}}}")
            }
        }

        @Engine2Alternate(original = "resolver hydrates unselected fields outside its output selection set")
        fun `ALTERNATIVE resolver hydrates unselected fields outside its output selection set`() {
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
                                createEngineObjectData("Foo")
                            }
                        )
                    }
                }

                field("Foo" to "x") {
                    resolver {
                        fn { _, _, _, _, _ -> 3 }
                    }
                }
            }.runRuntime2FeatureTest {
                runQueryWithTimeout("{ foo { x } }")
                    .assertJson("{data: {foo: {x: 3}}}")
            }

            assertEquals(1, fooCalls.get())
        }
    }

    private fun MockTenantModuleBootstrapper.runRuntime2FeatureTest(
        engineConfig: EngineConfiguration = EngineConfiguration.featureTestDefault,
        block: Runtime2FeatureTest.() -> Unit,
    ) {
        runWithRuntime2(engineConfig = engineConfig, block = block)
    }

    /** Helper class for managing deep Arb tests */
    abstract class SelectiveFieldArbTest(
        val sdl: String,
        val cfg: Config = defaultCfg,
        seed: Long = Random.nextLong(),
        iterations: Int = 100
    ) : DeepArbSuite<Pair<Viaduct, ExecutionInput>>(seed, iterations) {
        override val comparator = ViaductAndInputComparator

        override val checkedArb: CheckedArb<Pair<Viaduct, ExecutionInput>>
            get() {
                val schema = sdl.asViaductSchema

                return arbitrary {
                    val viaduct = Arb.viaduct(schema, cfg).bind()
                    val input = Arb.viaductExecutionInput(schema, cfg).bind()
                    viaduct to input
                }.withCheck { (viaduct, input) ->
                    val result = runCatching { viaduct.runQueryWithTimeout(input) }
                    assertTrue(result.getOrNull()?.errors?.isEmpty() == true) {
                        dump(viaduct, input, result)
                    }
                }
            }

        companion object {
            val defaultCfg: Config = Config.default +
                (UndeclaredFieldResolverWeight to .25) +
                (SelectiveResolverWeight to .5) +
                (DeterministicResolveWeight to 1.0) +
                // SelectedTypeBias can override the behavior of DeterministicResolveWeight, causing
                //  re-executing fields to return different values when called with different selections.
                //  Disabling this feature allows for true deterministic resolver behavior
                (SelectedTypeBias to 0.0) +
                (ResolverFieldRefWeight to 0.0) +
                (VariablesResolverExceptionWeight to 0.0) +
                (CheckerErrorWeight to 0.0) +
                (CheckerExceptionWeight to 0.0) +
                (FieldCheckerWeight to 0.0) +
                (TypeCheckerWeight to 0.0) +
                (NodeResolverExceptionWeight to 0.0) +
                (FieldResolverExceptionWeight to 0.0) +
                (FieldResolverFactory to FieldResolver.Factory.Instrumented()) +
                (CheckerExecutorFactory to CheckerExecutor.Factory.Instrumented())
        }
    }
}
