package viaduct.engine.runtime2.execution

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.fetchAs
import viaduct.engine.runtime.execution.query
import viaduct.engine.runtime2.execution.testing.runQPlanFeatureTest

@OptIn(ExperimentalCoroutinesApi::class)
class MutationExecutionTest {
    @Test
    fun `a nonnull mutation failure preserves later mutation effects`() {
        for (nullResult in listOf(false, true)) {
            var calls = 0
            EngineTestModule("extend type Mutation { update: Int! @resolver }") {
                field("Mutation" to "update") {
                    resolver {
                        fn { _, _, _, _, _ ->
                            if (++calls == 1) {
                                if (nullResult) null else error("mutation failed")
                            } else {
                                yield()
                                calls
                            }
                        }
                    }
                }
            }.runQPlanFeatureTest {
                val result = runQuery("mutation { first: update second: update }")
                assertNull(result.getData<Any?>())
                assertEquals(1, result.errors.size)
                assertEquals(listOf("first"), result.errors.first().path)
            }
            assertEquals(2, calls, "nullResult=$nullResult")
        }
    }

    @Test
    fun `nonnull failures preserve namespace and later mutation effects`() {
        var calls = 0
        EngineTestModule(
            """
                extend type Mutation { group: MutationGroup, update: Int @resolver }
                type MutationGroup @namespaceType { update: Int! @resolver }
            """.trimIndent(),
        ) {
            field("MutationGroup" to "update") {
                resolver {
                    fn { _, _, _, _, _ ->
                        if (++calls == 1) error("mutation failed")
                        yield()
                        calls
                    }
                }
            }
            field("Mutation" to "update") {
                resolver { fn { _, _, _, _, _ -> ++calls } }
            }
        }.runQPlanFeatureTest {
            val result = runQuery("mutation { group { first: update second: update } last: update }")
            assertEquals(mapOf("group" to null, "last" to 3), result.getData())
            assertEquals(1, result.errors.size)
            assertEquals(listOf("group", "first"), result.errors.single().path)
        }
        assertEquals(3, calls)
    }

    @Test
    fun `nonnull payload failures preserve later effects regardless of propagation`() {
        for (payloadType in listOf("Payload", "Payload!", "[Payload]", "[Payload!]", "[Payload!]!")) {
            var calls = 0
            EngineTestModule(
                """
                extend type Mutation { update: $payloadType @resolver }
                type Payload { value: Int! @resolver }
                """.trimIndent(),
            ) {
                field("Mutation" to "update") {
                    resolver {
                        fn { _, _, _, _, _ ->
                            ++calls
                            if (payloadType.startsWith("[")) listOf(mapOf<String, Any?>()) else mapOf<String, Any?>()
                        }
                    }
                }
                field("Payload" to "value") {
                    resolver { fn { _, _, _, _, _ -> error("payload failed") } }
                }
            }.runQPlanFeatureTest {
                val result = runQuery("mutation { first: update { value } second: update { value } }")
                if (payloadType.endsWith("!")) assertNull(result.getData<Any?>())
                val firstPath: List<Any> = if (payloadType.startsWith("[")) listOf("first", 0, "value") else listOf("first", "value")
                assertEquals(firstPath, result.errors.first().path)
            }
            assertEquals(2, calls, payloadType)
        }
    }

    @Test
    fun `mutation aliases and nested namespaces complete in execution order`() {
        var count = 0
        val events = mutableListOf<String>()
        EngineTestModule(
            """
            extend type Mutation { group: MutationGroup, update: Int @resolver }
            type MutationGroup @namespaceType { update: Int @resolver, nested: NestedMutations }
            type NestedMutations @namespaceType { update: Int @resolver }
            """.trimIndent(),
        ) {
            for (type in listOf("Mutation", "MutationGroup", "NestedMutations")) {
                field(type to "update") {
                    resolver {
                        fn { _, _, _, _, _ ->
                            events += "start:$type"
                            yield()
                            events += "end:$type"
                            ++count
                        }
                    }
                }
            }
        }.runQPlanFeatureTest {
            runQuery("mutation { group { a: update nested { update } b: update } first: update second: update first: update }")
                .assertJson("""{"data":{"group":{"a":1,"nested":{"update":2},"b":3},"first":4,"second":5}}""")
        }
        assertEquals(listOf("MutationGroup", "NestedMutations", "MutationGroup", "Mutation", "Mutation").flatMap { listOf("start:$it", "end:$it") }, events)
    }

    @Test
    fun `mutation resolvers can execute ctx query against current state`() {
        var count = 0
        EngineTestModule("extend type Query { current: Int @resolver } extend type Mutation { update: Int @resolver }") {
            field("Query" to "current") {
                resolver { fn { _, _, _, _, _ -> count } }
            }
            field("Mutation" to "update") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        ++count
                        val selections = ctx.engineSelectionSetFactory.engineSelectionSet("Query", "current", emptyMap())
                        ctx.query(selectionSet = selections).fetchAs<Int>("current")
                    }
                }
            }
        }.runQPlanFeatureTest {
            runQuery("mutation { first: update second: update }")
                .assertJson("""{"data":{"first":1,"second":2}}""")
        }
    }

    @Test
    fun `a failing or null mutation does not prevent later mutations`() {
        var count = 0
        EngineTestModule("extend type Mutation { update: Int @resolver }") {
            field("Mutation" to "update") {
                resolver {
                    fn { _, _, _, _, _ ->
                        when (++count) {
                            1 -> error("mutation failed")
                            2 -> null
                            else -> count
                        }
                    }
                }
            }
        }.runQPlanFeatureTest {
            val result = runQuery("mutation { failed: update empty: update success: update }")
            assertEquals(mapOf("failed" to null, "empty" to null, "success" to 3), result.getData())
            assertEquals(1, result.errors.size)
            assertEquals(listOf("failed"), result.errors.single().path)
        }
        assertEquals(3, count)
    }
}
