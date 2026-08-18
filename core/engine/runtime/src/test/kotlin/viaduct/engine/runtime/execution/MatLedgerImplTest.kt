@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime.execution

import graphql.execution.ResultPath
import graphql.schema.GraphQLObjectType
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
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

                assertTrue(err.message?.contains("expected object of type `Bar`, found `Foo`") == true)
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
        fun `initial materialization records result`(): Unit =
            runBlocking {
                val requested = KeyTree.build(schema) {
                    field(foo.name, key("a"))
                }
                val source = ResolvedEngineObjectData(foo, mapOf("a" to "A"))
                val matCalls = AtomicInteger()
                val ledger = MatLedgerImpl { tree, _ ->
                    matCalls.incrementAndGet()
                    MatResult(tree, Result.success(source))
                }

                val result = ledger.materializeInitial(requested, testParameters())

                assertEquals(1, matCalls.get())
                assertSame(source, result.source.getOrThrow())
                assertEquals(requested, ledger.subtreeAt(mkMatPath(foo)))
                assertEquals("A", ledger.fetchField(mkMatPath(foo), "a"))
            }

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

        @Test
        fun `concurrent disjoint requests are materialized concurrently`(): Unit =
            runBlocking {
                val first = KeyTree.build(schema) {
                    field(foo.name, key("a"))
                }
                val second = KeyTree.build(schema) {
                    field(foo.name, key("b"))
                }
                val firstStarted = CompletableDeferred<Unit>()
                val releaseFirst = CompletableDeferred<Unit>()
                val secondStarted = CompletableDeferred<Unit>()
                val requestedShapes = mutableListOf<KeyTree>()
                val matCalls = AtomicInteger()
                val ledger = MatLedgerImpl { tree, _ ->
                    requestedShapes += tree
                    when (matCalls.incrementAndGet()) {
                        1 -> {
                            firstStarted.complete(Unit)
                            releaseFirst.await()
                        }
                        2 -> secondStarted.complete(Unit)
                    }
                    MatResult(
                        tree,
                        Result.success(
                            ResolvedEngineObjectData(
                                foo,
                                mapOf(
                                    "a" to "A",
                                    "b" to "B",
                                ),
                            )
                        ),
                    )
                }
                val parameters = testParameters()

                val firstRequest = async(start = CoroutineStart.UNDISPATCHED) {
                    ledger.ensureCoverage(first, parameters)
                }
                firstStarted.await()
                val secondRequest = async(start = CoroutineStart.UNDISPATCHED) {
                    ledger.ensureCoverage(second, parameters)
                }

                assertTrue(secondStarted.isCompleted)
                releaseFirst.complete(Unit)
                awaitAll(firstRequest, secondRequest)

                assertEquals(listOf(first, second), requestedShapes)
                assertEquals(2, matCalls.get())
                assertEquals("A", ledger.fetchField(mkMatPath(foo), "a"))
                assertEquals("B", ledger.fetchField(mkMatPath(foo), "b"))
            }

        @Test
        fun `mixed request materializes coverage absent from all pending requests`(): Unit =
            runBlocking {
                val first = KeyTree.build(schema) {
                    field(foo.name, key("a"))
                }
                val second = KeyTree.build(schema) {
                    field(foo.name, key("b"))
                }
                val third = KeyTree.build(schema) {
                    field(foo.name, key("c"))
                }
                val firstStarted = CompletableDeferred<Unit>()
                val releaseFirst = CompletableDeferred<Unit>()
                val secondStarted = CompletableDeferred<Unit>()
                val releaseSecond = CompletableDeferred<Unit>()
                val thirdStarted = CompletableDeferred<Unit>()
                val requestedShapes = mutableListOf<KeyTree>()
                val matCalls = AtomicInteger()
                val ledger = MatLedgerImpl { tree, _ ->
                    requestedShapes += tree
                    when (matCalls.incrementAndGet()) {
                        1 -> {
                            firstStarted.complete(Unit)
                            releaseFirst.await()
                        }
                        2 -> {
                            secondStarted.complete(Unit)
                            releaseSecond.await()
                        }
                        3 -> thirdStarted.complete(Unit)
                    }
                    MatResult(
                        tree,
                        Result.success(
                            ResolvedEngineObjectData(
                                foo,
                                mapOf(
                                    "a" to "A",
                                    "b" to "B",
                                    "c" to "C",
                                ),
                            )
                        ),
                    )
                }
                val parameters = testParameters()

                val firstRequest = async(start = CoroutineStart.UNDISPATCHED) {
                    ledger.ensureCoverage(first, parameters)
                }
                firstStarted.await()
                val secondRequest = async(start = CoroutineStart.UNDISPATCHED) {
                    ledger.ensureCoverage(second, parameters)
                }
                secondStarted.await()
                val mixedRequest = async(start = CoroutineStart.UNDISPATCHED) {
                    ledger.ensureCoverage(first + second + third, parameters)
                }

                assertTrue(thirdStarted.isCompleted)
                releaseFirst.complete(Unit)
                releaseSecond.complete(Unit)
                awaitAll(firstRequest, secondRequest, mixedRequest)

                assertEquals(listOf(first, second, third), requestedShapes)
                assertEquals(3, matCalls.get())
            }

        @Test
        fun `direct Mat exceptions wake waiters and permit retry`(): Unit =
            runBlocking {
                val requested = KeyTree.build(schema) {
                    field(foo.name, key("a"))
                }
                val failure = IllegalStateException("mat exploded")
                val firstStarted = CompletableDeferred<Unit>()
                val releaseFirst = CompletableDeferred<Unit>()
                val matCalls = AtomicInteger()
                val ledger = MatLedgerImpl { tree, _ ->
                    if (matCalls.incrementAndGet() == 1) {
                        firstStarted.complete(Unit)
                        releaseFirst.await()
                        throw failure
                    }
                    MatResult(
                        tree,
                        Result.success(ResolvedEngineObjectData(foo, mapOf("a" to "A"))),
                    )
                }
                val parameters = testParameters()

                val firstRequest = async(start = CoroutineStart.UNDISPATCHED) {
                    assertThrows<IllegalStateException> {
                        ledger.ensureCoverage(requested, parameters)
                    }
                }
                firstStarted.await()
                val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                    assertThrows<IllegalStateException> {
                        ledger.ensureCoverage(requested, parameters)
                    }
                }
                releaseFirst.complete(Unit)

                assertSame(failure, firstRequest.await())
                val waiterFailure = waiter.await()
                assertEquals(failure::class, waiterFailure::class)
                assertEquals(failure.message, waiterFailure.message)

                ledger.ensureCoverage(requested, parameters)

                assertEquals(2, matCalls.get())
                assertEquals("A", ledger.fetchField(mkMatPath(foo), "a"))
            }

        @Test
        fun `cancellation during Mat wakes waiters and permits retry`(): Unit =
            runBlocking {
                val requested = KeyTree.build(schema) {
                    field(foo.name, key("a"))
                }
                val firstStarted = CompletableDeferred<Unit>()
                val matCalls = AtomicInteger()
                val ledger = MatLedgerImpl { tree, _ ->
                    if (matCalls.incrementAndGet() == 1) {
                        firstStarted.complete(Unit)
                        awaitCancellation()
                    }
                    MatResult(
                        tree,
                        Result.success(ResolvedEngineObjectData(foo, mapOf("a" to "A"))),
                    )
                }
                val parameters = testParameters()

                val firstRequest = async(start = CoroutineStart.UNDISPATCHED) {
                    ledger.ensureCoverage(requested, parameters)
                }
                firstStarted.await()
                val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                    ledger.ensureCoverage(requested, parameters)
                }
                firstRequest.cancel(CancellationException("owner cancelled"))

                val firstFailure = assertThrows<CancellationException> {
                    firstRequest.await()
                }
                val waiterFailure = assertThrows<CancellationException> {
                    waiter.await()
                }
                assertEquals("owner cancelled", firstFailure.message)
                assertEquals("owner cancelled", waiterFailure.message)

                ledger.ensureCoverage(requested, parameters)

                assertEquals(2, matCalls.get())
                assertEquals("A", ledger.fetchField(mkMatPath(foo), "a"))
            }

        @Test
        fun `cancellation after Mat result still records coverage`(): Unit =
            runBlocking {
                val requested = KeyTree.build(schema) {
                    field(foo.name, key("a"))
                }
                val matCalls = AtomicInteger()
                val ledger = MatLedgerImpl { tree, _ ->
                    matCalls.incrementAndGet()
                    currentCoroutineContext().cancel(CancellationException("owner cancelled"))
                    MatResult(
                        tree,
                        Result.success(ResolvedEngineObjectData(foo, mapOf("a" to "A"))),
                    )
                }
                val parameters = testParameters()

                val request = async(start = CoroutineStart.UNDISPATCHED) {
                    ledger.ensureCoverage(requested, parameters)
                }

                val failure = assertThrows<CancellationException> {
                    request.await()
                }
                assertEquals("owner cancelled", failure.message)

                ledger.ensureCoverage(requested, parameters)

                assertEquals(1, matCalls.get())
                assertEquals("A", ledger.fetchField(mkMatPath(foo), "a"))
            }

        @Test
        fun `concurrent initial materializations invoke Mat once`(): Unit =
            runBlocking {
                val requested = KeyTree.build(schema) {
                    field(foo.name, key("a"))
                }
                val firstStarted = CompletableDeferred<Unit>()
                val releaseFirst = CompletableDeferred<Unit>()
                val matCalls = AtomicInteger()
                val ledger = MatLedgerImpl { tree, _ ->
                    matCalls.incrementAndGet()
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                    MatResult(
                        tree,
                        Result.success(ResolvedEngineObjectData(foo, mapOf("a" to "A"))),
                    )
                }
                val parameters = testParameters()

                val firstRequest = async(start = CoroutineStart.UNDISPATCHED) {
                    ledger.materializeInitial(requested, parameters)
                }
                firstStarted.await()

                val thrown = assertThrows<IllegalStateException> {
                    withTimeout(1.seconds) {
                        ledger.materializeInitial(requested, parameters)
                    }
                }
                assertEquals(IllegalStateException::class, thrown::class)

                releaseFirst.complete(Unit)
                firstRequest.await()

                assertEquals(1, matCalls.get())
            }

        @Test
        fun `empty coverage completes during initial materialization`(): Unit =
            runBlocking {
                val requested = KeyTree.build(schema) {
                    field(foo.name, key("a"))
                }
                val matCalls = AtomicInteger()
                lateinit var ledger: MatLedgerImpl
                ledger = MatLedgerImpl { tree, selectionHandle ->
                    matCalls.incrementAndGet()
                    ledger.ensureCoverage(KeyTree.empty, selectionHandle)
                    MatResult(
                        tree,
                        Result.success(ResolvedEngineObjectData(foo, mapOf("a" to "A"))),
                    )
                }

                withTimeout(2.seconds) {
                    ledger.materializeInitial(requested, testParameters())
                }

                assertEquals(1, matCalls.get())
                assertEquals("A", ledger.fetchField(mkMatPath(foo), "a"))
            }

        @Test
        fun `Mat can materialize disjoint missing coverage`(): Unit =
            runBlocking {
                val parameters = mkExecutionParameters(
                    schemaSDL = "extend type Query { a: String b: String }",
                    coordinate = "Query" to "a",
                    query = "{ a }",
                )
                val rootType = parameters.currentObjectEngineResult.type
                val initial = KeyTree.build(parameters) {
                    field(rootType.name, key("a"))
                }
                val nested = KeyTree.build(parameters) {
                    field(rootType.name, key("b"))
                }
                val nestedParameters = parameters.copy(
                    executionOrigin = ExecutionOrigin.ChildQueryPlan(
                        parameters,
                        ChildQueryPlanTarget.CurrentQueryResult,
                    )
                )
                val matCalls = AtomicInteger()
                lateinit var ledger: MatLedgerImpl
                ledger = MatLedgerImpl { tree, _ ->
                    if (matCalls.incrementAndGet() == 1) {
                        ledger.ensureCoverage(nested, nestedParameters)
                    }
                    MatResult(
                        tree,
                        Result.success(
                            ResolvedEngineObjectData(
                                rootType,
                                mapOf(
                                    "a" to "A",
                                    "b" to "B",
                                ),
                            )
                        ),
                    )
                }

                withTimeout(2.seconds) {
                    ledger.materializeInitial(initial, parameters)
                }

                assertEquals(2, matCalls.get())
                assertEquals("A", ledger.fetchField(mkMatPath(rootType), "a"))
                assertEquals("B", ledger.fetchField(mkMatPath(rootType), "b"))
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
