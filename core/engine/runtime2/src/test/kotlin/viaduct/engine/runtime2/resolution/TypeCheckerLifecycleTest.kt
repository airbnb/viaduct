@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolution

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
import viaduct.engine.runtime2.model.registry.ResolverFragmentTemplates
import viaduct.engine.runtime2.model.registry.TypeCheckerResolver
import viaduct.engine.runtime2.model.registry.VariableDefinition
import viaduct.engine.runtime2.model.registry.VariablesProviderFunction
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.schema.operationSelectionsFrom
import viaduct.graphql.schema.ViaductSchema

open class TypeCheckerLifecycleTest : ResolutionDispatcherResource {
    protected open val precomputedConstructionDemand = false

    @Test
    fun `skipping one occurrence excludes its checker demand but preserves descendant and sibling checks`() {
        val checkerCalls = AtomicInteger()
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              synthetic: Item @resolver(result: {value: 1, children: [{value: 2}]})
              ordinary: Item @resolver(result: {value: 3})
            }
            type Item {
              value: Int
              children: [Item]
              required: Int @resolver(result: 9)
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
        val operation = SharedOperationContext.create(world.newAssumptions(usePrecomputedConstructionDemand = precomputedConstructionDemand), checkerObserver = recorder)

        val result = operation.resolveWithTestDispatcher(world.schemas.operationSelectionsFrom("{ checked }"))

        assertEquals(setOf("checked"), result.keys.map { it.field.name }.toSet())
        assertEquals(null, result.typeCheckerResult.get())
        assertTrue(recorder.checkerApplications().isEmpty())
    }

    @Test
    fun `failed checker provider terminates bindings and dependent values without invoking the checker`() {
        val failure = IllegalStateException("checker provider failed")
        val world = providerWorld { throw failure }
        val recorder = CheckerApplicationRecorder()
        val operation = SharedOperationContext.create(world.newAssumptions(usePrecomputedConstructionDemand = precomputedConstructionDemand), checkerObserver = recorder)
        val result = operation.resolveWithTestDispatcher(world.schemas.operationSelectionsFrom("{ checked }"))
        val key = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", "checked"), emptyMap())
        assertEquals(1, result.getCell(key).value.get())
        assertSame(failure, assertFailsWith<IllegalStateException> { result.typeCheckerResult.get() })
        val fragments = checkNotNull(world.assumptions.resolverRegistry.typeChecker(result.type))
            .instantiateFragmentsAt(result, emptyList())
        fragments.objectFragment.variableDefinitions.forEach { definition ->
            assertSame(VariableBinding.Error, operation.variableBindings.getBinding(requireNotNull(definition.variable.instanceId)))
        }
        val dependency = result.keys.single { it.field.name == "dependency" }
        assertIs<ErrorEngineResult>(result.getCell(dependency).value.get())
        assertTrue(recorder.checkerApplications().isEmpty())
    }

    @Test
    fun `cancel before checker task entry terminates checker and variable promises`() =
        runBlocking {
            val world = providerWorld { error("Canceled provider must not run") }
            val queued = QueuedDispatcher()
            val job = Job()
            val operation = SharedOperationContext.create(world.newAssumptions(usePrecomputedConstructionDemand = precomputedConstructionDemand))
            val result = operation.startResolve(world.schemas.operationSelectionsFrom("{ checked }"), CoroutineScope(job + queued))
            job.cancel()
            queued.drain()
            withTimeout(2_000) { job.join() }
            assertCanceled(world, operation, result)
        }

    @Test
    fun `nonterminating checker provider is bounded and cancellation closes every binding`() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val world = providerWorld {
                entered.complete(Unit)
                awaitCancellation()
            }
            val operation = SharedOperationContext.create(world.newAssumptions(usePrecomputedConstructionDemand = precomputedConstructionDemand))
            val job = Job()
            val result = operation.startResolve(world.schemas.operationSelectionsFrom("{ checked }"), CoroutineScope(job + resolverDispatcher))
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
        extend type Query {
          checked: Int! @resolver(result: 1)
          dependency(value: Int): Int @resolver(result: "value(${'$'}value)")
        }
            """.trimIndent(),
            typeCheckers = { schema ->
                val field = schema.loweredSchema.requireQueryTypeDef()
                mapOf(
                    field to TypeCheckerResolver.of(
                        field,
                        schema.loweredSchema.requireQueryTypeDef(),
                        mapOf(
                            "input" to ResolverFragmentTemplates(
                                objectFragmentTemplate = schema.fragmentFrom(
                                    "fragment Input on Query { dependency(value: ${'$'}v) }",
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

    private fun assertCanceled(
        world: TestWorld,
        operation: SharedOperationContext<*>,
        result: ObjectEngineResult
    ) {
        assertFailsWith<CancellationException> { result.typeCheckerResult.get() }
        val fragments = checkNotNull(world.assumptions.resolverRegistry.typeChecker(result.type)).instantiateFragmentsAt(result, emptyList())
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
