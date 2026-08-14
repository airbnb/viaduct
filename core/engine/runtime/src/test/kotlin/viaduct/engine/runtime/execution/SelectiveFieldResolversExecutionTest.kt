@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime.execution

import graphql.GraphQLError
import graphql.execution.DataFetcherResult
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import viaduct.arbitrary.common.CheckedArb
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.common.DeepArbSuite
import viaduct.arbitrary.common.withCheck
import viaduct.arbitrary.graphql.BatchingResolverWeight
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
import viaduct.engine.api.mocks.FeatureTest
import viaduct.engine.api.mocks.MockFieldBatchResolverExecutor
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.MockTenantModuleBootstrapper
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.createRSS
import viaduct.engine.api.mocks.featureTestDefault
import viaduct.engine.api.mocks.fetchAs
import viaduct.engine.api.mocks.getAs
import viaduct.engine.api.mocks.runFeatureTest as runEngineFeatureTest
import viaduct.graphql.test.assertMatches
import viaduct.service.api.ExecutionInput
import viaduct.service.api.Viaduct
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.mocks.MockFlagManager

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
class SelectiveFieldResolversExecutionTest {
    @Nested
    inner class BasicExecutionTests {
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
                                createEngineObjectData("Foo")
                            }
                        )
                    }
                }
            }.runFeatureTest {
                runQueryWithTimeout("{ foo { __typename } }")
                    .assertJson("{data: {foo: {__typename: \"Foo\"}}}")
            }

            assertEquals(1, fooCalls.get())
        }

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
                                    "Foo",
                                    mapOf("bar" to createEngineObjectData("Bar")),
                                )
                            }
                        )
                    }
                }
            }.runFeatureTest {
                runQueryWithTimeout("{ foo { bar { kind: __typename } } }")
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
                                createEngineObjectData("Foo", mapOf("x" to 2))
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
            }.runFeatureTest {
                runQueryWithTimeout("{b}")
                    .assertJson("{data: {b: 6}}")
            }
        }

        @Nested
        inner class ArbitraryTests :
            SelectiveFieldArbTest(
                """
                    | extend type Query { a:Int, b:Int!, foo:Foo }
                    | type Foo { x:Int, y:Int!, bar:Bar }
                    | type Bar { x:Int, y:Int! }
                """.trimMargin()
            )
    }

    @Nested
    inner class ParentFields {
        @Test
        fun `selective child object can read its non-selective parent`() {
            MockTenantModuleBootstrapper(
                """
                    extend type Query { company: Company }
                    type Company { name: String, user: User }
                    type User { parent: Company @parent, companyName: String }
                """.trimIndent()
            ) {
                field("Query" to "company") {
                    resolver {
                        fn { _, _, _, _, _ ->
                            createEngineObjectData("Company", mapOf("name" to "Airbnb"))
                        }
                    }
                }

                field("Company" to "user") {
                    resolverExecutor {
                        MockFieldUnbatchedResolverExecutor(
                            isSelective = true,
                            resolverId = resolverId,
                            unbatchedResolveFn = { _, _, _, _, _ ->
                                createEngineObjectData("User")
                            }
                        )
                    }
                }

                field("User" to "companyName") {
                    resolver {
                        objectSelections("parent { name }")
                        fn { _, obj, _, _, _ ->
                            obj.fetchAs<EngineObjectData>("parent").fetchAs<String>("name")
                        }
                    }
                }
            }.runFeatureTest {
                runQueryWithTimeout("{ company { user { companyName } } }")
                    .assertJson("{data: {company: {user: {companyName: \"Airbnb\"}}}}")
            }
        }

        @Test
        fun `parent traversal resolves a selective child field`() {
            MockTenantModuleBootstrapper(
                """
                    extend type Query { company: Company }
                    type Company { details: Details, user: User }
                    type Details { name: String }
                    type User { parent: Company @parent, companyName: String }
                """.trimIndent()
            ) {
                field("Query" to "company") {
                    resolver {
                        fn { _, _, _, _, _ ->
                            createEngineObjectData("Company")
                        }
                    }
                }

                field("Company" to "details") {
                    resolverExecutor {
                        MockFieldUnbatchedResolverExecutor(
                            isSelective = true,
                            resolverId = resolverId,
                            unbatchedResolveFn = { _, _, _, selections, _ ->
                                createEngineObjectData(
                                    "Details",
                                    buildMap {
                                        if (selections!!.containsField("Details", "name")) {
                                            put("name", "Airbnb")
                                        }
                                    }
                                )
                            }
                        )
                    }
                }

                field("Company" to "user") {
                    resolver {
                        fn { _, _, _, _, _ ->
                            createEngineObjectData("User")
                        }
                    }
                }

                field("User" to "companyName") {
                    resolver {
                        objectSelections("parent { details { name } }")
                        fn { _, obj, _, _, _ ->
                            obj.fetchAs<EngineObjectData>("parent")
                                .fetchAs<EngineObjectData>("details")
                                .fetchAs<String>("name")
                        }
                    }
                }
            }.runFeatureTest {
                runQueryWithTimeout("{ company { user { companyName } } }")
                    .assertJson("{data: {company: {user: {companyName: \"Airbnb\"}}}}")
            }
        }

        @Test
        fun `non-selective resolver restores parent traversal below selective ancestor`() {
            MockTenantModuleBootstrapper(
                """
                    extend type Query { container: Container }
                    type Container { company: Company }
                    type Company { name: String, user: User }
                    type User { parent: Company @parent, companyName: String }
                """.trimIndent()
            ) {
                field("Query" to "container") {
                    resolverExecutor {
                        MockFieldUnbatchedResolverExecutor(
                            isSelective = true,
                            resolverId = resolverId,
                            unbatchedResolveFn = { _, _, _, _, _ ->
                                createEngineObjectData("Container")
                            }
                        )
                    }
                }

                field("Container" to "company") {
                    resolver {
                        fn { _, _, _, _, _ ->
                            createEngineObjectData("Company", mapOf("name" to "Airbnb"))
                        }
                    }
                }

                field("Company" to "user") {
                    resolver {
                        fn { _, _, _, _, _ ->
                            createEngineObjectData("User")
                        }
                    }
                }

                field("User" to "companyName") {
                    resolver {
                        objectSelections("parent { name }")
                        fn { _, obj, _, _, _ ->
                            obj.fetchAs<EngineObjectData>("parent").fetchAs<String>("name")
                        }
                    }
                }
            }.runFeatureTest {
                runQueryWithTimeout("{ container { company { user { companyName } } } }")
                    .assertJson("{data: {container: {company: {user: {companyName: \"Airbnb\"}}}}}")
            }
        }
    }

    @Nested
    inner class RssTests {
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
                                    "Foo",
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
            }.runFeatureTest {
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
            }.runFeatureTest {
                runQueryWithTimeout("{ x }")
                    .assertJson("{data: {x: 1}}")
            }
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
                                    "Foo",
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
            }.runFeatureTest {
                runQueryWithTimeout(
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
                            unbatchedResolveFn = { _, _, _, _, _ -> createEngineObjectData("Foo") }
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
            }.runFeatureTest {
                runQueryWithTimeout("{ b }")
                    .assertJson("{data: {b: 60}}")
            }
        }
    }

    @Nested
    inner class CoverageTests {
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
                                    "Foo",
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
            }.runFeatureTest {
                runQueryWithTimeout("{ foo { bar { y } x } }")
                    .assertJson("{data: {foo: {bar: {y: 2}, x: 6}}}")
            }

            assertEquals(1, fooCalls.get())
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
                                    "Foo",
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
            }.runFeatureTest {
                runQueryWithTimeout("{ foo { x } }")
                    .assertJson("{data: {foo: {x: 6}}}")
            }

            assertEquals(1, fooCalls.get())
        }

        @Test
        fun `surplus coverage from a later materialization prevents another resolver call`() {
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
                                val call = fooCalls.incrementAndGet()
                                createEngineObjectData(
                                    "Foo",
                                    if (call == 1) {
                                        emptyMap()
                                    } else {
                                        mapOf("z" to 2, "w" to 3)
                                    },
                                )
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
            }.runFeatureTest {
                runQueryWithTimeout("{ foo { x y } }")
                    .assertJson("{data: {foo: {x: 10, y: 21}}}")
            }

            assertEquals(2, fooCalls.get())
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
                                    "Foo",
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
            }.runFeatureTest {
                runQueryWithTimeout("{ foo { x } }")
                    .assertJson("{data: {foo: {x: 6}}}")
            }

            assertEquals(2, fooCalls.get())
        }

        @Test
        fun `fully skipped selective field still resolves rss reads`() {
            // The client selects the selective field `foo` with a single aliased selection that a
            // literal `@skip(if: true)` removes, so the first materialization shape is empty. Query.b's
            // object RSS then needs `foo { y x }`; that coverage miss must re-invoke the mat.
            // If the recorded coverage wrongly retains the skipped aliased selection, the read of
            // `y` routes to the empty first materialization and resolves a spurious null.
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
                                    "Foo",
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
            }.runFeatureTest {
                runQueryWithTimeout(
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
                                createEngineObjectData("Foo", fooData)
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
            }.runFeatureTest {
                runQueryWithTimeout("{ foo { bar { x } } }")
                    .assertJson("{data: {foo: {bar: {x: 6}}}}")
            }
        }
    }

    @Nested
    inner class RecursiveTests {
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
                                    "Foo",
                                    if (sels!!.containsField("Foo", "next")) {
                                        mapOf(
                                            "next" to createEngineObjectData(
                                                "Foo",
                                                mapOf(
                                                    "next" to createEngineObjectData(
                                                        "Foo",
                                                        mapOf(
                                                            "next" to createEngineObjectData("Foo")
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
            }.runFeatureTest {
                runQueryWithTimeout("{ foo { x } }")
                    .assertJson("{data: {foo: {x: 1}}}")
            }
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
                            createEngineObjectData("Foo", mapOf("y" to 1))
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
                                            "Foo",
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
            }.runFeatureTest {
                runQueryWithTimeout("{ foo { next { next { __typename } } } }")
                    .assertJson("{data: {foo: {next: {next: {__typename: \"Foo\"}}}}}")
            }
        }

        @Nested
        inner class ArbitraryTests :
            SelectiveFieldArbTest(
                """
                    | extend type Query { foo:Foo }
                    | type Foo { x:Int, next:Foo, bar:Bar }
                    | type Bar { y:Int, foo:Foo }
                """.trimMargin()
            )
    }

    @Nested
    inner class ListTests {
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
                                        "Foo",
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
            }.runFeatureTest {
                runQueryWithTimeout("{ foo { y } }")
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
                                            "Foo",
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
            }.runFeatureTest {
                runQueryWithTimeout("{ foo { y } }")
                    .assertJson("{data: {foo: [[{y: 22}, {y: 33}], [{y: 55}, {y: 77}]]}}")
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
                                    "Foo",
                                    mapOf(
                                        "bars" to listOf(
                                            createEngineObjectData(
                                                "Bar",
                                                mapOf("y" to if (ySelected) 2 else 7),
                                            ),
                                            createEngineObjectData(
                                                "Bar",
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
            }.runFeatureTest {
                runQueryWithTimeout("{ foo { bars { x } } }")
                    .assertJson("{data: {foo: {bars: [{x: 10}, {x: 15}]}}}")
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
                                    "Foo",
                                    mapOf(
                                        "bars" to listOf(
                                            createEngineObjectData(
                                                "Baz",
                                                mapOf("y" to if (bazYSelected) 5 else 2),
                                            ),
                                            createEngineObjectData(
                                                "Qux",
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
            }.runFeatureTest {
                runQueryWithTimeout(
                    "{ foo { bars { ... on Baz { x } ... on Qux { x } } } }"
                ).assertJson("{data: {foo: {bars: [{x: 14}, {x: 33}]}}}")
            }
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
                                    "Foo",
                                    mapOf(
                                        "bars" to listOf(
                                            createEngineObjectData(
                                                "Bar",
                                                if (includeY) mapOf("y" to 2) else emptyMap(),
                                            ),
                                            null,
                                            createEngineObjectData(
                                                "Bar",
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
            }.runFeatureTest {
                runQueryWithTimeout("{ foo { bars { x } } }")
                    .assertJson("{data: {foo: {bars: [{x: 10}, null, {x: 15}]}}}")
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
                                    "Foo",
                                    mapOf(
                                        "bars" to if (ySelected) {
                                            listOf(createEngineObjectData("Bar", mapOf("y" to 2)))
                                        } else {
                                            listOf(
                                                createEngineObjectData("Bar"),
                                                createEngineObjectData("Bar"),
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
            }.runFeatureTest {
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
                                    "Foo",
                                    mapOf(
                                        "bars" to listOf(
                                            if (ySelected) {
                                                createEngineObjectData("Qux", mapOf("y" to 2))
                                            } else {
                                                createEngineObjectData("Baz")
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
            }.runFeatureTest {
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
                            "message" to ".*expected object of type `Baz`, found `Qux`.*"
                            "path" to listOf("foo", "bars", "0", "x")
                        }
                    )
                }
            }
        }

        @Nested
        inner class ArbitraryTests :
            SelectiveFieldArbTest(
                """
                    | extend type Query { foos:[Foo] }
                    | type Foo { x:Int, bars:[Bar] }
                    | type Bar { y:Int }
                """.trimMargin()
            )
    }

    @Nested
    inner class AbstractTypeTests {
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
                                    "Foo",
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
            }.runFeatureTest {
                runQuery("{ trigger item { ... on Foo { y } } }")
                    .assertJson("{data: {trigger: 3, item: {y: 2}}}")
            }
        }

        @Nested
        inner class ArbitraryTests :
            SelectiveFieldArbTest(
                """
                    | interface Item { x:Int }
                    | type Foo implements Item { x:Int, y:Int }
                    | type Bar implements Item { x:Int, z:Int }
                    | union Result = Foo | Bar
                    | extend type Query { item:Item, results:[Result!]! }
                """.trimMargin()
            )
    }

    @Nested
    inner class VariablesTests {
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
                                    "Foo",
                                    mapOf(
                                        "bar" to createEngineObjectData(
                                            "Bar",
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
            }.runFeatureTest {
                runQueryWithTimeout(
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
            }.runFeatureTest {
                runQueryWithTimeout(
                    "query(\$y: Int!) { foo(y: \$y) { x } }",
                    variables = mapOf("y" to 3),
                ).assertJson("{data: {foo: {x: 30}}}")
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
                                    "Foo",
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
            }.runFeatureTest {
                runQueryWithTimeout("{ foo { x y } }")
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
                                    "Foo",
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
            }.runFeatureTest {
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
        }

        @Test
        fun `variable rss does not use skipped child object plan`() {
            // Query.b has a runtime-dependent object RSS field whose variable resolver needs
            // `foo { z y }`. Query.foo is selective, so it materializes scalar Foo fields only when
            // they are selected. The requested `z` field is itself a selective resolver, and Foo.z
            // needs parent object data from its own object RSS: `y`.
            //
            // A runtime-skipped Query.a branch also plans `b` and `foo { z y }`. That unexecuted
            // branch creates another Foo.z object-RSS plan with the same RSS id as the active
            // variable-RSS path. If runtime chooses the skipped branch's plan for Foo.z's object RSS,
            // Foo.z waits for a `y` value that will never be produced.

            MockTenantModuleBootstrapper(
                """
                    extend type Query { a: Int, b: Int, foo: Foo @resolver }
                    type Foo { y: Int, z: Int }
                """.trimIndent()
            ) {
                field("Query" to "b") {
                    resolver {
                        objectSelections("__typename @include(if: ${"$"}includeFoo)") {
                            variables(
                                "includeFoo",
                                rss = createRSS("Query", "foo { z y }")
                            ) { ctx, _ ->
                                val foo = ctx.objectData.getAs<EngineObjectData.Sync>("foo")
                                foo.getAs<Int>("z")
                                mapOf("includeFoo" to false)
                            }
                        }
                        fn { _, _, _, _, _ -> 2 }
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
                                5
                            }
                        )
                    }
                }
            }.runFeatureTest {
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

        @Test
        fun `embedded materialization preserves ancestor argument variables across child plans`() {
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
                            unbatchedResolveFn = { _, _, _, sels, _ ->
                                val barSelections = sels!!.selectionSetForField("Foo", "bar")
                                createEngineObjectData(
                                    "Foo",
                                    mapOf(
                                        "bar" to createEngineObjectData(
                                            "Bar",
                                            buildMap {
                                                if (barSelections.containsField("Bar", "y")) {
                                                    val id = sels
                                                        .argumentsOfSelection("Foo", "bar")
                                                        ?.get("y") as Int
                                                    put("y", id)
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
                        objectSelections("y")
                        fn { _, obj, _, _, _ -> obj.fetchAs<Int>("y") * 5 }
                    }
                }
            }.runFeatureTest {
                runQueryWithTimeout(
                    "query(\$y: Int!) { foo { bar(y: \$y) { x } } }",
                    variables = mapOf("y" to 2),
                ).assertJson("{data: {foo: {bar: {x: 10}}}}")
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
                                    fooData["bar"] = createEngineObjectData("Bar", barData)
                                }
                                createEngineObjectData("Foo", fooData)
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
            }.runFeatureTest {
                runQueryWithTimeout("{ foo { bar { x } } }")
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
                                    "Foo",
                                    mapOf(
                                        "bar" to createEngineObjectData(
                                            "Bar",
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
            }.runFeatureTest {
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
        }

        @Nested
        inner class ArbitraryTests :
            SelectiveFieldArbTest(
                """
                    | extend type Query { foo(x:Int!):Foo }
                    | enum E { A, B }
                    | input Inp { a:Int, b:String, c:[[Int]], d:Inp, e:E }
                    | type Foo { x:Int, y(a:Int!):Int, bar(inp:Inp = {a: 2}):Bar }
                    | type Bar { x:Int, y:Int }
                """.trimMargin()
            )
    }

    @Nested
    inner class BatchedTests {
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
                                            "Foo",
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
            }.runFeatureTest {
                runQueryWithTimeout(
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

        @Nested
        inner class ArbitraryTests :
            SelectiveFieldArbTest(
                """
                    | extend type Query { foo(z:Int!):Foo, foos(z:Int!):[Foo!]!, bars:[Bar!]! }
                    | type Foo { x:Int, y(z:Int!):Int, bar(z:Int!):Bar, bars:[Bar!]! }
                    | type Bar { x:Int, y(z:Int!):Int }
                """.trimMargin(),
                cfg = defaultCfg + (BatchingResolverWeight to .8)
            )
    }

    @Nested
    inner class ConsistencyTests {
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
                                        "Foo",
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
            }.runFeatureTest {
                runQueryWithTimeout("{ foo { y } }").assertMatches {
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
                                        "Foo",
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
            }.runFeatureTest {
                runQueryWithTimeout("{ foo { y } }").assertMatches {
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
            }.runFeatureTest {
                runQueryWithTimeout("{ foo { x } }")
                    .assertJson("{data: {foo: null}}")
            }

            assertEquals(1, fooCalls.get())
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
                                    createEngineObjectData("Bar")
                                } else {
                                    createEngineObjectData("Baz")
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
            }.runFeatureTest {
                runQueryWithTimeout("{ foo { ... on Bar { y } } }").assertMatches {
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
                                    createEngineObjectData("Qux")
                                } else {
                                    createEngineObjectData("Baz")
                                }
                                createEngineObjectData(
                                    "Foo",
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
            }.runFeatureTest {
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
                                    "Foo",
                                    mapOf(
                                        "bar" to if (barSelections.containsField("Bar", "y")) {
                                            2
                                        } else {
                                            createEngineObjectData("Bar")
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
            }.runFeatureTest {
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
                                createEngineObjectData("Foo")
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
            }.runFeatureTest {
                runQueryWithTimeout("{ foo { x } }").assertMatches {
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
    }

    @Nested
    inner class ResultMetadataTests {
        @Test
        fun `errors returned during materialization are included in the response`() {
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
                                if (initialFooCall.getAndSet(false)) {
                                    createEngineObjectData("Foo")
                                } else {
                                    DataFetcherResult.newResult<EngineObjectData>()
                                        .data(createEngineObjectData("Foo", mapOf("y" to 2)))
                                        .error(
                                            GraphQLError.newError()
                                                .message("foo materialization warning")
                                                .path(listOf("foo"))
                                                .build()
                                        )
                                        .build()
                                }
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
            }.runFeatureTest {
                val result = runQueryWithTimeout("{ foo { x } }")

                assertEquals(mapOf("foo" to mapOf("x" to 6)), result.getData())
                assertEquals(
                    listOf("foo materialization warning"),
                    result.errors.map { it.message },
                )
                assertEquals(listOf("foo"), result.errors.single().path)
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
                                                "Foo",
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
            }.runFeatureTest {
                runQueryWithTimeout("{ foo { y } }")
                    .assertJson("{data: {foo: [{y: 10}]}}")
            }
        }
    }

    @Nested
    inner class InstrumentationTests {
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
                                createEngineObjectData("Foo", mapOf("x" to 2))
                                    .also { selectiveSource = it }
                            }
                        )
                    }
                }
            }.runFeatureTest(
                engineConfig = EngineConfiguration.featureTestDefault.copy(
                    additionalInstrumentation = instrumentation,
                )
            ) {
                runQueryWithTimeout("{ foo { x } }")
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
                                    "Foo",
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
            }.runFeatureTest(
                engineConfig = EngineConfiguration.featureTestDefault.copy(
                    additionalInstrumentation = instrumentation,
                )
            ) {
                runQueryWithTimeout("{ foo { x } }")
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
    }

    @Nested
    inner class CheckerTests {
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
                                    "Foo",
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
            }.runFeatureTest {
                runQueryWithTimeout("{foo { x } }")
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
                                    "Foo",
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
            }.runFeatureTest {
                runQueryWithTimeout("{ foo { __typename } }").assertMatches {
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
                                    "Foo",
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
            }.runFeatureTest {
                runQueryWithTimeout("{ foo { x } }")
                    .assertJson("{data: {foo: {x: 6}}}")
            }

            assertEquals(1, fooCheckerCalls.get())
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
                                    "Foo",
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
            }.runFeatureTest {
                runQueryWithTimeout("{ foo { y } }").assertMatches {
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
                                    "Foo",
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
            }.runFeatureTest {
                runQueryWithTimeout("{ foo { y } }")
                    .assertJson("{data: {foo: {y: 2}}}")
            }

            assertEquals(2, fooCalls.get())
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
                                        "Bar",
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
            }.runFeatureTest {
                runQueryWithTimeout("{ bars { x } }")
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
                                    "Foo",
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
            }.runFeatureTest {
                runQueryWithTimeout("{ foo { __typename } }")
                    .assertJson("{data: {foo: {__typename: \"Foo\"}}}")
            }

            assertEquals(1, checkedY.get())
            assertEquals(1, fooCheckerCalls.get())
            assertEquals(2, fooResolverCalls.get())
        }

        @Test
        fun `type checker reads conditional field from selective source`() {
            MockTenantModuleBootstrapper(
                """
                    extend type Query { bar:Bar, foo:Foo }
                    type Foo { bar:Bar }
                    type Bar { y:Int }
                """.trimIndent()
            ) {
                field("Query" to "bar") {
                    resolver {
                        objectSelections("foo { bar @include(if: \$var) { y } }") {
                            variables(
                                "var",
                                rss = createRSS("Query", "foo { __typename }"),
                            ) { _, _ ->
                                mapOf("var" to true)
                            }
                        }
                        fn { _, obj, _, _, _ ->
                            obj.fetchAs<EngineObjectData>("foo")
                                .fetchAs<EngineObjectData>("bar")
                        }
                    }
                }

                field("Query" to "foo") {
                    resolverExecutor {
                        MockFieldUnbatchedResolverExecutor(
                            isSelective = true,
                            resolverId = resolverId,
                            unbatchedResolveFn = { _, _, _, sels, _ ->
                                createEngineObjectData(
                                    "Foo",
                                    if (sels!!.containsField("Foo", "bar")) {
                                        mapOf(
                                            "bar" to createEngineObjectData("Bar", mapOf("y" to 2))
                                        )
                                    } else {
                                        emptyMap()
                                    }
                                )
                            }
                        )
                    }
                }

                type("Bar") {
                    checker {
                        objectSelections("fields", "checked:y")
                        fn { _, objects ->
                            objects.getValue("fields").fetchAs<Int>("checked")
                        }
                    }
                }
            }.runFeatureTest {
                runQueryWithTimeout("{ bar { __typename } }")
                    .assertJson("{data: {bar: {__typename: \"Bar\"}}}")
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
                                createEngineObjectData("Foo")
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
            }.runEngineFeatureTest {
                runQueryWithTimeout("{ foo { __typename } }")
                    .assertJson("{data: {foo: {__typename: \"Foo\"}}}")
            }
        }

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
                                    "Foo",
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
            }.runFeatureTest {
                runQueryWithTimeout("{ foo { x } }")
                    .assertJson("{data: {foo: {x: 6}}}")
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
                                    "Foo",
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
            }.runFeatureTest {
                runQueryWithTimeout("{ foo { x } }")
                    .assertJson("{data: {foo: {x: 6}}}")
            }
        }

        @Test
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
            }.runFeatureTest {
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
                                    "Foo",
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
            }.runFeatureTest {
                runQueryWithTimeout("{ foo { x } }")
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
                                createEngineObjectData("Foo", data)
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
            }.runFeatureTest {
                runQueryWithTimeout("{ foo { y x } }")
                    .assertJson("{data: {foo: {y: null, x: 6}}}")
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
                                    "Foo",
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
            }.runFeatureTest {
                runQueryWithTimeout("{ foo { x } }")
                    .assertJson("{data: {foo: {x: 6}}}")
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
                                    "Foo",
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
            }.runFeatureTest {
                runQueryWithTimeout("{ foo { y(z:1) x } }")
                    .assertJson("{data: {foo: {y:1, x:6}}}")
            }
        }

        @Test
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
            }.runFeatureTest {
                runQueryWithTimeout("{ foo { x } }")
                    .assertJson("{data: {foo: {x: 3}}}")
            }
        }
    }

    private val matOnlyFlags =
        MockFlagManager.create(FlagManager.Flags.ENABLE_MAT_RESOLUTION)

    private fun MockTenantModuleBootstrapper.runFeatureTest(
        engineConfig: EngineConfiguration = EngineConfiguration.featureTestDefault,
        block: FeatureTest.() -> Unit,
    ) {
        runEngineFeatureTest(
            engineConfig = engineConfig.copy(flagManager = matOnlyFlags),
            block = block,
        )
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
