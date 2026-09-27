package semantics.resolver26

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
import model.ErrorEngineResult
import model.ObjectEngineResult
import model.ResolverOccurrenceId
import model.VariableBinding
import model.operationSelectionsFrom
import model.requireQueryTypeDef
import model.testing.TestWorld
import semantics.resolvers.GroundedFieldPublicationOccurrence
import semantics.resolvers.resolver21.CoroutineFieldResolverTask
import semantics.resolvers.resolver21.CoroutineOperationContext
import semantics.resolvers.resolver21.CoroutineOrchestrationTask
import semantics.shared.CycleCheckState
import semantics.shared.CycleSlot
import semantics.shared.CycleTask
import semantics.shared.OEROccurrence
import semantics.shared.ResolverInvocationObservation
import semantics.shared.ResolverObserver
import semantics.shared.SharedOperationContext

class PublicationValidationTest : Resolver26DispatcherResource {
    @Test
    fun `grounded publication metadata failure stays local to the owned field`(): Unit =
        runBlocking(resolverDispatcher) {
            withTimeout(5_000) {
                coroutineScope {
                    val world = TestWorld.fromDSL(
                        """
                        extend type Query {
                          invalid: Int! @resolver(result: 1)
                          sibling: Int! @resolver(result: 7)
                        }
                        """.trimIndent(),
                    ).assumptions
                    val invoked = ConcurrentLinkedQueue<String>()
                    val operation = CoroutineOperationContext(
                        SharedOperationContext.create(world, resolverObserver = recording(invoked)),
                        this,
                        complete = { it.values },
                        cycleChecker = CycleCheckState.create(),
                    )
                    val root = ObjectEngineResult.of(world.schema.requireQueryTypeDef(), mutable = true)
                    val orchestration = CoroutineOrchestrationTask.create(
                        operation,
                        OEROccurrence(root, emptyList(), root),
                        world.resolverRegistry.createRootQueryInput(),
                        world.operationSelectionsFrom("{ invalid sibling }"),
                    )
                    val publications = CoroutineFieldResolverTask.prepareAll(orchestration)
                    val invalid = publications.single { it.selection.key.field.name == "invalid" }
                    // The destination stays owned by invalid; only its claimed containing occurrence is corrupt.
                    val unrelated = ObjectEngineResult.of(root.type, mutable = true)
                    unrelated.reserveCell(invalid.selection.key)
                    val malformed = GroundedFieldPublicationOccurrence(
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
                    assertIs<ErrorEngineResult>(invalid.publicationCell.getValue().await())
                    assertEquals(7, publications.single { it !== invalid }.publicationCell.getValue().await())
                    assertEquals(listOf("sibling"), invoked.toList())
                }
            }
        }

    @Test
    fun `symbolic validation fails before provider reads and terminates their bindings`(): Unit =
        runBlocking(resolverDispatcher) {
            withTimeout(5_000) {
                coroutineScope {
                    val world = TestWorld.fromDSL(
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
                    ).assumptions
                    val invoked = ConcurrentLinkedQueue<String>()
                    val providerReads = AtomicInteger()
                    val cycleState = CycleCheckState.create()
                    val cycleChecker = object : CycleCheckState by cycleState {
                        override fun cycleCheck(reader: CycleTask, slot: CycleSlot) {
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
                        world.operationSelectionsFrom("{ invalid sibling }"),
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
                    assertIs<ErrorEngineResult>(invalid.publicationCell.getValue().await())
                    assertEquals(7, sibling.publicationCell.getValue().await())
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

    private fun recording(invoked: ConcurrentLinkedQueue<String>) = object : ResolverObserver {
        override fun onResolverInvocation(observation: ResolverInvocationObservation) {
            invoked += observation.field.name
        }
    }

}
