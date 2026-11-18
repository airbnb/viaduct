@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolution

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import viaduct.engine.api.CheckerResult
import viaduct.engine.runtime2.contract.CheckerApplicationRecorder
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.ErrorEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.PathComponent
import viaduct.engine.runtime2.model.ResolverTarget
import viaduct.engine.runtime2.model.VariableBinding
import viaduct.engine.runtime2.model.engineObjectDataOf
import viaduct.engine.runtime2.model.registry.ResolverFragmentTemplates
import viaduct.engine.runtime2.model.registry.TypeCheckerResolver
import viaduct.engine.runtime2.model.registry.VariableDefinition
import viaduct.engine.runtime2.model.registry.VariablesProviderFunction
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.resolution.framework.Demand
import viaduct.engine.runtime2.resolution.framework.OEROccurrence
import viaduct.engine.runtime2.resolution.framework.ResolutionObserver
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolution.framework.SharedOrchestrationTask
import viaduct.engine.runtime2.schema.operationSelectionsFrom
import viaduct.graphql.schema.ViaductSchema

open class TypeCheckerLifecycleTest : ResolutionDispatcherResource {
    protected open val precomputedConstructionDemand = false

    @Test
    fun `type checker nested query retains its creating task`() {
        val orchestrations = ConcurrentLinkedQueue<SharedOrchestrationTask<*>>()
        val fixture = TestWorld.fromDSL(
            """
            extend type Query {
              checked: Checked @resolver(script: "{:}")
              dependency: Int @resolver(script: "7")
            }
            type Checked @checker(script: "query('{ dependency }').dependency == 7 ? allow : deny") {
              value: Int @resolver(script: "1")
            }
            """.trimIndent(),
        )
        val observer = object : ResolutionObserver {
            override val onQueryOERPrepared: (SharedOrchestrationTask<*>, Int?) -> Unit = { task, _ ->
                orchestrations += task
            }
        }
        val result = SharedOperationContext.create(
            fixture.newAssumptions(usePrecomputedConstructionDemand = precomputedConstructionDemand),
            resolutionObserver = observer,
        ).resolveWithTestDispatcher(fixture.schemas.operationSelectionsFrom("{ checked { value } }"))
        val checked = assertIs<ObjectEngineResult>(result.getCell(result.keys.single()).value.get())
        assertEquals(CheckerResult.Success, checked.typeCheckerResult.get())
        assertEquals(3, orchestrations.size)
        val root = orchestrations.single { it.objectOER.occurrence.target === checked }
        val checker = assertIs<TypeCheckerTask>(orchestrations.single { it.creator is TypeCheckerTask }.creator)
        assertSame(root, checker.creator)
        assertSame(checker, checker.publication.task)
        assertSame(checker, orchestrations.single { it.creator === checker }.creator)
    }

    @Test
    fun `skipping one occurrence excludes its checker demand but preserves descendant and sibling checks`() {
        val checkerCalls = AtomicInteger()
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              synthetic: Item @resolver(script: "{'value': 1, 'children': [{'value': 2}]}")
              ordinary: Item @resolver(script: "{'value': 3}")
            }
            type Item {
              value: Int
              children: [Item]
              required: Int @resolver(script: "9")
            }
            """.trimIndent(),
            typeCheckers = { schemas ->
                val type = schemas.loweredSchema.requireType("Item") as ViaductSchema.Object
                mapOf(
                    type to TypeCheckerResolver.of(
                        type,
                        schemas.loweredSchema.requireQueryTypeDef(),
                        mapOf(
                            "input" to ResolverFragmentTemplates(
                                schemas.fragmentFrom("fragment Input on Item { required }").materializeSelections,
                                viaduct.engine.runtime2.model.materializeSelectionForestOf(),
                            ),
                        ),
                        isApplicable = { _, path ->
                            path.size != 1 || (path.single() as? ObjectEngineResult.ObjectKey)?.field?.name != "synthetic"
                        },
                    ) { inputs, _ ->
                        assertEquals(9, inputs.getValue("input").objectValue.get("required"))
                        checkerCalls.incrementAndGet()
                        CheckerResult.Success
                    },
                )
            },
        )
        val assumptions = world.newAssumptions(usePrecomputedConstructionDemand = precomputedConstructionDemand)
        val operation = SharedOperationContext.create(assumptions)

        val result = operation.resolveWithTestDispatcher(world.schemas.operationSelectionsFrom("{ synthetic { value children { value } } ordinary { value } }"))
        val syntheticKey = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", "synthetic"), emptyMap())
        val synthetic = assertIs<ObjectEngineResult>(result.getCell(syntheticKey).value.get())

        assertEquals(null, synthetic.typeCheckerResult.get())
        assertEquals(setOf("value", "children"), synthetic.keys.map { it.field.name }.toSet())
        assertEquals(2, checkerCalls.get())
    }

    @Test
    fun `inapplicable checker does not invoke its provider or demand its inputs`() {
        val world = providerWorld(isApplicable = { _, _ -> false }) { error("Inapplicable provider must not run") }
        val recorder = CheckerApplicationRecorder()
        val operation = SharedOperationContext.create(world.newAssumptions(usePrecomputedConstructionDemand = precomputedConstructionDemand), resolutionObserver = recorder)

        val root = operation.resolveWithTestDispatcher(world.schemas.operationSelectionsFrom("{ subject { checked } }"))
        val result = providerResult(root)

        assertEquals(setOf("checked"), result.keys.map { it.field.name }.toSet())
        assertEquals(null, result.typeCheckerResult.get())
        assertTrue(recorder.checkerApplications().isEmpty())
    }

    @Test
    fun `failed checker provider terminates bindings and dependent values without invoking the checker`() {
        val failure = IllegalStateException("checker provider failed")
        val world = providerWorld { throw failure }
        val recorder = CheckerApplicationRecorder()
        val operation = SharedOperationContext.create(world.newAssumptions(usePrecomputedConstructionDemand = precomputedConstructionDemand), resolutionObserver = recorder)
        val root = operation.resolveWithTestDispatcher(world.schemas.operationSelectionsFrom("{ subject { checked } }"))
        val result = providerResult(root)
        val key = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Checked", "checked"), emptyMap())
        assertEquals(1, result.getCell(key).value.get())
        assertSame(failure, assertFailsWith<IllegalStateException> { result.typeCheckerResult.get() })
        val fragments = checkNotNull(world.assumptions.resolverRegistry.typeChecker(result.type))
            .instantiateFragmentsAt(root, listOf(root.keys.single()))
        fragments.objectFragment.variableDefinitions.forEach { definition ->
            assertSame(VariableBinding.Error, operation.variableBindings.getBinding(requireNotNull(definition.variable.instanceId)))
        }
        val dependency = result.keys.single { it.field.name == "dependency" }
        assertIs<ErrorEngineResult>(result.getCell(dependency).value.get())
        assertTrue(recorder.checkerApplications().isEmpty())
    }

    @Test
    fun `cancel before checker task entry terminates checker and variable promises`(): Unit =
        runBlocking {
            val world = providerWorld { error("Canceled provider must not run") }
            val queued = QueuedDispatcher()
            val job = Job()
            val operation = OperationContext.create(
                base = SharedOperationContext.create(world.newAssumptions(usePrecomputedConstructionDemand = precomputedConstructionDemand)),
                requestScope = CoroutineScope(job + queued),
            )
            val selections = Demand.checked(world.schemas.fragmentFrom("fragment CheckedInput on Checked { checked }").materializeSelections.constructionSelections())
            val type = world.schema.requireType("Checked") as ViaductSchema.Object
            val checked = OrchestrationTask.createObjectResult(operation, type, selections)
            val root = ObjectEngineResult.of(world.schema.requireQueryTypeDef(), mutable = true)
            val key = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", "subject"), emptyMap())
            root.setCellValue(key, checked)
            root.freeze()
            val task = OrchestrationTask.create(
                operation,
                OEROccurrence(root, listOf(key), checked),
                engineObjectDataOf(type),
                selections,
            )
            operation.dispatcher.dispatchOrchestration(task)
            job.cancel()
            queued.drain()
            withTimeout(2_000) { job.join() }
            assertCanceled(world, operation, root)
        }

    @Test
    fun `nonterminating checker provider is bounded and cancellation closes every binding`(): Unit =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val world = providerWorld {
                entered.complete(Unit)
                awaitCancellation()
            }
            val operation = SharedOperationContext.create(world.newAssumptions(usePrecomputedConstructionDemand = precomputedConstructionDemand))
            val job = Job()
            val result = operation.startResolve(world.schemas.operationSelectionsFrom("{ subject { checked } }"), CoroutineScope(job + resolverDispatcher))
            withTimeout(2_000) { entered.await() }
            assertFailsWith<TimeoutCancellationException> { withTimeout(50) { job.join() } }
            withTimeout(2_000) { job.cancelAndJoin() }
            assertCanceled(world, operation, result)
        }

    private fun providerWorld(
        isApplicable: (ObjectEngineResult, List<PathComponent>) -> Boolean = { _, _ -> true },
        provider: VariablesProviderFunction,
    ): TestWorld =
        TestWorld.fromDSL(
            """
        extend type Query { subject: Checked @resolver(script: "{:}") }
        type Checked {
          checked: Int! @resolver(script: "1")
          dependency(value: Int): Int @resolver(script: "value")
        }
            """.trimIndent(),
            typeCheckers = { schema ->
                val field = schema.loweredSchema.requireType("Checked") as ViaductSchema.Object
                mapOf(
                    field to TypeCheckerResolver.of(
                        field,
                        schema.loweredSchema.requireQueryTypeDef(),
                        mapOf(
                            "input" to ResolverFragmentTemplates(
                                objectFragmentTemplate = schema.fragmentFrom(
                                    "fragment Input on Checked { dependency(value: ${'$'}v) }",
                                    variableTarget = ResolverTarget.TypeCheckerTarget(field)
                                ).materializeSelections,
                                queryFragmentTemplate = viaduct.engine.runtime2.model.materializeSelectionForestOf(),
                                variables = mapOf(Arguments.Variable.of(ResolverTarget.TypeCheckerTarget(field), "v") to VariableDefinition.FromProvider),
                                variablesProvider = provider,
                            )
                        ),
                        isApplicable = isApplicable,
                    ) { _, _ -> CheckerResult.Success }
                )
            },
        )

    private fun providerResult(root: ObjectEngineResult): ObjectEngineResult = assertIs<ObjectEngineResult>(root.getCell(root.keys.single()).value.get())

    private fun assertCanceled(
        world: TestWorld,
        operation: SharedOperationContext<*>,
        root: ObjectEngineResult,
        result: ObjectEngineResult = providerResult(root),
    ) {
        assertFailsWith<CancellationException> { result.typeCheckerResult.get() }
        val fragments = checkNotNull(world.assumptions.resolverRegistry.typeChecker(result.type)).instantiateFragmentsAt(root, listOf(root.keys.single()))
        fragments.objectFragment.variableDefinitions.forEach { definition ->
            val id = requireNotNull(definition.variable.instanceId)
            assertTrue(operation.variableBindings.isBound(id))
            assertFailsWith<CancellationException> { operation.variableBindings.getBinding(id) }
        }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable
        ) {
            queue.addLast(block)
        }

        fun drain() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }
}

class PrecomputedTypeCheckerLifecycleTest : TypeCheckerLifecycleTest() {
    override val precomputedConstructionDemand = true
}
