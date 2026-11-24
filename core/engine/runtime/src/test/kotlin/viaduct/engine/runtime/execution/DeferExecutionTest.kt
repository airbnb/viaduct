@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime.execution

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import viaduct.engine.api.mocks.MockTenantModuleBootstrapper
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.runFeatureTest
import viaduct.service.api.ExecutionResult
import viaduct.service.api.IncrementalEntry

class DeferExecutionTest {
    @Nested
    inner class MutationExecutionTests {
        @Test
        fun `deferred mutation payload does not block the next mutation`() {
            assertMutationDelivery(namespaced = false)
        }

        @Test
        fun `deferred namespaced mutation payload does not block the next mutation`() {
            assertMutationDelivery(namespaced = true)
        }

        @Test
        fun `deferred mutation list items do not block the next mutation`() {
            assertMutationDelivery(namespaced = false, listPayload = true)
        }

        @Test
        fun `deferred namespaced mutation list items do not block the next mutation`() {
            assertMutationDelivery(namespaced = true, listPayload = true)
        }

        private fun assertMutationDelivery(
            namespaced: Boolean,
            listPayload: Boolean = false
        ) {
            val finishSlow = CompletableDeferred<Unit>()
            val mutations = CopyOnWriteArrayList<String>()
            val mutationType = if (namespaced) "Actions" else "Mutation"
            val payloadType = if (listPayload) "[Payload]" else "Payload"
            val schema = if (namespaced) {
                """
                    extend type Mutation { actions: Actions }
                    type Actions @namespaceType { first: $payloadType, second: $payloadType }
                    type Payload { id: ID, slow: Int }
                """.trimIndent()
            } else {
                """
                    extend type Mutation { first: $payloadType, second: $payloadType }
                    type Payload { id: ID, slow: Int }
                """.trimIndent()
            }
            val selections = "first { id ... @defer { slow } } second { id }"
            val query = if (namespaced) "mutation { actions { $selections } }" else "mutation { $selections }"
            val payload = listOf("first", "second").associateWith { name ->
                if (listPayload) listOf(mapOf("id" to name), mapOf("id" to name)) else mapOf("id" to name)
            }
            val initialData = if (namespaced) mapOf("actions" to payload) else payload

            MockTenantModuleBootstrapper(schema) {
                if (namespaced) {
                    field("Mutation" to "actions") {
                        resolver { fn { _, _, _, _, _ -> createEngineObjectData("Actions") } }
                    }
                }
                for (name in listOf("first", "second")) {
                    field(mutationType to name) {
                        resolver {
                            fn { _, _, _, _, _ ->
                                mutations += name
                                if (listPayload) {
                                    List(2) { createEngineObjectData("Payload", mapOf("id" to name)) }
                                } else {
                                    createEngineObjectData("Payload", mapOf("id" to name))
                                }
                            }
                        }
                    }
                }
                field("Payload" to "slow") {
                    resolver {
                        fn { _, _, _, _, _ ->
                            finishSlow.await()
                            42
                        }
                    }
                }
            }.runFeatureTest {
                val results = runBlocking {
                    withTimeout(5.seconds) {
                        reactiveQuery(query).onEach {
                            if (it is ExecutionResult.Initial) {
                                assertEquals(initialData, it.getData())
                                assertTrue(it.errors.isEmpty(), it.errors.toString())
                                assertTrue(it.hasNext)
                                assertEquals(listOf("first", "second"), mutations)
                                assertFalse(finishSlow.isCompleted)
                                finishSlow.complete(Unit)
                            }
                        }.toList()
                    }
                }
                assertInstanceOf(ExecutionResult.Initial::class.java, results.first())
                val updates = results.drop(1).map { assertInstanceOf(ExecutionResult.Update::class.java, it) }
                assertTrue(updates.isNotEmpty())
                assertFalse(updates.last().hasNext)
                val patches = updates.flatMap { it.incremental }
                assertEquals(if (listPayload) 2 else 1, patches.size)
                patches.forEach {
                    assertEquals(mapOf("slow" to 42), assertInstanceOf(IncrementalEntry.Object::class.java, it).data)
                    assertTrue(it.errors.isEmpty(), it.errors.toString())
                }
                updates.forEach { update ->
                    assertTrue(update.completed.all { it.errors.isEmpty() }, update.toSpecification().toString())
                }
            }
        }
    }
}
