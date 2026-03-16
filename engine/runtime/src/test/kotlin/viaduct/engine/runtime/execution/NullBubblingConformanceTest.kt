@file:OptIn(ExperimentalCoroutinesApi::class)

package viaduct.engine.runtime.execution

import graphql.schema.DataFetcher
import io.kotest.property.Arb
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.common.KotestPropertyBase
import viaduct.arbitrary.graphql.NullNonNullableWeight
import viaduct.arbitrary.graphql.ResolverExceptionWeight

class NullBubblingConformanceTest : KotestPropertyBase() {
    private val cfg = Config.default

    @Test
    fun `multi-field non-null bubbling`() {
        Conformer(
            "type Query { x:Int! }",
            resolvers = mapOf("Query" to mapOf("x" to DataFetcher { null }))
        ) {
            check("{ a:x, x }", checkNoModernErrors = false)
        }
    }

    // TODO: These tests fail because modstrat will execute fields after a non-nullable field
    //  has failed and is expected to have bubbled up to the containing object.
    //  These tests are expected to pass after combining field resolution and completion into
    //  a single pass, and should be un-disabled as part of doing that work.
    @Disabled
    @Test
    fun `serial mutation execution with null bubbling`() {
        val bFetcherCallCount = AtomicInteger()
        Conformer(
            """
                type Query { empty: Int }
                type Mutation { a:Int! b:Int! }
            """.trimIndent(),
            resolvers = mapOf(
                "Mutation" to mapOf(
                    "a" to DataFetcher { null },
                    "b" to DataFetcher { bFetcherCallCount.incrementAndGet() },
                )
            )
        ) {
            check("mutation { a b }", checkNoModernErrors = false) { _, (act) ->
                assertEquals(null, act.getData<Any?>())
                assertEquals(0, bFetcherCallCount.get())
            }
        }
    }

    @Disabled
    @Test
    fun `trivial schema -- field resolver errors`() {
        val cfg = Config.default + (ResolverExceptionWeight to .3)
        Conformer(
            """
                type Query { x: Int, y:Int!, z:[Int] }
                type Mutation { x:Int, y:Int!, z:[Int] }
                type Subscription { x:Int, y:Int!, z:[Int] }
            """.trimIndent(),
            cfg
        ) {
            Arb.viaductExecutionInput(schema, cfg).checkAll(checkNoModernErrors = false)
        }
    }

    @Test
    fun `null bubbling in lists`() {
        Conformer(
            """
                type Bar { x:Int! }
                type Foo { a:[Bar], b:[Bar!], c:[Bar]!, d:[Bar!]! }
                type Query { foo:Foo }
            """.trimIndent(),
            cfg + (NullNonNullableWeight to .5)
        ) {
            Arb.viaductExecutionInput(schema, cfg).checkAll(checkNoModernErrors = false)
        }
    }

    @Test
    fun `trivial schema -- arb non-null bubbling`() {
        Conformer("type Query { x:Int! }", cfg + (NullNonNullableWeight to .5)) {
            Arb.viaductExecutionInput(schema, cfg).checkAll(checkNoModernErrors = false)
        }
    }

    @Test
    fun `experimental_disableErrorPropagation -- non null root field`() {
        Conformer("type Query { x:Int! }", mapOf("Query" to mapOf("x" to DataFetcher { null }))) {
            check(
                "query @experimental_disableErrorPropagation { x }",
                checkNoModernErrors = false
            ) { _, act ->
                assertEquals(mapOf("x" to null), act.executionResult.getData())

                assertEquals(1, act.executionResult.errors.size)
                act.executionResult.errors.first().let { err ->
                    assertEquals(listOf("x"), err.path)
                    assertTrue(err.message.contains("has wrongly returned a null value"))
                }
            }
        }
    }

    @Test
    fun `experimental_disableErrorPropagation -- non null object field`() {
        Conformer(
            """
                type Obj { x:Int! }
                type Query { obj:Obj! }
            """.trimIndent(),
            resolvers = mapOf(
                "Query" to mapOf("obj" to DataFetchers.emptyMap),
                "Obj" to mapOf("x" to DataFetcher { null })
            )
        ) {
            check(
                """
                    query @experimental_disableErrorPropagation {
                      obj {
                        x
                      }
                    }
                """.trimIndent(),
                checkNoModernErrors = false
            ) { _, act ->
                assertEquals(mapOf("obj" to mapOf("x" to null)), act.executionResult.getData())

                assertEquals(1, act.executionResult.errors.size)
                act.executionResult.errors.first().let { err ->
                    assertEquals(listOf("obj", "x"), err.path)
                    assertTrue(err.message.contains("has wrongly returned a null value"))
                }
            }
        }
    }

    @Test
    fun `experimental_disableErrorPropagation -- non null list item`() {
        Conformer(
            "type Query { x:[Int!] }",
            mapOf("Query" to mapOf("x" to DataFetcher { listOf(0, null, 2) }))
        ) {
            check("query @experimental_disableErrorPropagation { x }", checkNoModernErrors = false) { _, act ->
                assertEquals(mapOf("x" to listOf(0, null, 2)), act.executionResult.getData())

                assertEquals(1, act.executionResult.errors.size)
                act.executionResult.errors.first().let { err ->
                    assertEquals(listOf("x", 1), err.path)
                    assertTrue(err.message.contains("has wrongly returned a null value"))
                }
            }
        }
    }

    @Test
    fun `experimental_disableErrorPropagation -- execution error`() {
        Conformer(
            "type Query { x:Int! }",
            mapOf("Query" to mapOf("x" to DataFetcher { throw RuntimeException("TEST ERROR") }))
        ) {
            check("query @experimental_disableErrorPropagation { x }", checkNoModernErrors = false) { _, act ->
                assertEquals(mapOf("x" to null), act.executionResult.getData())

                assertEquals(1, act.executionResult.errors.size)
                act.executionResult.errors.first().let { err ->
                    assertEquals(listOf("x"), err.path)
                    assertTrue(err.message.contains("TEST ERROR"))
                }
            }
        }
    }
}
