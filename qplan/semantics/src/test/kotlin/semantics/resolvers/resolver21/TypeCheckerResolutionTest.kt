@file:Suppress("ForbiddenImport")

package semantics.resolvers.resolver21

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import model.EngineErrorData
import model.ObjectEngineResult
import model.fragmentFrom
import model.materializeSelectionForestOf
import model.operationSelectionsFrom
import model.outputValue
import model.registry.FieldCheckerResolver
import model.registry.ResolverFragmentTemplates
import model.registry.TypeCheckerResolver
import model.requireObjectField
import model.requireQueryTypeDef
import model.requireType
import model.schemaType
import model.testing.TestWorld
import semantics.resolvers.materializeResolverInput
import semantics.resolvers.resolver22.resolve as resolve22
import semantics.shared.CycleCheckState
import semantics.shared.SharedOperationContext
import semantics.shared.fieldResolverCycleTask
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.CheckerResultContext
import viaduct.engine.api.EngineObjectData
import viaduct.graphql.schema.ViaductSchema

class TypeCheckerResolutionTest {
    @Test
    fun `fragment-free named inputs are raw object and Query projections`() {
        val invoked = AtomicBoolean()
        val world =
            TestWorld.fromDSL(
                schemaSDL =
                    """
                    extend type Query {
                      item: Item! @resolver(result: {id: 1})
                    }

                    type Item {
                      id: Int!
                    }
                    """.trimIndent(),
                selectiveResolvers = false,
                typeCheckers = { schema ->
                    val item = schema.requireType("Item") as ViaductSchema.Object
                    val emptyPair =
                        ResolverFragmentTemplates(
                            objectFragmentTemplate = materializeSelectionForestOf(),
                            queryFragmentTemplate = materializeSelectionForestOf(),
                        )
                    mapOf(
                        item to
                            TypeCheckerResolver.of(
                                item,
                                schema.requireQueryTypeDef(),
                                fragmentTemplates = mapOf("empty" to emptyPair),
                            ) { inputs, _ ->
                                val input = inputs.getValue("empty")
                                assertEquals("Item", input.objectValue.schemaType.name)
                                assertEquals("Query", input.queryValue.schemaType.name)
                                assertTrue(input.objectValue.getSelections().toList().isEmpty())
                                assertTrue(input.queryValue.getSelections().toList().isEmpty())
                                invoked.set(true)
                                CheckerResult.Success
                            },
                    )
                },
            ).assumptions

        val result =
            SharedOperationContext.create(world).resolve(
                world.operationSelectionsFrom("{ item { id } }"),
            )

        assertTrue(invoked.get())
        assertSame(CheckerResult.Success, result.objectValue("item").typeCheckerResult.get())
    }

    @Test
    fun `denial and exceptional completion terminate and are observed by checked materialization`() =
        runBlocking {
            val denial = TypeDenial()
            val failure = IllegalStateException("type checker failed")
            val world =
                TestWorld.fromDSL(
                    schemaSDL =
                        """
                        extend type Query {
                          denied: Denied! @resolver(result: {id: 1})
                          failed: Failed! @resolver(result: {id: 2})
                        }

                        type Denied { id: Int! }
                        type Failed { id: Int! }
                        """.trimIndent(),
                    selectiveResolvers = false,
                    typeCheckers = { schema ->
                        val denied = schema.requireType("Denied") as ViaductSchema.Object
                        val failed = schema.requireType("Failed") as ViaductSchema.Object
                        mapOf(
                            denied to TypeCheckerResolver.of(denied, schema.requireQueryTypeDef()) { _, _ -> denial },
                            failed to TypeCheckerResolver.of(failed, schema.requireQueryTypeDef()) { _, _ -> throw failure },
                        )
                    },
                ).assumptions
            val operation = SharedOperationContext.create(world)
            val result = operation.resolve(world.operationSelectionsFrom("{ denied { id } failed { id } }"))
            val denied = result.objectValue("denied")
            val failed = result.objectValue("failed")

            assertSame(denial, denied.typeCheckerResult.get())
            assertSame(failure, assertFailsWith<IllegalStateException> { failed.typeCheckerResult.get() })

            val materialized =
                result.materializeResolverInput(
                    operation = operation,
                    cycleChecker = CycleCheckState.createNOP(),
                    selections =
                        world.fragmentFrom("fragment Input on Query { denied { id } }").materializeSelections,
                    reader = result.fieldResolverCycleTask(emptyList()),
                )
            assertSame(denial.error, assertIs<EngineErrorData>(materialized.outputValue("denied")).cause)
        }

    @Test
    fun `unchecked checker-only reads retain immediate null type results`() {
        val typeInvocations = AtomicInteger()
        val world =
            TestWorld.fromDSL(
                schemaSDL =
                    """
                    extend type Query {
                      checked: Int! @resolver(result: 1)
                      raw: Item! @resolver(result: {id: 2})
                    }

                    type Item { id: Int! }
                    """.trimIndent(),
                selectiveResolvers = false,
                fieldCheckers = { schema ->
                    val checked = schema.requireObjectField("Query", "checked")
                    val raw = schema.fragmentFrom("fragment Raw on Query { raw { id } }").materializeSelections
                    mapOf(
                        checked to
                            FieldCheckerResolver.of(
                                checked,
                                schema.requireQueryTypeDef(),
                                fragmentTemplates =
                                    mapOf(
                                        "raw" to
                                            ResolverFragmentTemplates(
                                                objectFragmentTemplate = raw,
                                                queryFragmentTemplate = materializeSelectionForestOf(),
                                            ),
                                    ),
                            ) { _, inputs, _ ->
                                assertIs<EngineObjectData.Sync>(inputs.getValue("raw").objectValue.get("raw"))
                                CheckerResult.Success
                            },
                    )
                },
                typeCheckers = { schema ->
                    val item = schema.requireType("Item") as ViaductSchema.Object
                    mapOf(
                        item to TypeCheckerResolver.of(item, schema.requireQueryTypeDef()) { _, _ ->
                            typeInvocations.incrementAndGet()
                            CheckerResult.Success
                        },
                    )
                },
            ).assumptions

        val result =
            SharedOperationContext.create(world).resolve22(
                world.operationSelectionsFrom("{ checked }"),
            )
        val raw = result.objectValue("raw")

        assertEquals(0, typeInvocations.get())
        assertTrue(raw.typeCheckerResult.isCompleted)
        assertNull(raw.typeCheckerResult.get())
    }

    @Test
    fun `parent backedge reuses the ancestor type-check result`() {
        val invocations = AtomicInteger()
        val world =
            TestWorld.fromDSL(
                schemaSDL =
                    """
                    extend type Query {
                      root: Root! @resolver(result: {value: 1, child: {}})
                    }

                    type Root {
                      value: Int!
                      child: Child!
                    }

                    type Child {
                      parent: Root! @parent
                    }
                    """.trimIndent(),
                selectiveResolvers = false,
                typeCheckers = { schema ->
                    val root = schema.requireType("Root") as ViaductSchema.Object
                    mapOf(
                        root to TypeCheckerResolver.of(root, schema.requireQueryTypeDef()) { _, _ ->
                            invocations.incrementAndGet()
                            CheckerResult.Success
                        },
                    )
                },
            ).assumptions

        val result =
            SharedOperationContext.create(world).resolve22(
                world.operationSelectionsFrom("{ root { child { parent { value } } } }"),
            )
        val root = result.objectValue("root")
        val child = root.objectValue("child")
        val parent = child.objectValue("parent")

        assertSame(root, parent)
        assertEquals(1, invocations.get())
        assertSame(CheckerResult.Success, root.typeCheckerResult.get())
    }

    @Test
    fun `cancellation before type-checker entry cancels the OER promise`() =
        runBlocking {
            val invoked = AtomicBoolean()
            val world =
                TestWorld.fromDSL(
                    schemaSDL =
                        """
                        extend type Query {
                          value: Int! @resolver(result: 1)
                        }
                        """.trimIndent(),
                    selectiveResolvers = false,
                    typeCheckers = { schema ->
                        val query = schema.requireQueryTypeDef()
                        mapOf(
                            query to TypeCheckerResolver.of(query, query) { _, _ ->
                                invoked.set(true)
                                CheckerResult.Success
                            },
                        )
                    },
                ).assumptions
            val queued = QueuedDispatcher()
            val job = Job()
            val operation = SharedOperationContext.create(world)
            val result =
                startCoroutineResolution(
                    operation = operation,
                    requestScope = CoroutineScope(job + queued),
                    selections = world.operationSelectionsFrom("{ value }"),
                    cycleChecker = CycleCheckState.create(),
                )

            job.cancel(CancellationException("cancelled before type-checker entry"))
            queued.runUntilIdle()
            job.join()

            assertFalse(invoked.get())
            assertFailsWith<CancellationException> { result.typeCheckerResult.get() }
        }
}

private fun ObjectEngineResult.rawValue(fieldName: String): Any? {
    val field = type.fields.single { it.name == fieldName }
    return getCell(ObjectEngineResult.GroundKey.of(field, emptyMap())).value.get()
}

private fun ObjectEngineResult.objectValue(fieldName: String): ObjectEngineResult = assertIs<ObjectEngineResult>(rawValue(fieldName))

private class TypeDenial : CheckerResult.Error {
    override val error: Exception = IllegalStateException("type denied")

    override fun isErrorForResolver(ctx: CheckerResultContext): Boolean = true

    override fun combine(fieldResult: CheckerResult.Error): CheckerResult.Error = this
}

private class QueuedDispatcher : CoroutineDispatcher() {
    private val tasks = ArrayDeque<Runnable>()

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        tasks += block
    }

    fun runUntilIdle() {
        while (tasks.isNotEmpty()) {
            tasks.removeFirst().run()
        }
    }
}
