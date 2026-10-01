@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.contract

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import viaduct.engine.api.CheckerResult
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.EngineResultCell
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.arg
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.materializeSelectionForestOf
import viaduct.engine.runtime2.model.outputValue
import viaduct.engine.runtime2.model.registry.CheckerInput
import viaduct.engine.runtime2.model.registry.FieldCheckerResolver
import viaduct.engine.runtime2.model.registry.ResolverFragmentTemplates
import viaduct.engine.runtime2.model.registry.ResolverTarget
import viaduct.engine.runtime2.model.registry.VariableDefinition
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.emptyFragmentOf
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.testing.objectOf
import viaduct.engine.runtime2.resolution.framework.CycleCheckState
import viaduct.engine.runtime2.resolution.framework.CycleSlot
import viaduct.engine.runtime2.resolution.framework.CycleSlotKind
import viaduct.engine.runtime2.resolution.framework.CycleTask
import viaduct.engine.runtime2.resolution.framework.CycleTaskKind
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation
import viaduct.engine.runtime2.resolution.framework.ResolverObserver
import viaduct.engine.runtime2.resolution.framework.SharedOERContext
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.schema.operationSelectionsFrom

/** Owner-local raw checker projections from the orchestration's shared Query OER. */
interface GroundedFieldCheckerQueryFragmentContract {
    val coroutineResolverSubject: CoroutineResolverTestSubject

    @Test
    fun `named object and Query inputs honor fromArgument inclusion conditions`() {
        val checkerCalls = AtomicInteger()
        val worldFixture =
            TestWorld.fromDSL(
                schemaSDL =
                    """
                    extend type Query {
                      item: Item! @resolver(result: {})
                      queryDependency: Int! @resolver(result: 3)
                    }

                    type Item {
                      checked(enabled: Boolean!): Int! @resolver(result: 1)
                      objectDependency: Int! @resolver(result: 2)
                    }
                    """.trimIndent(),
                selectiveResolvers = coroutineResolverSubject.selectiveResolvers,
                fieldCheckers = { schema ->
                    val checked = schema.loweredSchema.requireObjectField("Item", "checked")
                    val enabled = Arguments.Variable.of(ResolverTarget.FieldCheckerTarget(checked), "enabled")
                    mapOf(
                        checked to
                            FieldCheckerResolver.of(
                                field = checked,
                                queryType = schema.loweredSchema.requireQueryTypeDef(),
                                fragmentTemplates =
                                    mapOf(
                                        "input" to
                                            ResolverFragmentTemplates(
                                                objectFragmentTemplate =
                                                    schema
                                                        .fragmentFrom(
                                                            "fragment Input on Item { objectDependency @include(if: ${'$'}enabled) }",
                                                            variableTarget = ResolverTarget.FieldCheckerTarget(checked),
                                                        ).materializeSelections,
                                                queryFragmentTemplate =
                                                    schema
                                                        .fragmentFrom(
                                                            "fragment Input on Query { queryDependency @include(if: ${'$'}enabled) }",
                                                            variableTarget = ResolverTarget.FieldCheckerTarget(checked),
                                                        ).materializeSelections,
                                                variables =
                                                    mapOf<Arguments.Variable, VariableDefinition>(
                                                        enabled to
                                                            VariableDefinition.FromArgument.of(
                                                                checkNotNull(checked.arg("enabled")),
                                                            ),
                                                    ),
                                            ),
                                    ),
                            ) { arguments, inputs, _ ->
                                checkerCalls.incrementAndGet()
                                val included = arguments.fieldValues.getValue("enabled") as Boolean
                                val input = inputs.getValue("input")
                                val expectedObjectKeys =
                                    if (included) setOf("objectDependency") else emptySet()
                                val expectedQueryKeys =
                                    if (included) setOf("queryDependency") else emptySet()
                                assertEquals(expectedObjectKeys, input.objectValue.getSelections())
                                assertEquals(expectedQueryKeys, input.queryValue.getSelections())
                                if (included) {
                                    assertEquals(2, input.objectValue.get("objectDependency"))
                                    assertEquals(3, input.queryValue.get("queryDependency"))
                                }
                                CheckerResult.Success
                            },
                    )
                },
            )
        val world = worldFixture.assumptions

        coroutineResolverSubject.resolve(
            SharedOperationContext.create(world),
            worldFixture.schemas.operationSelectionsFrom(
                "{ item { excluded: checked(enabled: false) included: checked(enabled: true) } }",
            ),
        )

        assertEquals(2, checkerCalls.get())
    }

    @Test
    fun `shares one Query OER while preserving owner-local checker projections`() {
        val checkerInputs =
            Collections.synchronizedList(mutableListOf<Map<String, CheckerInput>>())
        val queryOERs = Collections.synchronizedList(mutableListOf<SharedOERContext>())
        val sharedCheckerCalls = AtomicInteger()
        val sharedResolverCalls = AtomicInteger()
        val worldFixture =
            TestWorld.fromDSL(
                schemaSDL =
                    """
                    extend type Query {
                      item: Item! @resolver(result: {})
                      shared(seed: Int!): Int! @resolver(result: 7)
                    }

                    type Item {
                      checked(seed: Int!): Int! @resolver(result: 1)
                      objectShared(seed: Int!): Int! @resolver(result: 5)
                    }
                    """.trimIndent(),
                selectiveResolvers = coroutineResolverSubject.selectiveResolvers,
                fieldCheckers = { schema ->
                    val query = schema.loweredSchema.requireQueryTypeDef()
                    val checked = schema.loweredSchema.requireObjectField("Item", "checked")
                    val seed = Arguments.Variable.of(ResolverTarget.FieldCheckerTarget(checked), "seed")

                    fun queryInput(alias: String): ResolverFragmentTemplates =
                        ResolverFragmentTemplates(
                            objectFragmentTemplate =
                                schema
                                    .fragmentFrom(
                                        "fragment Input on Item { ${alias}Object: objectShared(seed: ${'$'}seed) }",
                                        variableTarget = ResolverTarget.FieldCheckerTarget(checked),
                                    ).materializeSelections,
                            queryFragmentTemplate =
                                schema
                                    .fragmentFrom(
                                        "fragment Input on Query { $alias: shared(seed: ${'$'}seed) }",
                                        variableTarget = ResolverTarget.FieldCheckerTarget(checked),
                                    ).materializeSelections,
                            variables =
                                mapOf<Arguments.Variable, VariableDefinition>(
                                    seed to
                                        VariableDefinition.FromArgument.of(
                                            checkNotNull(checked.arg("seed")),
                                        ),
                                ),
                        )
                    val shared = schema.loweredSchema.requireObjectField("Query", "shared")
                    mapOf(
                        checked to
                            FieldCheckerResolver.of(
                                field = checked,
                                queryType = query,
                                fragmentTemplates =
                                    linkedMapOf(
                                        "first" to queryInput("firstValue"),
                                        "second" to queryInput("secondValue"),
                                        "empty" to
                                            ResolverFragmentTemplates(
                                                objectFragmentTemplate = materializeSelectionForestOf(),
                                                queryFragmentTemplate = materializeSelectionForestOf(),
                                            ),
                                    ),
                            ) { _, inputs, _ ->
                                checkerInputs += inputs
                                CheckerResult.Success
                            },
                        shared to
                            FieldCheckerResolver.of(shared, query) { _, _, _ ->
                                sharedCheckerCalls.incrementAndGet()
                                CheckerResult.Success
                            },
                    )
                },
            )
        val world = worldFixture.assumptions
        val observer =
            object : ResolverObserver {
                override fun onQueryOERPrepared(
                    queryOER: SharedOERContext,
                    queryOERDepth: Int?
                ) {
                    if (queryOER.closedValueSelections
                            .byKey()
                            .keys
                            .any { it.field.name == "shared" }
                    ) {
                        queryOERs += queryOER
                    }
                }

                override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                    if (observation.field.name == "shared") sharedResolverCalls.incrementAndGet()
                }
            }

        coroutineResolverSubject.resolve(
            SharedOperationContext.create(world, resolverObserver = observer),
            worldFixture.schemas.operationSelectionsFrom(
                "{ item { first: checked(seed: 1) second: checked(seed: 2) } }",
            ),
        )

        assertEquals(2, checkerInputs.size)
        assertEquals(1, queryOERs.size)
        checkerInputs.forEach { inputs ->
            assertEquals(5, inputs.getValue("first").objectValue.get("firstValueObject"))
            assertEquals(5, inputs.getValue("second").objectValue.get("secondValueObject"))
            assertEquals(7, inputs.getValue("first").queryValue.get("firstValue"))
            assertEquals(7, inputs.getValue("second").queryValue.get("secondValue"))
            assertEquals(emptySet(), inputs.getValue("empty").queryValue.getSelections())
        }
        assertNotSame(
            checkerInputs[0].getValue("first").queryValue,
            checkerInputs[1].getValue("first").queryValue,
        )
        assertEquals(if (coroutineResolverSubject.coalescesGroundedKeys) 2 else 4, sharedResolverCalls.get())
        assertEquals(0, sharedCheckerCalls.get())
    }

    @Test
    fun `checker-only raw demand restores checks at a nested resolver boundary`() {
        val protectedCheckerCalls = AtomicInteger()
        val worldFixture =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      item: Item!
                      shared: Int!
                      protected: Int!
                    }

                    type Item {
                      checked: Int!
                    }
                    """.trimIndent(),
                selectiveResolvers = coroutineResolverSubject.selectiveResolvers,
                fieldResolvers = { schema ->
                    val item = schema.loweredSchema.requireObjectField("Query", "item")
                    val shared = schema.loweredSchema.requireObjectField("Query", "shared")
                    val protected = schema.loweredSchema.requireObjectField("Query", "protected")
                    val checked = schema.loweredSchema.requireObjectField("Item", "checked")
                    mapOf(
                        item to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                                schema.loweredSchema.objectOf("Item")
                            },
                        shared to
                            fieldResolverOf(
                                schema.fragmentFrom("fragment Input on Query { protected }"),
                            ) { input, _ -> input.outputValue("protected") },
                        protected to fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 3 },
                        checked to fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Item")) { _, _ -> 1 },
                    )
                },
                fieldCheckers = { schema ->
                    val query = schema.loweredSchema.requireQueryTypeDef()
                    val checked = schema.loweredSchema.requireObjectField("Item", "checked")
                    val protected = schema.loweredSchema.requireObjectField("Query", "protected")
                    mapOf(
                        checked to
                            FieldCheckerResolver.of(
                                field = checked,
                                queryType = query,
                                fragmentTemplates =
                                    mapOf(
                                        "raw" to
                                            ResolverFragmentTemplates(
                                                objectFragmentTemplate = materializeSelectionForestOf(),
                                                queryFragmentTemplate =
                                                    schema
                                                        .fragmentFrom("fragment Input on Query { shared }")
                                                        .materializeSelections,
                                            ),
                                    ),
                            ) { _, inputs, _ ->
                                assertEquals(3, inputs.getValue("raw").queryValue.get("shared"))
                                CheckerResult.Success
                            },
                        protected to
                            FieldCheckerResolver.of(protected, query) { _, _, _ ->
                                protectedCheckerCalls.incrementAndGet()
                                CheckerResult.Success
                            },
                    )
                },
            )
        val world = worldFixture.assumptions

        coroutineResolverSubject.resolve(
            SharedOperationContext.create(world),
            worldFixture.schemas.operationSelectionsFrom("{ item { checked } }"),
        )

        assertEquals(1, protectedCheckerCalls.get())
    }

    @Test
    fun `object and Query inputs read exact value slots as the field checker`() {
        val writers = ConcurrentHashMap<CycleSlot, CycleTask>()
        val reads = Collections.synchronizedList(mutableListOf<Pair<CycleTask, CycleSlot>>())
        val cycleChecker =
            object : CycleCheckState {
                override fun registerWriter(
                    slot: CycleSlot,
                    writer: CycleTask,
                ) {
                    assertEquals(null, writers.putIfAbsent(slot, writer))
                }

                override fun cycleCheck(
                    reader: CycleTask,
                    slot: CycleSlot,
                ) {
                    reads += reader to slot
                }
            }
        val worldFixture = pairedInputWorld()
        val world = worldFixture.assumptions

        val result =
            coroutineResolverSubject.resolve(
                SharedOperationContext.create(world),
                worldFixture.schemas.operationSelectionsFrom("{ item { checked } }"),
                cycleChecker,
            )

        val checkerWriter =
            writers.values.single { writer ->
                writer.kind == CycleTaskKind.FIELD_CHECKER && writer.lastFieldName() == "checked"
            }
        val checkerReads = reads.filter { (reader) -> reader == checkerWriter }
        assertEquals(setOf(CycleSlotKind.VALUE), checkerReads.map { (_, slot) -> slot.kind }.toSet())
        val inputWriters = checkerReads.map { (_, slot) -> writers.getValue(slot) }
        assertEquals(
            setOf("objectDependency", "queryDependency"),
            inputWriters.map { it.lastFieldName() }.toSet(),
        )
        assertSame(
            checkerWriter.root,
            inputWriters.single { it.lastFieldName() == "objectDependency" }.root,
        )
        assertNotSame(
            checkerWriter.root,
            inputWriters.single { it.lastFieldName() == "queryDependency" }.root,
        )
        assertSame(result, checkerWriter.root)
    }

    @Test
    fun `Query input materialization failures complete the checker slot exceptionally`() {
        val failure = IllegalStateException("Query input materialization failed")
        val writers = ConcurrentHashMap<CycleSlot, CycleTask>()
        val cycleChecker =
            object : CycleCheckState {
                override fun registerWriter(
                    slot: CycleSlot,
                    writer: CycleTask,
                ) {
                    writers[slot] = writer
                }

                override fun cycleCheck(
                    reader: CycleTask,
                    slot: CycleSlot,
                ) {
                    val writer = writers.getValue(slot)
                    if (
                        reader.kind == CycleTaskKind.FIELD_CHECKER &&
                        writer.lastFieldName() == "queryDependency"
                    ) {
                        throw failure
                    }
                }
            }
        val worldFixture = pairedInputWorld()
        val world = worldFixture.assumptions

        val result =
            coroutineResolverSubject.resolve(
                SharedOperationContext.create(world),
                worldFixture.schemas.operationSelectionsFrom("{ item { checked } }"),
                cycleChecker,
            )
        val checkedCell = result.objectValue(world, "Query", "item").cell(world, "Item", "checked")

        assertSame(
            failure,
            assertFailsWith<IllegalStateException> {
                checkedCell.fieldCheckerResult.get()
            },
        )
        assertEquals(1, checkedCell.value.get())
    }

    @Test
    fun `request cancellation terminates shared Query work and the checker slot`() =
        runBlocking {
            val producerEntered = CompletableDeferred<Unit>()
            val producerCancelled = CompletableDeferred<Unit>()
            val checkerInvoked = AtomicBoolean()
            val worldFixture = cancellationWorld(producerEntered, producerCancelled, checkerInvoked)
            val world = worldFixture.assumptions
            val requestJob = Job()
            val requestScope = CoroutineScope(coroutineContext + requestJob)
            val cancellation = CancellationException("request cancelled")
            try {
                val result =
                    coroutineResolverSubject.startResolution(
                        SharedOperationContext.create(world),
                        requestScope,
                        worldFixture.schemas.operationSelectionsFrom("{ checked }"),
                        CycleCheckState.create(),
                    )
                withTimeout(5_000) { producerEntered.await() }
                requestJob.cancel(cancellation)
                withTimeout(5_000) { requestJob.join() }
                withTimeout(5_000) { producerCancelled.await() }

                val checkerFailure =
                    assertFailsWith<CancellationException> {
                        result.cell(world, "Query", "checked").fieldCheckerResult.await()
                    }
                assertEquals(cancellation.message, checkerFailure.message)
                assertFalse(checkerInvoked.get())
            } finally {
                requestJob.cancelAndJoin()
            }
        }

    private fun pairedInputWorld(): TestWorld =
        TestWorld.fromDSL(
            schemaSDL =
                """
                extend type Query {
                  item: Item! @resolver(result: {})
                  queryDependency: Int! @resolver(result: 3)
                }

                type Item {
                  checked: Int! @resolver(result: 1)
                  objectDependency: Int! @resolver(result: 2)
                }
                """.trimIndent(),
            selectiveResolvers = coroutineResolverSubject.selectiveResolvers,
            fieldCheckers = { schema ->
                val checked = schema.loweredSchema.requireObjectField("Item", "checked")
                mapOf(
                    checked to
                        FieldCheckerResolver.of(
                            checked,
                            schema.loweredSchema.requireQueryTypeDef(),
                            fragmentTemplates =
                                mapOf(
                                    "input" to
                                        ResolverFragmentTemplates(
                                            objectFragmentTemplate =
                                                schema
                                                    .fragmentFrom(
                                                        "fragment Input on Item { objectDependency }",
                                                    ).materializeSelections,
                                            queryFragmentTemplate =
                                                schema
                                                    .fragmentFrom(
                                                        "fragment Input on Query { queryDependency }",
                                                    ).materializeSelections,
                                        ),
                                ),
                        ) { _, inputs, _ ->
                            assertEquals(2, inputs.getValue("input").objectValue.get("objectDependency"))
                            assertEquals(3, inputs.getValue("input").queryValue.get("queryDependency"))
                            CheckerResult.Success
                        },
                )
            },
        )

    private fun cancellationWorld(
        producerEntered: CompletableDeferred<Unit>,
        producerCancelled: CompletableDeferred<Unit>,
        checkerInvoked: AtomicBoolean,
    ): TestWorld =
        TestWorld.fromSDL(
            schemaSDL = "type Query { checked: Int!, dependency: Int! }",
            selectiveResolvers = coroutineResolverSubject.selectiveResolvers,
            fieldResolvers = { schema ->
                val emptyFragment = schema.loweredSchema.emptyFragmentOf("Query")
                mapOf(
                    schema.loweredSchema.requireObjectField("Query", "checked") to
                        fieldResolverOf(emptyFragment) { _, _ -> 1 },
                    schema.loweredSchema.requireObjectField("Query", "dependency") to
                        fieldResolverOf(emptyFragment) { _, _ ->
                            producerEntered.complete(Unit)
                            try {
                                CompletableDeferred<Nothing>().await()
                            } finally {
                                producerCancelled.complete(Unit)
                            }
                        },
                )
            },
            fieldCheckers = { schema ->
                val checked = schema.loweredSchema.requireObjectField("Query", "checked")
                mapOf(
                    checked to
                        FieldCheckerResolver.of(
                            checked,
                            schema.loweredSchema.requireQueryTypeDef(),
                            fragmentTemplates =
                                mapOf(
                                    "input" to
                                        ResolverFragmentTemplates(
                                            objectFragmentTemplate = materializeSelectionForestOf(),
                                            queryFragmentTemplate =
                                                schema
                                                    .fragmentFrom(
                                                        "fragment Input on Query { dependency }",
                                                    ).materializeSelections,
                                        ),
                                ),
                        ) { _, _, _ ->
                            checkerInvoked.set(true)
                            CheckerResult.Success
                        },
                )
            },
        )
}

private fun CycleTask.lastFieldName(): String = (path.last() as ObjectEngineResult.ObjectKey).field.name

private fun ObjectEngineResult.cell(
    world: viaduct.engine.runtime2.model.Assumptions,
    typeName: String,
    fieldName: String,
): EngineResultCell =
    getCell(
        ObjectEngineResult.GroundKey.of(
            world.schema.requireObjectField(typeName, fieldName),
            emptyMap(),
        ),
    )

private fun ObjectEngineResult.objectValue(
    world: viaduct.engine.runtime2.model.Assumptions,
    typeName: String,
    fieldName: String,
): ObjectEngineResult = assertIs(cell(world, typeName, fieldName).value.get())
