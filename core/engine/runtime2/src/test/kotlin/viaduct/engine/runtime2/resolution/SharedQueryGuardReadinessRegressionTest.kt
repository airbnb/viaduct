@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import viaduct.engine.runtime2.contract.selectionValues
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.emptyFragmentOf
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

/** Independent owners must not impose a readiness barrier on one another through a shared OR. */
class SharedQueryGuardReadinessRegressionTest : ResolutionDispatcherResource {
    @Test
    fun `healthy owner completes while another owner's Query guard is suspended`() =
        runBlocking {
            val slowStarted = CompletableDeferred<Unit>()
            val fastStarted = CompletableDeferred<Unit>()
            val releaseSlow = CompletableDeferred<Unit>()
            val world = TestWorld.fromSDL(
                selectiveResolvers = true,
                schemaSDL = "type Query { slow: Int!, fast: Int!, source: Int! }",
                fieldResolvers = { schema ->
                    val owners = listOf("slow", "fast").associate { name ->
                        val field = schema.loweredSchema.requireObjectField("Query", name)
                        field to fieldResolverOf(
                            objectFragment = schema.loweredSchema.emptyFragmentOf("Query"),
                            queryFragment = schema.fragmentFrom(
                                "fragment Input on Query { source @include(if: ${'$'}enabled) }",
                                variableField = field,
                            ),
                        ) { _, query, _ -> query.selectionValues().getValue("source") }
                            .withVariablesProvider(setOf("enabled")) {
                                if (name == "slow") {
                                    slowStarted.complete(Unit)
                                    releaseSlow.await()
                                } else {
                                    fastStarted.complete(Unit)
                                }
                                mapOf("enabled" to true)
                            }
                    }
                    owners + (
                        schema.loweredSchema.requireObjectField("Query", "source") to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                                7
                            }
                    )
                },
            )
            val job = Job()
            val scope = CoroutineScope(resolverDispatcher + job)
            try {
                val operation = SharedOperationContext.create(world.assumptions)
                val result = operation.startResolve(
                    world.schemas.fragmentFrom("fragment Test on Query { slow fast }").subselections,
                    scope,
                )
                withTimeout(5_000) {
                    slowStarted.await()
                    fastStarted.await()
                }
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
