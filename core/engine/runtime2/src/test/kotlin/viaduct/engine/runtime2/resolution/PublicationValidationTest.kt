@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolution

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import viaduct.engine.runtime2.model.ErrorEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.VariableBinding
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.resolution.framework.CycleCheckState
import viaduct.engine.runtime2.resolution.framework.CycleSlot
import viaduct.engine.runtime2.resolution.framework.CycleTask
import viaduct.engine.runtime2.resolution.framework.OEROccurrence
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation
import viaduct.engine.runtime2.resolution.framework.ResolverObserver
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolvers.resolver21.CoroutineFieldPublicationOccurrence
import viaduct.engine.runtime2.resolvers.resolver21.CoroutineFieldResolverTask
import viaduct.engine.runtime2.resolvers.resolver21.CoroutineOperationContext
import viaduct.engine.runtime2.resolvers.resolver21.CoroutineOrchestrationTask
import viaduct.engine.runtime2.schema.operationSelectionsFrom

class PublicationValidationTest : ResolutionDispatcherResource {
    @Test
    fun `grounded publication metadata failure stays local to the owned field`(): Unit =
        runBlocking(resolverDispatcher) {
            withTimeout(5_000) {
                coroutineScope {
                    val worldFixture = TestWorld.fromDSL(
                        """
                        extend type Query {
                          invalid: Int! @resolver(result: 1)
                          sibling: Int! @resolver(result: 7)
                        }
                        """.trimIndent(),
                    )
                    val world = worldFixture.assumptions
                    val invoked = ConcurrentLinkedQueue<String>()
                    val operation = CoroutineOperationContext(
                        SharedOperationContext.create(world, resolverObserver = recording(invoked)),
                        this,
                        complete = { demand, _ -> demand.values },
                        cycleChecker = CycleCheckState.create(),
                    )
                    val root = ObjectEngineResult.of(world.schema.requireQueryTypeDef(), mutable = true)
                    val orchestration = CoroutineOrchestrationTask.create(
                        operation,
                        OEROccurrence(root, emptyList(), root),
                        world.resolverRegistry.createRootQueryInput(),
                        worldFixture.schemas.operationSelectionsFrom("{ invalid sibling }"),
                    )
                    val publications = CoroutineFieldResolverTask.prepareAll(orchestration)
                    val invalid = publications.single { it.selection.key.field.name == "invalid" }
                    // The destination stays owned by invalid; only its claimed containing occurrence is corrupt.
                    val unrelated = ObjectEngineResult.of(root.type, mutable = true)
                    unrelated.reserveCell(invalid.selection.key)
                    val malformed = CoroutineFieldPublicationOccurrence(
                        operation = operation,
                        oerOccurrence = OEROccurrence(unrelated, emptyList(), unrelated),
                        selection = invalid.selection,
                        publicationCell = invalid.publicationCell,
                        queryOER = invalid.queryOER,
                    )
                    publications.forEach { publication ->
                        operation.dispatcher.dispatchFieldResolver(if (publication === invalid) malformed else publication)
                    }
                    root.freeze()
                    assertIs<ErrorEngineResult>(invalid.publicationCell.value.await())
                    assertEquals(7, publications.single { it !== invalid }.publicationCell.value.await())
                    assertEquals(listOf("sibling"), invoked.toList())
                }
            }
        }

    @Test
    fun `symbolic validation fails before provider reads and terminates their bindings`(): Unit =
        runBlocking(resolverDispatcher) {
            withTimeout(5_000) {
                coroutineScope {
                    val worldFixture = TestWorld.fromDSL(
                        """
                        extend type Query {
                          invalid: Int! @resolver(
                            of: "source consume(value: ${'$'}provided)"
                            pathVars: [{name: "provided", path: ["source"]}]
                            result: "sum(consume)"
                          )
                          source: Int! @resolver(result: 3)
                          consume(value: Int!): Int! @resolver(result: "value(${'$'}value)")
                          sibling: Int! @resolver(result: 7)
                        }
                        """.trimIndent(),
                    )
                    val world = worldFixture.assumptions
                    val invoked = ConcurrentLinkedQueue<String>()
                    val providerReads = AtomicInteger()
                    val cycleState = CycleCheckState.create()
                    val cycleChecker = object : CycleCheckState by cycleState {
                        override fun cycleCheck(
                            reader: CycleTask,
                            slot: CycleSlot
                        ) {
                            if ((reader.path.lastOrNull() as? ObjectEngineResult.ObjectKey)?.field?.name == "invalid") {
                                providerReads.incrementAndGet()
                            }
                            cycleState.cycleCheck(reader, slot)
                        }
                    }
                    val operation = OperationContext.create(
                        SharedOperationContext.create(world, resolverObserver = recording(invoked)),
                        this,
                        cycleChecker,
                    )
                    val root = ObjectEngineResult.of(world.schema.requireQueryTypeDef(), mutable = true)
                    val orchestration = OrchestrationTask.create(
                        operation,
                        OEROccurrence(root, emptyList(), root),
                        world.resolverRegistry.createRootQueryInput(),
                        worldFixture.schemas.operationSelectionsFrom("{ invalid sibling }"),
                    )
                    val publications = FieldResolverTask.prepareAll(orchestration)
                    val invalid = publications.single { it.sourceOccurrence.selection.key.field.name == "invalid" }
                    val sibling = publications.single { it.sourceOccurrence.selection.key.field.name == "sibling" }
                    val source = assertIs<FieldResolverOccurrence>(invalid.sourceOccurrence)
                    val malformed = SymbolicFieldPublicationOccurrence(
                        operation = operation,
                        oerOccurrence = invalid.oerOccurrence,
                        sourceOccurrence = source.copy(
                            resolverOccurrenceId = ResolverOccurrenceId.at(root, sibling.sourceOccurrence.publicationPath),
                        ),
                        publicationCell = invalid.publicationCell,
                        queryOER = invalid.queryOER,
                        variableProviderReads = invalid.variableProviderReads,
                    )
                    assertTrue(invalid.variableProviderReads.isNotEmpty())
                    publications.forEach { publication ->
                        operation.dispatcher.dispatchFieldResolver(if (publication === invalid) malformed else publication)
                    }
                    root.freeze()
                    orchestration.queryOER.occurrence.target.freeze()
                    coroutineContext.job.children.toList().joinAll()
                    assertIs<ErrorEngineResult>(invalid.publicationCell.value.await())
                    assertEquals(7, sibling.publicationCell.value.await())
                    invalid.variableProviderReads.forEach { providerRead ->
                        assertSame(
                            VariableBinding.Error,
                            operation.variableBindings.getBinding(requireNotNull(providerRead.definition.variable.instanceId)),
                        )
                    }
                    assertEquals(0, providerReads.get())
                    assertTrue("invalid" !in invoked)
                }
            }
        }

    private fun recording(invoked: ConcurrentLinkedQueue<String>) =
        object : ResolverObserver {
            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                invoked += observation.field.name
            }
        }
}
