@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime.execution

import graphql.execution.ResultPath
import graphql.schema.GraphQLObjectType
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.arbitrary.graphql.asViaductSchema
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.runtime.ObjectEngineResult
import viaduct.engine.runtime.mat.KeyTree
import viaduct.engine.runtime.mat.Mat
import viaduct.engine.runtime.mat.MatPath
import viaduct.engine.runtime.mat.MatPath.Segment
import viaduct.engine.runtime.mat.MatResult
import viaduct.engine.runtime.mat.build

class MatLedgerImplTest {
    private val schema = """
        type Foo { unused: Int }
        type Bar { unused: Int }
    """.trimIndent().asViaductSchema
    private val foo = schema.schema.getObjectType("Foo")!!
    private val bar = schema.schema.getObjectType("Bar")!!

    @Nested
    inner class Fetch {
        @Test
        fun `returns top-level field values`(): Unit =
            runBlocking {
                val ledger = MatLedgerImpl(Mat.Null)
                ledger.initialize(
                    KeyTree.build(schema) {
                        field(foo.name, key("a"))
                    },
                    ResolvedEngineObjectData(foo, mapOf("a" to "A")),
                )
                val value = ledger.fetchField(mkMatPath(foo), "a")

                assertEquals("A", value)
            }

        @Test
        fun `returns nested field values`(): Unit =
            runBlocking {
                val ledger = MatLedgerImpl(Mat.Null)
                val tree = KeyTree.build(schema) {
                    field(foo.name, key("bar")) {
                        field(bar.name, key("x"))
                    }
                }
                val path = mkMatPath(foo, mkMatSegment(bar, "bar"))
                ledger.initialize(
                    tree,
                    ResolvedEngineObjectData(
                        foo,
                        mapOf("bar" to ResolvedEngineObjectData(bar, mapOf("x" to "X"))),
                    )
                )
                val value = ledger.fetchField(path, "x")

                assertEquals("X", value)
            }

        @Test
        fun `returns initialized node id without invoking mat`(): Unit =
            runBlocking {
                val ledger = MatLedgerImpl { _, _ -> error("unused") }
                ledger.initialize(
                    KeyTree.build(schema) {
                        field(foo.name, key("id"))
                    },
                    ResolvedEngineObjectData(foo, mapOf("id" to "Foo:1")),
                )

                val value = ledger.fetchField(mkMatPath(foo), "id")

                assertEquals("Foo:1", value)
            }

        @Test
        fun `returns initialized __typename without invoking mat`(): Unit =
            runBlocking {
                val ledger = MatLedgerImpl { _, _ -> error("unused") }
                ledger.initialize(
                    KeyTree.build(schema) {
                        field(foo.name, key("__typename"))
                    },
                    ResolvedEngineObjectData(foo, mapOf("__typename" to "Foo")),
                )

                val value = ledger.fetchField(mkMatPath(foo), "__typename")

                assertEquals("Foo", value)
            }

        @Test
        fun `returns list values`(): Unit =
            runBlocking {
                val ledger = MatLedgerImpl(Mat.Null)
                val tree = KeyTree.build(schema) {
                    field(foo.name, key("bar")) {
                        field(bar.name, key("x"))
                    }
                }
                val path = mkMatPath(foo, mkMatSegment(bar, "bar", indices = listOf(0)))
                ledger.initialize(
                    tree,
                    ResolvedEngineObjectData(
                        foo,
                        mapOf(
                            "bar" to listOf(
                                ResolvedEngineObjectData(bar, mapOf("x" to "X")),
                            )
                        ),
                    )
                )
                val value = ledger.fetchField(path, "x")

                assertEquals("X", value)
            }

        @Test
        fun `uses the same list index across materializations`(): Unit =
            runBlocking {
                val schema = """
                    type Foo { bar: [Bar!]! }
                    type Bar { a: Int b: Int }
                """.trimIndent().asViaductSchema
                val foo = checkNotNull(schema.schema.getObjectType("Foo"))
                val bar = checkNotNull(schema.schema.getObjectType("Bar"))
                val path = mkMatPath(foo, mkMatSegment(bar, "bar", indices = listOf(1)))
                val initialCoverage = KeyTree.build(schema) {
                    field(foo.name, key("bar")) {
                        field(bar.name, key("a"))
                    }
                }
                val missingCoverage = KeyTree.build(schema) {
                    field(foo.name, key("bar")) {
                        field(bar.name, key("b"))
                    }
                }
                val ledger = MatLedgerImpl(
                    successfulMat(
                        ResolvedEngineObjectData(
                            foo,
                            mapOf(
                                "bar" to listOf(
                                    ResolvedEngineObjectData(bar, mapOf("b" to 3)),
                                    ResolvedEngineObjectData(bar, mapOf("b" to 4)),
                                )
                            ),
                        )
                    )
                )
                ledger.initialize(
                    initialCoverage,
                    ResolvedEngineObjectData(
                        foo,
                        mapOf(
                            "bar" to listOf(
                                ResolvedEngineObjectData(bar, mapOf("a" to 1)),
                                ResolvedEngineObjectData(bar, mapOf("a" to 2)),
                            )
                        ),
                    ),
                )
                ledger.ensureCoverage(missingCoverage, testParameters())

                assertEquals(2, ledger.fetchField(path, "a"))
                assertEquals(4, ledger.fetchField(path, "b"))
            }

        @Test
        fun `throws when list member type diverges across materializations`(): Unit =
            runBlocking {
                val schema = """
                    type Foo { bar: [Bar!]! }
                    type Bar { a: Int b: Int }
                """.trimIndent().asViaductSchema
                val foo = checkNotNull(schema.schema.getObjectType("Foo"))
                val bar = checkNotNull(schema.schema.getObjectType("Bar"))
                val initialCoverage = KeyTree.build(schema) {
                    field(foo.name, key("bar")) {
                        field(bar.name, key("a"))
                    }
                }
                val missingCoverage = KeyTree.build(schema) {
                    field(foo.name, key("bar")) {
                        field(bar.name, key("b"))
                    }
                }
                val ledger = MatLedgerImpl(
                    successfulMat(
                        ResolvedEngineObjectData(
                            foo,
                            mapOf(
                                "bar" to listOf(
                                    ResolvedEngineObjectData(foo, emptyMap()),
                                )
                            ),
                        )
                    )
                )
                ledger.initialize(
                    initialCoverage,
                    ResolvedEngineObjectData(
                        foo,
                        mapOf(
                            "bar" to listOf(
                                ResolvedEngineObjectData(bar, emptyMap())
                            )
                        ),
                    ),
                )
                ledger.ensureCoverage(missingCoverage, testParameters())
                val path = mkMatPath(foo, mkMatSegment(bar, "bar", indices = listOf(0)))

                val err = assertThrows<RuntimeException> {
                    ledger.fetchField(path, "b")
                }

                assertTrue(err.message?.contains("expected type `Bar` at `bar`, found `Foo`") == true)
            }

        @Test
        fun `throws when no Mat covers the field`(): Unit =
            runBlocking {
                val ledger = MatLedgerImpl(Mat.Null)
                ledger.initialize(
                    KeyTree.build(schema) {
                        field(foo.name, key("a"))
                    },
                    ResolvedEngineObjectData(foo, mapOf("a" to "A")),
                )

                val err = assertThrows<RuntimeException> {
                    ledger.resolveSource(mkMatPath(foo), key("b"))
                }

                assertTrue(err.message?.contains("Key(name='b'") == true) {
                    err.message
                }
            }

        @Test
        fun `returns null when a covered result source is null`(): Unit =
            runBlocking {
                val ledger = MatLedgerImpl(Mat.Null)
                ledger.initialize(
                    KeyTree.build(schema) {
                        field(foo.name, key("a"))
                    },
                    null,
                )

                val source = ledger.resolveSource(mkMatPath(foo), key("a"))

                assertEquals(null, source)
            }

        @Test
        fun `throws when a list index is out of range`(): Unit =
            runBlocking {
                val ledger = MatLedgerImpl(Mat.Null)
                val tree = KeyTree.build(schema) {
                    field(foo.name, key("bar")) {
                        field(bar.name, key("x"))
                    }
                }
                val path = mkMatPath(foo, mkMatSegment(bar, "bar", indices = listOf(0)))
                ledger.initialize(
                    tree,
                    ResolvedEngineObjectData(foo, mapOf("bar" to emptyList<EngineObjectData>()))
                )
                val err = assertThrows<RuntimeException> {
                    ledger.resolveSource(path, key("x"))
                }
                assertTrue(err.message?.contains("has 0 items") == true)
            }

        @Test
        fun `returns null when a value in traversal path is null`(): Unit =
            runBlocking {
                val ledger = MatLedgerImpl(Mat.Null)
                ledger.initialize(
                    KeyTree.build(schema) {
                        field(foo.name, key("bar")) {
                            field(bar.name, key("x"))
                        }
                    },
                    ResolvedEngineObjectData(foo, mapOf("bar" to null))
                )

                val source = ledger.resolveSource(
                    mkMatPath(
                        foo,
                        mkMatSegment(bar, "bar")
                    ),
                    key("x"),
                )

                assertEquals(null, source)
            }

        @Test
        fun `propagates Mat exceptions without wrapping`(): Unit =
            runBlocking {
                val failure = RuntimeException("mat exploded")
                val ledger = MatLedgerImpl { _, _ -> throw failure }

                val thrown = assertThrows<RuntimeException> {
                    ledger.ensureCoverage(
                        KeyTree.build(schema) {
                            field(foo.name, key("name"))
                        },
                        testParameters(),
                    )
                }

                assertSame(failure, thrown)
            }

        @Test
        fun `failed MatResults throw on covered reads`(): Unit =
            runBlocking {
                val failure = RuntimeException("mat result failed")
                val ledger = MatLedgerImpl { tree, _ ->
                    MatResult(tree, Result.failure(failure))
                }

                ledger.ensureCoverage(
                    KeyTree.build(schema) {
                        field(foo.name, key("name"))
                    },
                    testParameters(),
                )

                val thrown = assertThrows<RuntimeException> {
                    ledger.resolveSource(mkMatPath(foo), key("name"))
                }

                assertSame(failure, thrown)
            }

        @Test
        fun `failed MatResults do not poison already covered fields`(): Unit =
            runBlocking {
                val failure = RuntimeException("mat result failed")
                val ledger = MatLedgerImpl { tree, _ ->
                    MatResult(tree, Result.failure(failure))
                }
                ledger.initialize(
                    KeyTree.build(schema) {
                        field(foo.name, key("a"))
                    },
                    ResolvedEngineObjectData(foo, mapOf("a" to "A")),
                )

                ledger.ensureCoverage(
                    KeyTree.build(schema) {
                        field(foo.name, key("a"))
                        field(foo.name, key("b"))
                    },
                    testParameters(),
                )

                assertEquals("A", ledger.fetchField(mkMatPath(foo), "a"))
                val thrown = assertThrows<RuntimeException> {
                    ledger.resolveSource(mkMatPath(foo), key("b"))
                }
                assertSame(failure, thrown)
            }

        @Test
        fun `materializes aliased terminal keys with different arguments separately`(): Unit =
            runBlocking {
                val firstKey = key("value", alias = "a", arguments = mapOf("id" to 1))
                val secondKey = key("value", alias = "b", arguments = mapOf("id" to 2))
                val secondCoverage = KeyTree.build(schema) {
                    field(foo.name, secondKey)
                }
                val matCalls = AtomicInteger()
                val ledger = MatLedgerImpl { coverage, _ ->
                    matCalls.incrementAndGet()
                    MatResult(
                        coverage,
                        Result.success(ResolvedEngineObjectData(foo, mapOf("value" to "second"))),
                    )
                }
                ledger.initialize(
                    KeyTree.build(schema) {
                        field(foo.name, firstKey)
                    },
                    ResolvedEngineObjectData(foo, mapOf("value" to "first")),
                )
                ledger.ensureCoverage(secondCoverage, testParameters())

                val source = checkNotNull(ledger.resolveSource(mkMatPath(foo), secondKey))

                assertEquals(1, matCalls.get())
                assertEquals("second", source.fetch("value"))
            }

        @Test
        fun `materializes aliased path keys with different arguments separately`(): Unit =
            runBlocking {
                val firstPathKey = key("child", alias = "a", arguments = mapOf("id" to 1))
                val secondPathKey = key("child", alias = "b", arguments = mapOf("id" to 2))
                val valueKey = key("value")
                val secondCoverage = KeyTree.build(schema) {
                    field(foo.name, secondPathKey) {
                        field(bar.name, valueKey)
                    }
                }
                val matCalls = AtomicInteger()
                val ledger = MatLedgerImpl { coverage, _ ->
                    matCalls.incrementAndGet()
                    MatResult(
                        coverage,
                        Result.success(
                            ResolvedEngineObjectData(
                                foo,
                                mapOf(
                                    "child" to ResolvedEngineObjectData(
                                        bar,
                                        mapOf("value" to "second"),
                                    )
                                ),
                            )
                        ),
                    )
                }
                ledger.initialize(
                    KeyTree.build(schema) {
                        field(foo.name, firstPathKey) {
                            field(bar.name, valueKey)
                        }
                    },
                    ResolvedEngineObjectData(
                        foo,
                        mapOf(
                            "child" to ResolvedEngineObjectData(
                                bar,
                                mapOf("value" to "first"),
                            )
                        ),
                    ),
                )
                ledger.ensureCoverage(secondCoverage, testParameters())

                val source = checkNotNull(
                    ledger.resolveSource(
                        mkMatPath(foo, mkMatSegment(bar, secondPathKey)),
                        valueKey,
                    )
                )

                assertEquals(1, matCalls.get())
                assertEquals("second", source.fetch("value"))
            }
    }

    @Nested
    inner class EnsureCoverage {
        @Test
        fun `initial result covers selections without invoking Mat`(): Unit =
            runBlocking {
                val matCalls = AtomicInteger()
                val requested = KeyTree.build(schema) {
                    field(foo.name, key("a"))
                }
                val source = ResolvedEngineObjectData(foo, mapOf("a" to "A"))
                val ledger = MatLedgerImpl { tree, _ ->
                    matCalls.incrementAndGet()
                    MatResult(tree, Result.success(source))
                }
                ledger.initialize(requested, source)

                ledger.ensureCoverage(requested, testParameters())

                assertEquals(0, matCalls.get())
                assertEquals(requested, ledger.subtreeAt(mkMatPath(foo)))
                assertEquals("A", ledger.fetchField(mkMatPath(foo), "a"))
            }

        @Test
        fun `materializes fields absent from recorded coverage`(): Unit =
            runBlocking {
                val matCalls = AtomicInteger()
                val selected = KeyTree.build(schema) {
                    field(foo.name, key("a"))
                }
                val surplusKey = key(
                    "value",
                    alias = "alias",
                    arguments = mapOf("factor" to 2),
                )
                val surplus = KeyTree.build(schema) {
                    field(foo.name, surplusKey)
                }
                val source = ResolvedEngineObjectData(
                    foo,
                    mapOf(
                        "a" to "A",
                        "value" to "surplus",
                    ),
                )
                val ledger = MatLedgerImpl { tree, _ ->
                    matCalls.incrementAndGet()
                    MatResult(tree, Result.success(source))
                }
                ledger.initialize(selected, source)

                ledger.ensureCoverage(surplus, testParameters())

                assertEquals(1, matCalls.get())
                assertEquals(selected + surplus, ledger.subtreeAt(mkMatPath(foo)))
                assertEquals(
                    "surplus",
                    checkNotNull(ledger.resolveSource(mkMatPath(foo), surplusKey)).fetch("value"),
                )
            }

        @Test
        fun `initialization is at most once`(): Unit =
            runBlocking {
                val ledger = MatLedgerImpl(Mat.Null)
                val coverage = KeyTree.build(schema) {
                    field(foo.name, key("a"))
                }
                ledger.initialize(coverage, null)

                assertThrows<IllegalStateException> {
                    ledger.initialize(coverage, null)
                }
            }

        @Test
        fun `concurrent requests share one materialization`(): Unit =
            runBlocking {
                val requested = KeyTree.build(schema) {
                    field(foo.name, key("a"))
                }
                val matCalls = AtomicInteger()
                val ledger = MatLedgerImpl { tree, _ ->
                    matCalls.incrementAndGet()
                    delay(10.milliseconds)
                    MatResult(
                        tree,
                        Result.success(ResolvedEngineObjectData(foo, mapOf("a" to "A"))),
                    )
                }
                val parameters = testParameters()

                withTimeout(5.seconds) {
                    List(100) {
                        async { ledger.ensureCoverage(requested, parameters) }
                    }.awaitAll()
                }

                assertEquals(1, matCalls.get())
                assertEquals("A", ledger.fetchField(mkMatPath(foo), "a"))
            }
    }

    @Nested
    inner class SubtreeAt {
        @Test
        fun `initialized reports initial result keys as covered`(): Unit =
            runBlocking {
                val initialCoverage = KeyTree.build(schema) {
                    field(foo.name, key("a"))
                    field(foo.name, key("b"))
                }
                val ledger = MatLedgerImpl(Mat.Null)
                ledger.initialize(initialCoverage, null)

                assertEquals(initialCoverage, ledger.subtreeAt(mkMatPath(foo)))
            }

        @Test
        fun `empty`(): Unit =
            runBlocking {
                val subtree = MatLedgerImpl(Mat.Null)
                    .subtreeAt(MatPath(foo))
                assertEquals(KeyTree.empty, subtree)
            }

        @Test
        fun `simple`(): Unit =
            runBlocking {
                val ledger = MatLedgerImpl(Mat.Null)
                val coverage = KeyTree.build(schema) {
                    field(foo.name, key("a"))
                }
                ledger.initialize(coverage, null)
                val subtree = ledger.subtreeAt(mkMatPath(foo))
                assertEquals(coverage, subtree)
            }

        @Test
        fun `unions result trees`(): Unit =
            runBlocking {
                val secondCoverage = KeyTree.build(schema) {
                    field(foo.name, key("bar")) {
                        field(bar.name, key("b"))
                    }
                }
                val ledger = MatLedgerImpl(
                    successfulMat(
                        ResolvedEngineObjectData(
                            foo,
                            mapOf("bar" to ResolvedEngineObjectData(bar, mapOf("b" to "B"))),
                        )
                    )
                )
                ledger.initialize(
                    KeyTree.build(schema) {
                        field(foo.name, key("bar")) {
                            field(bar.name, key("a"))
                        }
                    },
                    ResolvedEngineObjectData(
                        foo,
                        mapOf("bar" to ResolvedEngineObjectData(bar, mapOf("a" to "A"))),
                    ),
                )
                ledger.ensureCoverage(secondCoverage, testParameters())

                val subtree = ledger.subtreeAt(mkMatPath(foo, mkMatSegment(bar, "bar")))

                assertEquals(
                    subtree,
                    KeyTree.build(schema) {
                        field(bar.name, key("a"))
                        field(bar.name, key("b"))
                    }
                )
            }

        @Test
        fun `keeps different field instances in separate subtrees`(): Unit =
            runBlocking {
                val firstPathKey = key("child", alias = "a", arguments = mapOf("id" to 1))
                val secondPathKey = key("child", alias = "b", arguments = mapOf("id" to 2))
                val secondCoverage = KeyTree.build(schema) {
                    field(foo.name, secondPathKey) {
                        field(bar.name, key("y"))
                    }
                }
                val ledger = MatLedgerImpl(
                    successfulMat(
                        ResolvedEngineObjectData(
                            foo,
                            mapOf(
                                "child" to ResolvedEngineObjectData(
                                    bar,
                                    mapOf("y" to "Y"),
                                )
                            ),
                        )
                    )
                )
                ledger.initialize(
                    KeyTree.build(schema) {
                        field(foo.name, firstPathKey) {
                            field(bar.name, key("x"))
                        }
                    },
                    ResolvedEngineObjectData(
                        foo,
                        mapOf("child" to ResolvedEngineObjectData(bar, mapOf("x" to "X"))),
                    ),
                )
                ledger.ensureCoverage(secondCoverage, testParameters())

                assertEquals(
                    KeyTree.build(schema) {
                        field(bar.name, key("x"))
                    },
                    ledger.subtreeAt(mkMatPath(foo, mkMatSegment(bar, firstPathKey))),
                )
                assertEquals(
                    KeyTree.build(schema) {
                        field(bar.name, key("y"))
                    },
                    ledger.subtreeAt(mkMatPath(foo, mkMatSegment(bar, secondPathKey))),
                )
            }
    }

    private fun testParameters(): ExecutionParameters =
        mockk(relaxed = true) {
            io.mockk.every { path } returns ResultPath.rootPath()
            io.mockk.every { field } returns null
        }

    private fun mkMatPath(
        rootType: GraphQLObjectType,
        vararg segments: Segment
    ): MatPath = MatPath(rootType, segments.toList())

    private fun mkMatSegment(
        type: GraphQLObjectType,
        fieldName: String,
        responseKey: String = fieldName,
        arguments: Map<String, Any?> = emptyMap(),
        indices: List<Int> = emptyList()
    ): Segment = mkMatSegment(type, key(fieldName, responseKey, arguments), indices)

    private fun mkMatSegment(
        type: GraphQLObjectType,
        key: ObjectEngineResult.Key,
        indices: List<Int> = emptyList(),
    ): Segment = Segment(type, key, indices)

    private fun key(
        name: String,
        alias: String? = null,
        arguments: Map<String, Any?> = emptyMap(),
    ): ObjectEngineResult.Key = ObjectEngineResult.Key(name, alias, arguments)

    private fun successfulMat(source: EngineObjectData?): Mat = Mat { coverage, _ -> MatResult(coverage, Result.success(source)) }

    private suspend fun MatLedgerImpl.fetchField(
        path: MatPath,
        fieldName: String
    ): Any? = checkNotNull(resolveSource(path, key(fieldName))).fetch(fieldName)

    private suspend fun MatLedgerImpl.initialize(
        coverage: KeyTree,
        source: EngineObjectData?
    ) {
        initialize(MatResult(coverage, Result.success(source)))
    }
}
