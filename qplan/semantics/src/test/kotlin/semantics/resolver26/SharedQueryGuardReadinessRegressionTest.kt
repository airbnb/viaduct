@file:Suppress("ForbiddenImport")

package semantics.resolver26

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import model.ObjectEngineResult
import model.emptyFragmentOf
import model.fragmentFrom
import model.requireObjectField
import model.testing.TestWorld
import model.testing.fieldResolverOf
import semantics.contract.selectionValues
import semantics.shared.SharedOperationContext

/** Independent owners must not impose a readiness barrier on one another through a shared OR. */
class SharedQueryGuardReadinessRegressionTest : Resolver26DispatcherResource {
    @Test
    fun `healthy owner completes while another owner's Query guard is suspended`() = runBlocking {
        val slowStarted = CompletableDeferred<Unit>()
        val fastStarted = CompletableDeferred<Unit>()
        val releaseSlow = CompletableDeferred<Unit>()
        val world = TestWorld.fromSDL(
            selectiveResolvers = true,
            schemaSDL = "type Query { slow: Int!, fast: Int!, source: Int! }",
            fieldResolvers = { schema ->
                val owners = listOf("slow", "fast").associate { name ->
                    val field = schema.requireObjectField("Query", name)
                    field to fieldResolverOf(
                        objectFragment = schema.emptyFragmentOf("Query"),
                        queryFragment = schema.fragmentFrom(
                            "fragment Input on Query { source @include(if: ${'$'}enabled) }",
                            variableField = field,
                        ),
                    ) { _, query, _ -> query.selectionValues().getValue("source") }
                        .withVariablesProvider(setOf("enabled")) {
                            if (name == "slow") {
                                slowStarted.complete(Unit)
                                releaseSlow.await()
                            } else fastStarted.complete(Unit)
                            mapOf("enabled" to true)
                        }
                }
                owners + (schema.requireObjectField("Query", "source") to
                    fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                        7
                    })
            },
        )
        val job = Job()
        val scope = CoroutineScope(resolverDispatcher + job)
        try {
            val operation = SharedOperationContext.create(world.assumptions)
            val result = operation.startResolve(
                world.assumptions.fragmentFrom("fragment Test on Query { slow fast }").subselections,
                scope,
            )
            withTimeout(5_000) { slowStarted.await(); fastStarted.await() }
            val fastCell = result.getCell(
                ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", "fast"), emptyMap()),
            )
            val fastBeforeSlowRelease = withTimeoutOrNull(1_000) { fastCell.value.await() }
            releaseSlow.complete(Unit)
            assertEquals(7, withTimeout(5_000) { fastCell.value.await() })
            assertEquals(7, fastBeforeSlowRelease, "An independent true guard must make the shared dependency ready")
        } finally {
            releaseSlow.complete(Unit)
            job.cancelAndJoin()
        }
    }
}
