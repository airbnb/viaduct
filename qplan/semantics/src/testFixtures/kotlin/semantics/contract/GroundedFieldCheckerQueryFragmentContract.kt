@file:Suppress("ForbiddenImport")

package semantics.contract

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
import model.Arguments
import model.EngineResultCell
import model.ObjectEngineResult
import model.arg
import model.emptyFragmentOf
import model.fragmentFrom
import model.materializeSelectionForestOf
import model.objectOf
import model.operationSelectionsFrom
import model.outputValue
import model.registry.CheckerInput
import model.registry.FieldCheckerResolver
import model.registry.ResolverFragmentTemplates
import model.registry.ResolverTarget
import model.registry.VariableDefinition
import model.requireObjectField
import model.requireQueryTypeDef
import model.testing.TestWorld
import model.testing.fieldResolverOf
import semantics.shared.CycleCheckState
import semantics.shared.CycleSlot
import semantics.shared.CycleSlotKind
import semantics.shared.CycleTask
import semantics.shared.CycleTaskKind
import semantics.shared.ResolverInvocationObservation
import semantics.shared.ResolverObserver
import semantics.shared.SharedOERContext
import semantics.shared.SharedOperationContext
import viaduct.engine.api.CheckerResult

/** Owner-local raw checker projections from the orchestration's shared Query OER. */
interface GroundedFieldCheckerQueryFragmentContract {
    val coroutineResolverSubject: CoroutineResolverTestSubject

    @Test
    fun `named object and Query inputs honor fromArgument inclusion conditions`() {
        val checkerCalls = AtomicInteger()
        val world =
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
                    val checked = schema.requireObjectField("Item", "checked")
                    val enabled = Arguments.Variable.of(ResolverTarget.FieldCheckerTarget(checked), "enabled")
                    mapOf(
                        checked to
                            FieldCheckerResolver.of(
                                field = checked,
                                queryType = schema.requireQueryTypeDef(),
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
            ).assumptions

        coroutineResolverSubject.resolve(
            SharedOperationContext.create(world),
            world.operationSelectionsFrom(
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
        val world =
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
                    val query = schema.requireQueryTypeDef()
                    val checked = schema.requireObjectField("Item", "checked")
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
                    val shared = schema.requireObjectField("Query", "shared")
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
            ).assumptions
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
            world.operationSelectionsFrom(
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
        val world =
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
                    val item = schema.requireObjectField("Query", "item")
                    val shared = schema.requireObjectField("Query", "shared")
                    val protected = schema.requireObjectField("Query", "protected")
                    val checked = schema.requireObjectField("Item", "checked")
                    mapOf(
                        item to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Item")
                            },
                        shared to
                            fieldResolverOf(
                                schema.fragmentFrom("fragment Input on Query { protected }"),
                            ) { input, _ -> input.outputValue("protected") },
                        protected to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 3 },
                        checked to fieldResolverOf(schema.emptyFragmentOf("Item")) { _, _ -> 1 },
                    )
                },
                fieldCheckers = { schema ->
                    val query = schema.requireQueryTypeDef()
                    val checked = schema.requireObjectField("Item", "checked")
                    val protected = schema.requireObjectField("Query", "protected")
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
            ).assumptions

        coroutineResolverSubject.resolve(
            SharedOperationContext.create(world),
            world.operationSelectionsFrom("{ item { checked } }"),
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
        val world = pairedInputWorld().assumptions

        val result =
            coroutineResolverSubject.resolve(
                SharedOperationContext.create(world),
                world.operationSelectionsFrom("{ item { checked } }"),
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
        val world = pairedInputWorld().assumptions

        val result =
            coroutineResolverSubject.resolve(
                SharedOperationContext.create(world),
                world.operationSelectionsFrom("{ item { checked } }"),
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
            val world =
                cancellationWorld(producerEntered, producerCancelled, checkerInvoked).assumptions
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
                val checked = schema.requireObjectField("Item", "checked")
                mapOf(
                    checked to
                        FieldCheckerResolver.of(
                            checked,
                            schema.requireQueryTypeDef(),
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
                val emptyFragment = schema.emptyFragmentOf("Query")
                mapOf(
                    schema.requireObjectField("Query", "checked") to
                        fieldResolverOf(emptyFragment) { _, _ -> 1 },
                    schema.requireObjectField("Query", "dependency") to
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
                val checked = schema.requireObjectField("Query", "checked")
                mapOf(
                    checked to
                        FieldCheckerResolver.of(
                            checked,
                            schema.requireQueryTypeDef(),
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
    world: model.Assumptions,
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
    world: model.Assumptions,
    typeName: String,
    fieldName: String,
): ObjectEngineResult = assertIs(cell(world, typeName, fieldName).value.get())
