@file:Suppress("ForbiddenImport")

package semantics.resolver26

import java.util.concurrent.ConcurrentLinkedQueue
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
import model.Arguments
import model.ErrorEngineResult
import model.ObjectEngineResult
import model.VariableBinding
import model.fragmentFrom
import model.operationSelectionsFrom
import model.registry.FieldCheckerResolver
import model.registry.ResolverFragmentTemplates
import model.registry.ResolverTarget
import model.registry.VariableDefinition
import model.registry.VariablesProviderFunction
import model.requireObjectField
import model.requireQueryTypeDef
import model.testing.TestWorld
import semantics.contract.CheckerApplicationRecorder
import semantics.shared.SharedOperationContext
import viaduct.engine.api.CheckerResult

class FieldCheckerLifecycleTest : Resolver26DispatcherResource {
    @Test
    fun `failed checker provider terminates bindings and dependent values without invoking the checker`() {
        val failure = IllegalStateException("checker provider failed")
        val world = providerWorld { throw failure }
        val recorder = CheckerApplicationRecorder()
        val operation = SharedOperationContext.create(world.assumptions, checkerObserver = recorder)
        val result = operation.resolveWithTestDispatcher(world.schemas.operationSelectionsFrom("{ checked }"))
        val key = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", "checked"), emptyMap())
        assertEquals(1, result.getCell(key).value.get())
        assertSame(failure, assertFailsWith<IllegalStateException> { result.getCell(key).fieldCheckerResult.get() })
        val fragments = checkNotNull(world.assumptions.resolverRegistry.fieldChecker(key.field))
            .instantiateFragmentsAt(result, listOf(key))
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
            val operation = SharedOperationContext.create(world.assumptions)
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
            val operation = SharedOperationContext.create(world.assumptions)
            val job = Job()
            val result = operation.startResolve(world.schemas.operationSelectionsFrom("{ checked }"), CoroutineScope(job + resolverDispatcher))
            withTimeout(2_000) { entered.await() }
            assertFailsWith<TimeoutCancellationException> { withTimeout(50) { job.join() } }
            withTimeout(2_000) { job.cancelAndJoin() }
            assertCanceled(world, operation, result)
        }

    @Test
    fun `checker ctx query uses an independent raw scope with ordinary checked resolver boundaries`() {
        val recorder = CheckerApplicationRecorder()
        val roots = ConcurrentLinkedQueue<ObjectEngineResult>()
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              checked: Int! @resolver(result: 1)
              dependency: Int! @resolver(of: "protected", result: 7)
              protected: Int! @resolver(result: 9)
            }
            """.trimIndent(),
            fieldCheckers = { schema ->
                val checked = schema.loweredSchema.requireObjectField("Query", "checked")
                val dependency = schema.loweredSchema.requireObjectField("Query", "dependency")
                val protected = schema.loweredSchema.requireObjectField("Query", "protected")
                mapOf(
                    checked to FieldCheckerResolver.of(checked, schema.loweredSchema.requireQueryTypeDef()) { _, _, ctx ->
                        val selections = schema.fragmentFrom("fragment Child on Query { dependency }").materializeSelections
                        assertEquals(7, ctx.resolveSelectionSet(selections).get("dependency"))
                        assertEquals(7, ctx.resolveSelectionSet(selections).get("dependency"))
                        CheckerResult.Success
                    },
                    dependency to FieldCheckerResolver.of(dependency, schema.loweredSchema.requireQueryTypeDef()) { _, _, _ -> CheckerResult.Success },
                    protected to FieldCheckerResolver.of(protected, schema.loweredSchema.requireQueryTypeDef()) { _, _, _ -> CheckerResult.Success },
                )
            },
        )
        val resolverObserver = object : semantics.shared.ResolverObserver {
            override fun onResolverInvocation(observation: semantics.shared.ResolverInvocationObservation) = Unit

            override fun onQueryOERPrepared(
                queryOER: semantics.shared.SharedOERContext,
                queryOERDepth: Int?
            ) {
                roots += queryOER.occurrence.root
            }
        }
        val operation = SharedOperationContext.create(world.assumptions, checkerObserver = recorder, resolverObserver = resolverObserver)
        operation.resolveWithTestDispatcher(world.schemas.operationSelectionsFrom("{ checked dependency }"))
        assertEquals(5, recorder.checkerApplications().size)
        assertEquals(3, recorder.checkerApplications().count { it.checkedCoordinate.name == "protected" })
        assertEquals(3, roots.toSet().size)
    }

    private fun providerWorld(provider: VariablesProviderFunction): TestWorld =
        TestWorld.fromDSL(
            """
        extend type Query {
          checked: Int! @resolver(result: 1)
          dependency(value: Int): Int @resolver(result: "value(${'$'}value)")
        }
            """.trimIndent(),
            fieldCheckers = { schema ->
                val field = schema.loweredSchema.requireObjectField("Query", "checked")
                mapOf(
                    field to FieldCheckerResolver.of(
                        field,
                        schema.loweredSchema.requireQueryTypeDef(),
                        mapOf(
                            "input" to ResolverFragmentTemplates(
                                objectFragmentTemplate = schema.fragmentFrom(
                                    "fragment Input on Query { dependency(value: ${'$'}v) }",
                                    variableTarget = ResolverTarget.FieldCheckerTarget(field)
                                ).materializeSelections,
                                queryFragmentTemplate = model.materializeSelectionForestOf(),
                                variables = mapOf(Arguments.Variable.of(ResolverTarget.FieldCheckerTarget(field), "v") to VariableDefinition.FromProvider),
                                variablesProvider = provider,
                            )
                        )
                    ) { _, _, _ -> CheckerResult.Success }
                )
            },
        )

    private fun assertCanceled(
        world: TestWorld,
        operation: SharedOperationContext<*>,
        result: ObjectEngineResult
    ) {
        val key = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", "checked"), emptyMap())
        assertFailsWith<CancellationException> { result.getCell(key).fieldCheckerResult.get() }
        val fragments = checkNotNull(world.assumptions.resolverRegistry.fieldChecker(key.field)).instantiateFragmentsAt(result, listOf(key))
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
