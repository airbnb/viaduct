package viaduct.engine.runtime.execution

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import viaduct.engine.EngineConfiguration
import viaduct.engine.api.IncrementalEntry
import viaduct.engine.api.IncrementalExecutionResult
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.featureTestDefault
import viaduct.engine.runtime.execution.ExecutionTestHelpers.runExecutionTest
import viaduct.graphql.test.assertJson
import viaduct.service.api.spi.FlagManager

interface ExecutionGroupsContract : QueryExecutionContract {
    val incrementalExecutionModes: List<Boolean>
        get() = listOf(false, true)

    @TestFactory
    fun `overlapping groups complete once including deferred list subfields`() =
        withIncrementalExecutionModes { config ->
            val calls = AtomicInteger()
            EngineTestModule("extend type Query { a: Int, b: Int, c: Int, d: Int, items: [Obj] } type Obj { x: Int, y: Int }") {
                fieldWithValue("Query" to "a", 1)
                fieldWithValue("Query" to "b", 2)
                fieldWithValue("Query" to "d", 4)
                field("Query" to "c") {
                    resolver {
                        fn { _, _, _, _, _ ->
                            calls.incrementAndGet()
                            3
                        }
                    }
                }
                field("Query" to "items") {
                    resolver {
                        fn { _, _, _, _, _ ->
                            listOf(
                                createEngineObjectData(schema.schema.getObjectType("Obj"), mapOf("x" to 1, "y" to 2)),
                                createEngineObjectData(schema.schema.getObjectType("Obj"), mapOf("x" to 3, "y" to 4)),
                            )
                        }
                    }
                }
            }.runContractTest(engineConfig = config) {
                val result = runIncremental(
                    """
                {
                    ... @defer(label: "A") { first: a shared: c }
                    d
                    ... @defer(label: "B") { b shared: c }
                    items { ... @defer { y } x }
                }
                    """.trimIndent()
                )
                if (config.flagManager.isEnabled(FlagManager.Flags.ENABLE_INCREMENTAL_EXECUTION)) {
                    val initial = result.shouldBeInstanceOf<IncrementalExecutionResult>()
                    assertEquals(mapOf("d" to 4, "items" to listOf(mapOf("x" to 1), mapOf("x" to 3))), initial.getData())
                    val updates = initial.collectUpdates()
                    initial.patches(updates).shouldContainExactlyInAnyOrder(
                        emptyList<Any>() to mapOf("first" to 1),
                        emptyList<Any>() to mapOf("shared" to 3),
                        emptyList<Any>() to mapOf("b" to 2),
                        listOf("items", 0) to mapOf("y" to 2),
                        listOf("items", 1) to mapOf("y" to 4),
                    )
                    assertTrue(updates.flatMap { it.incremental }.all { it.errors.isEmpty() })
                } else {
                    result.assertJson("""{"data":{"first":1,"shared":3,"d":4,"b":2,"items":[{"y":2,"x":1},{"y":4,"x":3}]}}""")
                    val data = result.getData<Map<String, Any?>>()
                    assertEquals(listOf("first", "shared", "d", "b", "items"), data.keys.toList())
                    @Suppress("UNCHECKED_CAST")
                    val items = data["items"] as List<Map<String, Any?>>
                    items.forEach { assertEquals(listOf("y", "x"), it.keys.toList()) }
                }
                assertEquals(1, calls.get())
            }
        }

    @TestFactory
    fun `merged object occurrences retain nested defer results`() =
        withIncrementalExecutionModes { config ->
            EngineTestModule("extend type Query { obj: Obj } type Obj { x: Int, y: Int, z: Int }") {
                field("Query" to "obj") {
                    resolver { fn { _, _, _, _, _ -> createEngineObjectData(schema.schema.getObjectType("Obj"), mapOf("x" to 1, "y" to 2, "z" to 3)) } }
                }
            }.runContractTest(engineConfig = config) {
                val result = runIncremental(
                    """
                        {
                            obj { first: x }
                            ... @defer(label: "A") { obj { y ... @defer(label: "B") { last: z } } }
                            ... @defer(label: "C") { obj { first: x } }
                        }
                    """.trimIndent()
                )

                if (config.flagManager.isEnabled(FlagManager.Flags.ENABLE_INCREMENTAL_EXECUTION)) {
                    val initial = result.shouldBeInstanceOf<IncrementalExecutionResult>()
                    assertEquals(mapOf("obj" to mapOf("first" to 1)), initial.getData())
                    val updates = initial.collectUpdates()
                    initial.patches(updates).shouldContainExactlyInAnyOrder(
                        listOf("obj") to mapOf("y" to 2),
                        listOf("obj") to mapOf("last" to 3),
                    )
                    assertEquals(listOf("A"), initial.pending.map { it.label })
                    assertEquals(listOf("B"), updates.flatMap { it.pending }.map { it.label })
                } else {
                    result.assertJson("""{"data":{"obj":{"first":1,"y":2,"last":3}}}""")
                    assertEquals(listOf("first", "y", "last"), result.getData<Map<String, Map<String, Any?>>>().getValue("obj").keys.toList())
                }
            }
        }

    @TestFactory
    fun `a deferred nullable field error belongs to its response`() =
        withIncrementalExecutionModes { config ->
            EngineTestModule("extend type Query { a: Int, b: Int }") {
                fieldWithValue("Query" to "a", 1)
                field("Query" to "b") { resolver { fn { _, _, _, _, _ -> throw IllegalStateException("failed") } } }
            }.runContractTest(engineConfig = config) {
                val result = runIncremental("{ a ... @defer { broken: b } }")
                if (config.flagManager.isEnabled(FlagManager.Flags.ENABLE_INCREMENTAL_EXECUTION)) {
                    val initial = result.shouldBeInstanceOf<IncrementalExecutionResult>()
                    assertEquals(mapOf("a" to 1), initial.getData())
                    assertTrue(initial.errors.isEmpty())
                    val patch = initial.collectUpdates().flatMap { it.incremental }.single().shouldBeInstanceOf<IncrementalEntry.Object>()
                    assertEquals(mapOf("broken" to null), patch.data)
                    assertEquals(listOf("broken"), patch.errors.single().path)
                } else {
                    assertEquals(mapOf("a" to 1, "broken" to null), result.getData<Map<String, Any?>>())
                    assertEquals(listOf("broken"), result.errors.single().path)
                }
            }
        }

    @TestFactory
    fun `a deferred non-null error respects its delivery boundary`() =
        withIncrementalExecutionModes { config ->
            EngineTestModule("extend type Query { a: Int, obj: Obj } type Obj { good: Int, broken: Int! }") {
                fieldWithValue("Query" to "a", 1)
                field("Query" to "obj") {
                    resolver { fn { _, _, _, _, _ -> createEngineObjectData(schema.schema.getObjectType("Obj"), mapOf("good" to 2)) } }
                }
                field("Obj" to "broken") { resolver { fn { _, _, _, _, _ -> throw IllegalStateException("failed") } } }
            }.runContractTest(engineConfig = config) {
                val result = runIncremental("{ a obj { good ... @defer { broken } } }")
                if (config.flagManager.isEnabled(FlagManager.Flags.ENABLE_INCREMENTAL_EXECUTION)) {
                    val initial = result.shouldBeInstanceOf<IncrementalExecutionResult>()
                    assertEquals(mapOf("a" to 1, "obj" to mapOf("good" to 2)), initial.getData())
                    assertTrue(initial.errors.isEmpty())
                    val updates = initial.collectUpdates()
                    assertTrue(updates.flatMap { it.incremental }.isEmpty())
                    assertEquals(listOf("obj", "broken"), updates.flatMap { it.completed }.single().errors.single().path)
                } else {
                    assertEquals(mapOf("a" to 1, "obj" to null), result.getData<Map<String, Any?>>())
                    assertEquals(listOf("obj", "broken"), result.errors.single().path)
                }
            }
        }

    private fun IncrementalExecutionResult.collectUpdates(): List<IncrementalExecutionResult.Update> =
        runExecutionTest { withTimeout(2_000) { updates.toList() } }.also { results ->
            assertFalse(results.last().hasNext)
            results.dropLast(1).forEach { assertTrue(it.hasNext) }
            results.flatMap { it.completed }.map { it.id }.shouldContainExactlyInAnyOrder(
                (pending + results.flatMap { it.pending }).map { it.id },
            )
        }

    private fun IncrementalExecutionResult.patches(updates: List<IncrementalExecutionResult.Update>): List<Pair<List<Any>, Map<String, Any?>>> {
        val groups = (pending + updates.flatMap { it.pending }).associateBy { it.id }
        return updates.flatMap { it.incremental }.map {
            val entry = it.shouldBeInstanceOf<IncrementalEntry.Object>()
            (groups.getValue(entry.id).path.toList() + entry.subPath?.toList().orEmpty()) to entry.data
        }
    }

    private fun withIncrementalExecutionModes(test: (EngineConfiguration) -> Unit) =
        incrementalExecutionModes.map { enabled ->
            dynamicTest("incrementalExecutionEnabled=$enabled") {
                val defaults = EngineConfiguration.featureTestDefault
                val flags = object : FlagManager {
                    override fun isEnabled(flag: FlagManager.Flag): Boolean = if (flag == FlagManager.Flags.ENABLE_INCREMENTAL_EXECUTION) enabled else defaults.flagManager.isEnabled(flag)
                }
                test(defaults.copy(flagManager = flags))
            }
        }
}
