package viaduct.engine.runtime.execution

import graphql.execution.DataFetcherResult
import graphql.schema.DataFetcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.any
import strikt.assertions.contains
import viaduct.engine.runtime.EngineResultLocalContext
import viaduct.engine.runtime.context.CompositeLocalContext
import viaduct.engine.runtime.context.getLocalContextForType
import viaduct.engine.runtime.execution.ExecutionTestHelpers.executeViaductModernGraphQL
import viaduct.engine.runtime.execution.ExecutionTestHelpers.runExecutionTest

@ExperimentalCoroutinesApi
class LocalContextFeatureTest {
    @Test
    fun `updates EngineResultLocalContext before resolving fields`() =
        runExecutionTest {
            val result = executeViaductModernGraphQL(
                """
                    type Query { foo: Foo }
                    type Foo { bar: Bar },
                    type Bar { x: Int }
                """.trimIndent(),
                mapOf(
                    "Query" to mapOf(
                        "foo" to DataFetcher {
                            val ctx = it.getLocalContextForType<EngineResultLocalContext>()
                            assertEquals("Query", ctx?.rootEngineResult?.type?.name)
                            assertEquals("Query", ctx?.parentEngineResult?.type?.name)
                        }
                    ),
                    "Foo" to mapOf(
                        "bar" to DataFetcher {
                            val ctx = it.getLocalContextForType<EngineResultLocalContext>()
                            assertEquals("Query", ctx?.rootEngineResult?.type?.name)
                            assertEquals("Foo", ctx?.parentEngineResult?.type?.name)
                        }
                    ),
                    "Bar" to mapOf(
                        "x" to DataFetcher {
                            val ctx = it.getLocalContextForType<EngineResultLocalContext>()
                            assertEquals("Query", ctx?.rootEngineResult?.type?.name)
                            assertEquals("Bar", ctx?.parentEngineResult?.type?.name)
                            42
                        }
                    )
                ),
                "{ foo { bar { x } } }"
            )
            assertEquals(
                mapOf("data" to mapOf("foo" to mapOf("bar" to mapOf("x" to 42)))),
                result.toSpecification(),
            )
        }

    @Test
    fun `field resolver modifications to local context are propagated to sub fields`() =
        runExecutionTest {
            class TestContext(val x: Int)
            val result = executeViaductModernGraphQL(
                """
                    type Query { foo1: Foo, foo2: Foo }
                    type Foo { x: Int },
                """.trimIndent(),
                mapOf(
                    "Query" to mapOf(
                        "foo1" to DataFetcher {
                            val ctx = it.getLocalContext<CompositeLocalContext>()!!.addOrUpdate(TestContext(1))
                            DataFetcherResult.newResult<Unit>()
                                .data(Unit)
                                .localContext(ctx)
                                .build()
                        },
                        "foo2" to DataFetcher {
                            val ctx = it.getLocalContext<CompositeLocalContext>()!!.addOrUpdate(TestContext(2))
                            DataFetcherResult.newResult<Unit>()
                                .data(Unit)
                                .localContext(ctx)
                                .build()
                        }
                    ),
                    "Foo" to mapOf(
                        "x" to DataFetcher {
                            it.getLocalContextForType<TestContext>()!!.x
                        }
                    ),
                ),
                "{ foo1 { x }, foo2 { x } }"
            )
            assertEquals(
                mapOf("data" to mapOf("foo1" to mapOf("x" to 1), "foo2" to mapOf("x" to 2))),
                result.toSpecification(),
            )
        }

    @Test
    fun `throw on field resolvers that return non-CompositeLocalContext localContext`() =
        runExecutionTest {
            val result = executeViaductModernGraphQL(
                "type Query { x: Int }",
                mapOf(
                    "Query" to mapOf(
                        "x" to DataFetcher {
                            DataFetcherResult.newResult<Int>()
                                .data(42)
                                .localContext(Unit)
                                .build()
                        },
                    ),
                ),
                "{ x }"
            )
            val resultSpec = result.toSpecification()
            assertEquals(mapOf("x" to null), resultSpec["data"], resultSpec.toString())
            expectThat(result) {
                get { errors } any {
                    get { message } contains "Expected CompositeLocalContext but found"
                }
            }
        }
}
