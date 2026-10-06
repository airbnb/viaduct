@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolvers.resolver21

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.selectionForestOf
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.resolution.framework.CycleCheckState
import viaduct.engine.runtime2.resolution.framework.Demand
import viaduct.engine.runtime2.resolution.framework.OEROccurrence
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

class CoroutineOrchestrationTaskTest {
    @Test
    fun `empty Query demand retains and freezes an undemanded Query OER`(): Unit =
        runBlocking {
            val world =
                TestWorld
                    .fromSDL(
                        schemaSDL = "type Query { value: Int }",
                        selectiveResolvers = false,
                    ).assumptions
            val operation =
                CoroutineOperationContext(
                    base = SharedOperationContext.create(world),
                    requestScope = this,
                    complete = { demand, _ -> demand.values },
                    cycleChecker = CycleCheckState.create(),
                )
            val root = ObjectEngineResult.of(world.schema.requireQueryTypeDef(), mutable = true)
            val task =
                CoroutineOrchestrationTask.create(
                    operation = operation,
                    occurrence = OEROccurrence(root, emptyList(), root),
                    source = world.resolverRegistry.createRootQueryInput(),
                    constructionDemand = selectionForestOf(),
                )

            assertFalse(task.queryOER.isDemanded())
            operation.dispatcher.dispatchOrchestration(task)

            assertEquals(emptySet(), task.queryOER.occurrence.target.keys)
            val extra =
                ObjectEngineResult.GroundKey.of(
                    world.schema.requireObjectField("Query", "value"),
                    emptyMap(),
                )
            assertFailsWith<NoSuchElementException> {
                task.queryOER.occurrence.target.reserveCell(extra)
            }
        }

    @Test
    fun `duplicate orchestration dispatch remains an illegal state`(): Unit =
        runBlocking {
            val world =
                TestWorld
                    .fromSDL(
                        schemaSDL = "type Query { value: Int }",
                        selectiveResolvers = false,
                    ).assumptions
            val operation =
                CoroutineOperationContext(
                    base = SharedOperationContext.create(world),
                    requestScope = this,
                    complete = { demand, _ -> demand.values },
                    cycleChecker = CycleCheckState.create(),
                )
            val root =
                ObjectEngineResult.of(
                    world.schema.requireQueryTypeDef(),
                    mutable = true,
                )
            val task =
                CoroutineOrchestrationTask.create(
                    operation = operation,
                    occurrence = OEROccurrence(root, emptyList(), root),
                    source = world.resolverRegistry.createRootQueryInput(),
                    constructionDemand = Demand.checked(selectionForestOf()),
                )

            operation.dispatcher.dispatchOrchestration(task)

            val failure =
                assertFailsWith<IllegalStateException> {
                    operation.dispatcher.dispatchOrchestration(task)
                }
            assertEquals("Object orchestrated twice: []", failure.message)
        }
}
