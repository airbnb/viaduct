@file:Suppress("ForbiddenImport")

package semantics.contract

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import model.Assumptions
import model.EngineResultCell
import model.ObjectEngineResult
import model.PathComponent
import model.SelectionForest
import model.emptyFragmentOf
import model.operationSelectionsFrom
import model.registry.FieldCheckerResolver
import model.requireObjectField
import model.requireQueryTypeDef
import model.testing.TestWorld
import model.testing.fieldResolverOf
import semantics.shared.CycleCheckState
import semantics.shared.CycleSlot
import semantics.shared.CycleTask
import semantics.shared.ResolverInvocationObservation
import semantics.shared.ResolverObserver
import semantics.shared.SharedOperationContext
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.CheckerResultContext

/** Fragment-free field-checker publication and lifecycle behavior shared by coroutine resolvers. */
interface FragmentFreeFieldCheckerPublicationContract {
    val coroutineResolverSubject: CoroutineResolverTestSubject

    @Test
    fun `reserves every value and checker slot before any local producer starts`() {
        val cells = linkedMapOf<String, EngineResultCell>()
        fun assertBarrier() {
            assertEquals(setOf("first", "second"), cells.keys)
            cells.values.forEach { cell ->
                cell.getValue()
                assertTrue(cell.isFieldCheckerResultSet())
            }
        }
        val resolverObserver =
            object : ResolverObserver {
                override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                    assertBarrier()
                }
            }
        val world =
            TestWorld.fromDSL(
                schemaSDL =
                    """
                    extend type Query {
                      first: Int! @resolver(result: 1)
                      second: Int! @resolver(result: 2)
                    }
                    """.trimIndent(),
                selectiveResolvers = coroutineResolverSubject.selectiveResolvers,
                fieldCheckers = { schema ->
                    val field = schema.requireObjectField("Query", "first")
                    mapOf(
                        field to
                            FieldCheckerResolver.of(field, schema.requireQueryTypeDef()) { _, _, _ ->
                                assertBarrier()
                                CheckerResult.Success
                            },
                    )
                },
            ).assumptions
        val cycleChecker =
            object : CycleCheckState {
                override fun registerWriter(
                    slot: CycleSlot,
                    writer: CycleTask,
                ) {
                    cells[(writer.path.last() as ObjectEngineResult.ObjectKey).field.name] = slot.cell
                }

                override fun cycleCheck(
                    reader: CycleTask,
                    slot: CycleSlot,
                ) {}
            }

        resolve(
            world,
            world.operationSelectionsFrom("{ first second }"),
            cycleChecker,
            resolverObserver,
        )

        assertSame(CheckerResult.Success, cells.getValue("first").getFieldCheckerResult().get())
        assertNull(cells.getValue("second").getFieldCheckerResult().get())
    }

    @Test
    fun `active and passive selected fields run once while an unselected returned field does not`() {
        val activeChecks = AtomicInteger()
        val passiveChecks = AtomicInteger()
        val extraChecks = AtomicInteger()
        val world =
            TestWorld.fromDSL(
                schemaSDL =
                    """
                    extend type Query {
                      item: Item! @resolver(result: { passive: 7, extra: 9 })
                    }

                    type Item {
                      active: Int! @resolver(result: 8)
                      passive: Int!
                      extra: Int!
                    }
                    """.trimIndent(),
                selectiveResolvers = coroutineResolverSubject.selectiveResolvers,
                fieldCheckers = { schema ->
                    listOf(
                        "active" to activeChecks,
                        "passive" to passiveChecks,
                        "extra" to extraChecks,
                    ).associate { (name, count) ->
                        val field = schema.requireObjectField("Item", name)
                        field to
                            FieldCheckerResolver.of(field, schema.requireQueryTypeDef()) { _, _, _ ->
                                count.incrementAndGet()
                                CheckerResult.Success
                            }
                    }
                },
            ).assumptions

        val root = resolve(world, world.operationSelectionsFrom("{ item { active passive } }"))
        val item = root.objectValue(world, "Query", "item")

        assertEquals(1, activeChecks.get())
        assertEquals(1, passiveChecks.get())
        assertEquals(0, extraChecks.get())
        assertSame(CheckerResult.Success, item.cell(world, "Item", "active").getFieldCheckerResult().get())
        assertSame(CheckerResult.Success, item.cell(world, "Item", "passive").getFieldCheckerResult().get())
    }

    @Test
    fun `argument-distinct occurrences receive grounded arguments`() {
        val argumentsSeen = ConcurrentLinkedQueue<Int>()
        val world =
            TestWorld.fromSDL(
                schemaSDL = "type Query { checked(value: Int!): Int! }",
                selectiveResolvers = coroutineResolverSubject.selectiveResolvers,
                fieldResolvers = { schema ->
                    val checked = schema.requireObjectField("Query", "checked")
                    mapOf(
                        checked to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, arguments ->
                                arguments.fieldValues.getValue("value")
                            },
                    )
                },
                fieldCheckers = { schema ->
                    val checked = schema.requireObjectField("Query", "checked")
                    mapOf(
                        checked to
                            FieldCheckerResolver.of(checked, schema.requireQueryTypeDef()) { arguments, _, _ ->
                                argumentsSeen += arguments.fieldValues.getValue("value") as Int
                                CheckerResult.Success
                            },
                    )
                },
            ).assumptions

        val result =
            resolve(
                world,
                world.operationSelectionsFrom("{ first: checked(value: 1) second: checked(value: 2) }"),
            )

        assertEquals(setOf(1, 2), argumentsSeen.toSet())
        for (value in listOf(1, 2)) {
            val cell = result.cell(world, "Query", "checked", mapOf("value" to value))
            assertEquals(value, cell.getValue().get())
            assertSame(CheckerResult.Success, cell.getFieldCheckerResult().get())
        }
    }

    @Test
    fun `checker denial absence and exception do not change field values`() {
        val failure = IllegalStateException("checker failed")
        val denial = ContractCheckerError()
        val world =
            TestWorld.fromDSL(
                schemaSDL =
                    """
                    extend type Query {
                      denied: Int! @resolver(result: 1)
                      absent: Int! @resolver(result: 2)
                      failed: Int! @resolver(result: 3)
                    }
                    """.trimIndent(),
                selectiveResolvers = coroutineResolverSubject.selectiveResolvers,
                fieldCheckers = { schema ->
                    fun checker(name: String, function: suspend () -> CheckerResult): Pair<viaduct.graphql.schema.ViaductSchema.ObjectField, FieldCheckerResolver> {
                        val field = schema.requireObjectField("Query", name)
                        return field to FieldCheckerResolver.of(field, schema.requireQueryTypeDef()) { _, _, _ -> function() }
                    }
                    mapOf(
                        checker("denied") { denial },
                        checker("failed") { throw failure },
                    )
                },
            ).assumptions

        val result = resolve(world, world.operationSelectionsFrom("{ denied absent failed }"))

        assertEquals(1, result.cell(world, "Query", "denied").getValue().get())
        assertEquals(2, result.cell(world, "Query", "absent").getValue().get())
        assertEquals(3, result.cell(world, "Query", "failed").getValue().get())
        assertSame(denial, result.cell(world, "Query", "denied").getFieldCheckerResult().get())
        assertNull(result.cell(world, "Query", "absent").getFieldCheckerResult().get())
        assertSame(
            failure,
            assertFailsWith<IllegalStateException> {
                result.cell(world, "Query", "failed").getFieldCheckerResult().get()
            },
        )
    }

    @Test
    fun `request cancellation terminates checker promises before entry and during execution`() = runBlocking {
        for (cancelBeforeEntry in listOf(true, false)) {
            val checkerEntered = CompletableDeferred<Unit>()
            val world = cancellationWorld(checkerEntered).assumptions
            val requestJob = Job()
            val requestScope = CoroutineScope(coroutineContext + requestJob)
            val cancellation = CancellationException("request cancelled")
            try {
                val result =
                    coroutineResolverSubject.startResolution(
                        SharedOperationContext.create(world),
                        requestScope,
                        world.operationSelectionsFrom("{ checked }"),
                        CycleCheckState.create(),
                    )
                if (cancelBeforeEntry) {
                    requestJob.cancel(cancellation)
                } else {
                    withTimeout(5_000) { checkerEntered.await() }
                    requestJob.cancel(cancellation)
                }
                withTimeout(5_000) { requestJob.join() }
                val checkerFailure =
                    assertFailsWith<CancellationException> {
                        result.cell(world, "Query", "checked").getFieldCheckerResult().await()
                    }
                assertEquals(cancellation.message, checkerFailure.message)
                assertEquals(!cancelBeforeEntry, checkerEntered.isCompleted)
            } finally {
                requestJob.cancelAndJoin()
            }
        }
    }

    private fun cancellationWorld(checkerEntered: CompletableDeferred<Unit>): TestWorld =
        TestWorld.fromDSL(
            schemaSDL =
                """
                extend type Query {
                  checked: Int! @resolver(result: 7)
                }
                """.trimIndent(),
            selectiveResolvers = coroutineResolverSubject.selectiveResolvers,
            fieldCheckers = { schema ->
                val checked = schema.requireObjectField("Query", "checked")
                mapOf(
                    checked to
                        FieldCheckerResolver.of(checked, schema.requireQueryTypeDef()) { _, _, _ ->
                            checkerEntered.complete(Unit)
                            CompletableDeferred<Nothing>().await()
                        },
                )
            },
        )
}

private fun FragmentFreeFieldCheckerPublicationContract.resolve(
    world: Assumptions,
    selections: SelectionForest,
    cycleChecker: CycleCheckState = CycleCheckState.create(),
    resolverObserver: ResolverObserver = ResolverObserver.NOP,
): ObjectEngineResult =
    coroutineResolverSubject.resolve(
        SharedOperationContext.create(world, resolverObserver = resolverObserver),
        selections,
        cycleChecker,
    )

private fun ObjectEngineResult.cell(
    world: Assumptions,
    typeName: String,
    fieldName: String,
    arguments: Map<String, Any?> = emptyMap(),
): EngineResultCell =
    getCell(ObjectEngineResult.GroundKey.of(world.schema.requireObjectField(typeName, fieldName), arguments))

private fun ObjectEngineResult.objectValue(
    world: Assumptions,
    typeName: String,
    fieldName: String,
): ObjectEngineResult = assertIs(cell(world, typeName, fieldName).getValue().get())

private class ContractCheckerError : CheckerResult.Error {
    override val error: Exception = IllegalStateException("denied")

    override fun isErrorForResolver(ctx: CheckerResultContext): Boolean = true

    override fun combine(fieldResult: CheckerResult.Error): CheckerResult.Error = this
}
