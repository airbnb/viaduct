@file:Suppress("ForbiddenImport")

package semantics.contract

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import model.ObjectEngineResult
import model.emptyFragmentOf
import model.fragmentFrom
import model.materializeSelectionForestOf
import model.objectOf
import model.operationSelectionsFrom
import model.registry.ResolverFragmentTemplates
import model.registry.TypeCheckerResolver
import model.requireObjectField
import model.requireQueryTypeDef
import model.requireType
import model.testing.TestWorld
import model.testing.fieldResolverOf
import semantics.shared.CycleCheckState
import semantics.shared.ResolverReadCycleException
import semantics.shared.SharedOperationContext
import viaduct.engine.api.CheckerResult
import viaduct.graphql.schema.ViaductSchema

/** Liveness and failure of fragment-bearing type checks, independent of output policy. */
interface GroundedTypeCheckerLifecycleContract {
    val coroutineResolverSubject: CoroutineResolverTestSubject

    @Test
    fun `type checker and checked parent input cycle terminates on the OER slot`() {
        val worldFixture = TestWorld.fromSDL(
            selectiveResolvers = coroutineResolverSubject.selectiveResolvers,
            schemaSDL = """
                directive @parent on FIELD_DEFINITION
                type Query { root: Root! }
                type Root { value: Int! child: Child! }
                type Child { parent: Root! @parent derived: Int! }
            """.trimIndent(),
            fieldResolvers = { schema ->
                mapOf(
                    schema.loweredSchema.requireObjectField("Query", "root") to fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                        schema.loweredSchema.objectOf("Root") {
                            "value" setTo 1
                            "child" setTo schema.loweredSchema.objectOf("Child")
                        }
                    },
                    schema.loweredSchema.requireObjectField("Child", "derived") to fieldResolverOf(schema.fragmentFrom("fragment Input on Child { parent { value } }")) { input, _ ->
                        (input.get("parent") as viaduct.engine.api.EngineObjectData.Sync).get("value")
                    },
                )
            },
            typeCheckers = { schema ->
                val root = schema.loweredSchema.requireType("Root") as ViaductSchema.Object
                mapOf(
                    root to TypeCheckerResolver.of(
                        root,
                        schema.loweredSchema.requireQueryTypeDef(),
                        mapOf(
                            "input" to ResolverFragmentTemplates(
                                schema.fragmentFrom("fragment Input on Root { child { derived } }").materializeSelections,
                                materializeSelectionForestOf(),
                            )
                        )
                    ) { inputs, _ ->
                        (inputs.getValue("input").objectValue.get("child") as viaduct.engine.api.EngineObjectData.Sync).get("derived")
                        CheckerResult.Success
                    }
                )
            },
        )
        val world = worldFixture.assumptions
        val result = coroutineResolverSubject.resolve(SharedOperationContext.create(world), worldFixture.schemas.operationSelectionsFrom("{ root { value } }"))
        val root = assertIs<ObjectEngineResult>(result.getCell(ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", "root"), emptyMap())).value.get())
        val failure = assertFailsWith<Exception> { root.typeCheckerResult.get() }
        assertTrue(generateSequence(failure as Throwable?) { it.cause }.any { it is ResolverReadCycleException })
    }

    @Test
    fun `request cancellation terminates type checker waiting for its Query input`() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val checkerInvoked = AtomicBoolean()
            val worldFixture = TestWorld.fromSDL(
                selectiveResolvers = coroutineResolverSubject.selectiveResolvers,
                schemaSDL = "type Query { item: Item! dependency: Int! } type Item { value: Int! }",
                fieldResolvers = { schema ->
                    mapOf(
                        schema.loweredSchema.requireObjectField(
                            "Query",
                            "item"
                        ) to fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> schema.loweredSchema.objectOf("Item") { "value" setTo 1 } },
                        schema.loweredSchema.requireObjectField("Query", "dependency") to fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                            entered.complete(Unit)
                            try {
                                CompletableDeferred<Nothing>().await()
                            } finally {
                                cancelled.complete(Unit)
                            }
                        },
                    )
                },
                typeCheckers = { schema ->
                    val item = schema.loweredSchema.requireType("Item") as ViaductSchema.Object
                    mapOf(
                        item to TypeCheckerResolver.of(
                            item,
                            schema.loweredSchema.requireQueryTypeDef(),
                            mapOf(
                                "input" to ResolverFragmentTemplates(
                                    materializeSelectionForestOf(),
                                    schema.fragmentFrom("fragment Input on Query { dependency }").materializeSelections,
                                )
                            )
                        ) { _, _ ->
                            checkerInvoked.set(true)
                            CheckerResult.Success
                        }
                    )
                },
            )
            val world = worldFixture.assumptions
            val requestJob = Job()
            val requestScope = CoroutineScope(coroutineContext + requestJob)
            try {
                val result = coroutineResolverSubject.startResolution(
                    SharedOperationContext.create(world),
                    requestScope,
                    worldFixture.schemas.operationSelectionsFrom("{ item { value } }"),
                    CycleCheckState.create()
                )
                withTimeout(5_000) { entered.await() }
                val item = assertIs<ObjectEngineResult>(result.getCell(ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", "item"), emptyMap())).value.get())
                val cancellation = CancellationException("cancel type checker input")
                requestJob.cancel(cancellation)
                withTimeout(5_000) {
                    requestJob.join()
                    cancelled.await()
                }
                assertEquals(cancellation.message, assertFailsWith<CancellationException> { item.typeCheckerResult.get() }.message)
                assertFalse(checkerInvoked.get())
            } finally {
                requestJob.cancelAndJoin()
            }
        }
}
