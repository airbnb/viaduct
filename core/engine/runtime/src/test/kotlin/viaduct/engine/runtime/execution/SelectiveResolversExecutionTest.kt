@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime.execution

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import viaduct.arbitrary.common.CheckedArb
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.common.DeepArbSuite
import viaduct.arbitrary.common.withCheck
import viaduct.arbitrary.graphql.FieldCheckerWeight
import viaduct.arbitrary.graphql.FieldResolverExceptionWeight
import viaduct.arbitrary.graphql.SelectiveResolverWeight
import viaduct.arbitrary.graphql.TypeCheckerWeight
import viaduct.arbitrary.graphql.UndeclaredFieldResolverWeight
import viaduct.arbitrary.graphql.VariableWeight
import viaduct.arbitrary.graphql.VariablesResolverExceptionWeight
import viaduct.arbitrary.graphql.asViaductSchema
import viaduct.arbitrary.graphql.dump
import viaduct.arbitrary.graphql.viaduct
import viaduct.arbitrary.graphql.viaductExecutionInput
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.FeatureTest
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.createRSS
import viaduct.engine.api.mocks.fetchAs
import viaduct.engine.api.mocks.getAs
import viaduct.engine.api.mocks.runFeatureTest
import viaduct.service.api.ExecutionInput
import viaduct.service.api.ExecutionResult
import viaduct.service.api.Viaduct

class SelectiveResolversExecutionTest {
    @Nested
    inner class ArbSelectiveFieldResolvers : DeepArbSuite<Pair<Viaduct, ExecutionInput>>(
        iterations = 100,
        minViolationIterations = 10_000,
    ) {
        override val comparator = ViaductAndInputComparator

        override val checkedArb: CheckedArb<Pair<Viaduct, ExecutionInput>> = arbitrary {
            val schema = """
                type Foo { x:Int, y:Int, z:Int }
                extend type Query { a:Int, b:Int, foo:Foo }
            """.asViaductSchema

            val cfg = Config.default +
                (UndeclaredFieldResolverWeight to .25) +
                (VariableWeight to .25) +
                (SelectiveResolverWeight to .5) +
                (VariablesResolverExceptionWeight to 0.0) +
                (FieldCheckerWeight to 0.0) +
                (TypeCheckerWeight to 0.0) +
                (FieldResolverExceptionWeight to 0.0)

            val v = Arb.viaduct(schema, cfg).bind()
            val inp = Arb.viaductExecutionInput(schema, cfg).bind()

            v to inp
        }.withCheck { (v, inp) ->
            assertTrue(v.runQueryWithTimeout(inp).errors.isEmpty()) {
                (v to inp).dump()
            }
        }
    }

    @Test
    fun `selective field resolver returns when another selective shape is skipped and selected fields resolve`() {
        // This creates two planned executions of Foo.x:
        // 1. a direct selection through the outer query's `foo { x }`
        // 2. a selection through Query.b's object RSS `foo { x y }`, under the skipped `b` branch
        //
        // The `b` branch is skipped by a directive, so the second Foo.x plan is created but its
        // branch never runs. Foo.x's query RSS then asks for `foo { y }`. If createOERSelections
        // looks up that RSS through the global QueryPlanIndex, it can pick the child plan from
        // the never-executed Foo.x instead of the current root Foo.x. The Foo.x resolver then
        // waits forever when it fetches `foo`.
        EngineTestModule(
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
                        unbatchedResolveFn = { _, _, _, selections, _ ->
                            val values = selections
                                ?.selections()
                                ?.associate { sel -> sel.selectionName to sel.selectionName.first().code }
                                .orEmpty()

                            createEngineObjectData(
                                schema.schema.getObjectType("Foo")!!,
                                values
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
                        query.fetchAs<EngineObjectData>("foo").fetchAs<Int>("y") + 1
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
            ).assertJson("{data: {foo: {x: 122}}}")
        }
    }

    @Test
    fun `selective field resolver returns when repeated root field has object selections and selected fields resolve`() {
        // This creates two planned executions of Query.a:
        // 1. a direct selection from the outer query
        // 2. a selection through Foo.z's RSS on Query, under the skipped `foo { z }` branch
        //
        // The `foo { z }` branch is skipped by a directive, so the second Query.a plan is created
        // but its branch never runs. Query.a's object RSS then asks for `foo { x }`. If
        // createOERSelections looks up that RSS through the global QueryPlanIndex, it can pick
        // the child plan from the never-executed Query.a instead of the current root Query.a. The
        // Query.a resolver then waits forever when it fetches `foo`.
        EngineTestModule(
            """
                extend type Query { a: Int, foo: Foo }
                type Foo { x: Int, z: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, selections, _ ->
                            val values = selections
                                ?.selections()
                                ?.associate { sel -> sel.selectionName to sel.selectionName.first().code }
                                .orEmpty()

                            createEngineObjectData(
                                schema.schema.getObjectType("Foo")!!,
                                values
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
                            obj.fetchAs<EngineObjectData>("foo").fetchAs<Int>("x")
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
                            query.fetchAs<Int>("a") + 1
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
            ).assertJson("{data: {a: 120}}")
        }
    }

    @Test
    fun `selective field resolver returns when query root field is required from nested object selections`() {
        // Query.b's object RSS fetches `foo.y` before `foo.x`. Resolving `foo.y` first exercises
        // a query RSS where `Query.foo` contains a statically skipped named fragment whose
        // definition selects `Foo.z`. The named fragment is important: the same shape written as
        // a skipped inline fragment does not hang.
        //
        // Query.b then fetches `foo.x`. Foo.x's query RSS asks for `foo { z }`, and Foo.z's
        // object RSS asks for `y`, so this second path needs an active Query.foo plan for
        // `foo { z }` and then a Foo.z plan that reads `y` from that object.
        //
        // With createOERSelections limited to direct child-plan lookup, this combination can
        // produce empty or mismatched OER selections for the nested RSS. The selective Query.foo
        // fetch then waits for a key that no launched child plan will populate. The minimized
        // shape is sensitive: `foo { x y }` passes, replacing the skipped fragment's `z` with
        // `__typename` passes, and removing the indirect `x -> z -> y` dependency passes.
        EngineTestModule(
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
                        unbatchedResolveFn = { _, _, _, _, _ ->
                            createEngineObjectData(
                                schema.schema.getObjectType("Foo")!!,
                                emptyMap()
                            )
                        }
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
                            foo.fetchAs<Int>("y")
                            foo.fetchAs<Int>("x")
                            123
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
                            query.fetchAs<EngineObjectData>("foo").fetchAs<Int>("z")
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
                            obj.fetchAs<Int>("y") + 1
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
                            121
                        }
                    )
                }
            }
        }.runFeatureTest {
            runQueryWithTimeout("{ b }")
                .assertJson("{data: {b: 123}}")
        }
    }

    @Test
    fun `selective field resolver returns when variable rss object field has child object rss`() {
        // Query.b has a runtime-dependent object RSS field whose variable resolver needs
        // `foo { z y }`. Query.foo is selective, so it materializes scalar Foo fields only when
        // they are selected. The requested `z` field is itself a selective resolver, and Foo.z
        // needs parent object data from its own object RSS: `y`.
        //
        // A runtime-skipped Query.a branch also plans `b` and `foo { z y }`. That unexecuted
        // branch creates another Foo.z object-RSS plan with the same RSS id as the active
        // variable-RSS path. If runtime chooses the skipped branch's plan for Foo.z's object RSS,
        // Foo.z waits for a `y` value that will never be produced.

        EngineTestModule(
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
                        unbatchedResolveFn = { _, _, _, selections, _ ->
                            val values = buildMap {
                                if (selections!!.containsField("Foo", "y")) {
                                    put("y", 4)
                                }
                            }
                            createEngineObjectData(
                                schema.schema.getObjectType("Foo")!!,
                                values
                            )
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
}

private fun Viaduct.runQueryWithTimeout(
    input: ExecutionInput,
    timeout: kotlin.time.Duration = 2.seconds
): ExecutionResult {
    return runBlocking {
        withTimeout(timeout) {
            executeAsync(input).await()
        }
    }
}

private fun FeatureTest.runQueryWithTimeout(
    query: String,
    variables: Map<String, Any?> = emptyMap(),
    timeout: kotlin.time.Duration = 2.seconds
): graphql.ExecutionResult {
    val input = viaduct.engine.api.ExecutionInput(
        operationText = query,
        variables = variables,
        requestContext = Any(),
    )

    return runBlocking {
        withTimeout(timeout) {
            DefaultCoroutineInterop.enterThreadLocalCoroutineContext(coroutineContext) {
                engine.execute(input)
            }.await()
        }
    }
}

object ViaductAndInputComparator : Comparator<Pair<Viaduct, ExecutionInput>> {
    override fun compare(
        o1: Pair<Viaduct, ExecutionInput>,
        o2: Pair<Viaduct, ExecutionInput>
    ): Int {
        val len1 = o1.first.dump().length + o1.second.operationText.length
        val len2 = o2.first.dump().length + o2.second.operationText.length
        return len1.compareTo(len2)
    }
}

fun Pair<Viaduct, ExecutionInput>.dump(): String =
    """
        |___VIADUCT___
        |${first.dump()}
        |
        |INPUT
        |$second
    """.trimMargin()
