package semantics.resolver26

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import model.ObjectEngineResult
import model.operationSelectionsFrom
import model.requireObjectField
import model.requireQueryTypeDef
import model.requireType
import model.selectionForestOf
import model.testing.TestWorld
import semantics.shared.SharedOperationContext
import viaduct.graphql.schema.ViaductSchema
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import semantics.shared.OEROccurrence

class OrchestrationTaskTest : Resolver26DispatcherResource {
    @Test
    fun `factory closes demand without dispatching field work`(): Unit = runBlocking {
        val world = TestWorld.fromDSL(
            schemaSDL = """
                extend type Query {
                  first: Int! @resolver(result: 7)
                  second: Int! @resolver(of: "first", result: "sum(first)")
                }
            """.trimIndent(),
        ).assumptions
        val base = SharedOperationContext.create(world)
        val operation = OperationContext.create(
            base = base,
            requestScope = this,
        )
        val root = ObjectEngineResult.of(world.schema.requireQueryTypeDef(), mutable = true)
        val task = OrchestrationTask.create(
            operation,
            OEROccurrence(root, emptyList(), root),
            world.resolverRegistry.createRootQueryInput(),
            world.operationSelectionsFrom("{ second }"),
        )
        assertEquals(setOf("first", "second"), task.objectOER.closedDemand.byKey().keys.map { it.field.name }.toSet())
        assertTrue(root.keys.isEmpty())
        assertFalse(coroutineContext[kotlinx.coroutines.Job]!!.children.any())

        assertSame(operation, task.operation)
        task.operation.dispatcher.dispatchOrchestrator(task)
        val key = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", "second"), emptyMap())
        assertEquals(7, root.getCell(key).getValue().await())
        assertFailsWith<IllegalArgumentException> { operation.dispatcher.dispatchOrchestrator(task) }
    }

    @Test
    fun `object orchestration validates source and target types at construction`(): Unit =
        runBlocking(resolverDispatcher) {
            coroutineScope {
                val world =
                    TestWorld
                        .fromSDL(
                            """
                            type Query {
                              item: Item
                            }

                            type Item {
                              value: Int
                            }
                            """.trimIndent(),
                        ).assumptions
                val baseOperation = SharedOperationContext.create(world)
                val operation =
                    OperationContext.create(
                        base = baseOperation,
                        requestScope = this,
                    )
                val root =
                    ObjectEngineResult.of(
                        world.schema.requireQueryTypeDef(),
                        mutable = true,
                    )
                val target =
                    ObjectEngineResult.of(
                        world.schema.requireType("Item") as ViaductSchema.Object,
                        mutable = true,
                    )

                assertFailsWith<IllegalArgumentException> {
                    OrchestrationTask.create(
                        operation = operation,
                        occurrence =
                            OEROccurrence(
                                root = root,
                                path =
                                    listOf(
                                        ObjectEngineResult.GroundKey.of(
                                            world.schema.requireObjectField("Query", "item"),
                                            emptyMap(),
                                        ),
                                    ),
                                target = target,
                            ),
                        source = world.resolverRegistry.createRootQueryInput(),
                        initialDemand = selectionForestOf(),
                    )
                }
            }
        }
}
