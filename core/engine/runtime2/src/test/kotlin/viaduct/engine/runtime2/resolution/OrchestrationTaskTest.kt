@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolution

import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import viaduct.engine.api.CheckerResult
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.ListEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.RootFieldReferenceData
import viaduct.engine.runtime2.model.Selection
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.engineObjectDataOf
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.registry.FieldCheckerResolver
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.selectionForestOf
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.emptyFragmentOf
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.testing.objectOf
import viaduct.engine.runtime2.resolution.framework.Demand
import viaduct.engine.runtime2.resolution.framework.OEROccurrence
import viaduct.engine.runtime2.resolution.framework.OrchestrationConstructionDemand
import viaduct.engine.runtime2.resolution.framework.ResolutionObserver
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolution.framework.SharedOrchestrationTask
import viaduct.engine.runtime2.schema.operationSelectionsFrom
import viaduct.graphql.schema.ViaductSchema

class OrchestrationTaskTest : ResolutionDispatcherResource {
    @ParameterizedTest
    @CsvSource("false, false", "true, false", "false, true", "true, true")
    fun `Query fragment subscriptions are checked once and independently during preparation`(
        observeFragments: Boolean,
        observeAddresses: Boolean,
    ): Unit =
        runBlocking {
            var queryOERs = 0
            var fragmentSubscriptions = 0
            var addressSubscriptions = 0
            var fragments = 0
            var addresses = 0
            val observer = object : ResolutionObserver {
                override val onQueryOERPrepared: (SharedOrchestrationTask<*>, Int?) -> Unit = { _, _ -> queryOERs++ }
                override val onQueryFragmentPrepared: ((ResolverOccurrenceId, ObjectEngineResult, OEROccurrence?) -> Unit)?
                    get() {
                        fragmentSubscriptions++
                        return if (observeFragments) {
                            { _, _, _ -> fragments++ }
                        } else {
                            null
                        }
                    }
                override val onQueryFragmentOwnerAddress: ((ResolverOccurrenceId, OEROccurrence, ObjectEngineResult.ObjectKey) -> Unit)?
                    get() {
                        addressSubscriptions++
                        return if (observeAddresses) {
                            { _, _, _ -> addresses++ }
                        } else {
                            null
                        }
                    }
            }
            val fixture = TestWorld.fromSDL(
                "type Query { first: Int second: Int dependency: Int }",
                fieldResolvers = { schema ->
                    val empty = schema.loweredSchema.emptyFragmentOf("Query")
                    val query = schema.fragmentFrom("fragment Input on Query { dependency }")
                    listOf("first", "second").associate { name ->
                        schema.loweredSchema.requireObjectField("Query", name) to fieldResolverOf(empty, query) { _, _, _ ->
                            error("Preparation must not invoke resolvers")
                        }
                    }
                },
            )
            val world = fixture.assumptions
            val operation = OperationContext.create(SharedOperationContext.create(world, resolutionObserver = observer), this)
            val root = ObjectEngineResult.of(world.schema.requireQueryTypeDef(), mutable = true)

            OrchestrationTask.create(
                operation,
                OEROccurrence(root, emptyList(), root),
                world.resolverRegistry.createRootQueryInput(),
                fixture.schemas.operationSelectionsFrom("{ first second }"),
            )

            assertEquals(1, queryOERs)
            assertEquals(1, fragmentSubscriptions)
            assertEquals(1, addressSubscriptions)
            assertEquals(if (observeFragments) 2 else 0, fragments)
            assertEquals(if (observeAddresses) 2 else 0, addresses)
        }

    @Test
    fun `passive object orchestrations retain the producing field as creator without observations`() {
        val tasks = ConcurrentHashMap<List<String>, FieldResolverTask>()
        val fixture = TestWorld.fromSDL(
            "type Query { item: Item } type Item { child: Item value: Int }",
            fieldResolvers = { schemas ->
                val schema = schemas.loweredSchema
                val itemType = schema.requireType("Item") as ViaductSchema.Object
                mapOf(
                    schema.requireObjectField("Query", "item") to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _, ctx ->
                        tasks[emptyList()] = assertIs<FieldResolverTask>(ctx)
                        engineObjectDataOf(itemType, mapOf("child" to engineObjectDataOf(itemType)))
                    },
                    schema.requireObjectField("Item", "value") to fieldResolverOf(schema.emptyFragmentOf("Item")) { _, _, ctx ->
                        val task = assertIs<FieldResolverTask>(ctx)
                        tasks[task.publication.oerOccurrence.path.map { (it as ObjectEngineResult.ObjectKey).field.name }] = task
                        7
                    },
                )
            },
        )
        val result = SharedOperationContext.create(fixture.assumptions).resolveWithTestDispatcher(
            fixture.schemas.operationSelectionsFrom("{ item { value child { value } } }"),
        )
        assertTrue(result.keys.all { result.getCell(it).value.isCompleted })
        assertEquals(3, tasks.size)
        val producer = tasks.getValue(emptyList())
        val root = assertIs<OrchestrationTask>(producer.creator)
        assertNull(root.creator)
        val item = assertIs<OrchestrationTask>(tasks.getValue(listOf("item")).creator)
        val child = assertIs<OrchestrationTask>(tasks.getValue(listOf("item", "child")).creator)
        assertSame(producer, item.creator)
        assertSame(producer, child.creator)
        tasks.values.forEach { assertSame(it, it.publication.task) }
    }

    @Test
    fun `closed value projection preserves independently guarded checked and raw descendants`() {
        val fixture = TestWorld.fromSDL("type Query { item: Item } type Item { checked: Int raw: Int }")
        val world = fixture.assumptions
        val queryType = world.schema.requireQueryTypeDef()
        val itemType = world.schema.requireType("Item") as ViaductSchema.Object
        val itemField = world.schema.requireObjectField("Query", "item")
        val checkedVariable = Arguments.Variable.of(itemField, "checked")
        val rawVariable = Arguments.Variable.of(itemField, "raw")
        val checkedCondition = InclusionCondition.requires(mapOf(checkedVariable to true))
        val rawCondition = InclusionCondition.requires(mapOf(rawVariable to true))
        val key = ObjectEngineResult.GroundKey.of(itemField, emptyMap())
        val checkedSelection = Selection.of(
            key,
            setOf(queryType),
            fixture.schemas.fragmentFrom("fragment Input on Item { checked }").subselections,
            checkedCondition,
        )
        val rawSelection = Selection.of(
            key,
            setOf(queryType),
            fixture.schemas.fragmentFrom("fragment Input on Item { raw }").subselections,
            rawCondition,
        )
        val root = ObjectEngineResult.of(queryType, mutable = true)
        val queryRoot = ObjectEngineResult.of(queryType, mutable = true)
        val closed = world.objectOf("Query").closeOrchestrationConstructionDemand(
            world,
            OEROccurrence(root, emptyList(), root),
            OEROccurrence(queryRoot, emptyList(), queryRoot),
            OrchestrationConstructionDemand(
                Demand(selectionForestOf(checkedSelection), selectionForestOf(rawSelection), typeCheckDemanded = true),
                Demand.EMPTY,
            ),
            engineExecutionContext = null,
        ).objectRooted

        assertEquals(setOf("checked"), closed.constructionDemand.checked[key].subselections.merge(itemType).keys().map { it.field.name }.toSet())
        assertEquals(setOf("raw"), closed.constructionDemand.unchecked[key].subselections.merge(itemType).keys().map { it.field.name }.toSet())
        assertTrue(closed.constructionDemand.typeCheckDemanded)
        val valueSelection = closed.closedValueSelections[key]
        val children = valueSelection.subselections.merge(itemType).byKey().values.associateBy { it.key.field.name }
        assertEquals(setOf("checked", "raw"), children.keys)
        for (checked in listOf(false, true)) {
            for (raw in listOf(false, true)) {
                val bindings = mapOf(checkedVariable to checked, rawVariable to raw)
                assertEquals(checked || raw, valueSelection.inclusionCondition.include(bindings))
                assertEquals(checked, children.getValue("checked").inclusionCondition.include(bindings))
                assertEquals(raw, children.getValue("raw").inclusionCondition.include(bindings))
            }
        }
    }

    @Test
    fun `factory closes demand without dispatching field work`(): Unit =
        runBlocking {
            val worldFixture = TestWorld
                .fromDSL(
                    schemaSDL = """
                extend type Query {
                  first: Int! @resolver(result: 7)
                  second: Int! @resolver(of: "first", result: "sum(first)")
                }
                    """.trimIndent(),
                )
            val world = worldFixture.assumptions
            val base = SharedOperationContext.create(world)
            val operation = OperationContext.create(
                base = base,
                requestScope = this,
            )
            val root = ObjectEngineResult.of(world.schema.requireQueryTypeDef(), mutable = true)
            val task = OrchestrationTask.create(
                operation,
                OEROccurrence(root, emptyList(), root),
                world.resolverRegistry.createRootQueryInput(),
                worldFixture.schemas.operationSelectionsFrom("{ second }"),
            )
            assertEquals(
                setOf("first", "second"),
                task.objectOER.closedValueSelections
                    .byKey()
                    .keys
                    .map { it.field.name }
                    .toSet()
            )
            assertEquals(setOf("first", "second"), root.keys.map { it.field.name }.toSet())
            assertTrue(root.keys.all { !root.getCell(it).fieldCheckerResult.isCompleted })
            assertFalse(coroutineContext[Job]!!.children.any())

            assertSame(operation, task.operation)
            task.operation.dispatcher.dispatchOrchestration(task)
            val key = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", "second"), emptyMap())
            assertEquals(7, root.getCell(key).value.await())
            assertFailsWith<IllegalArgumentException> { operation.dispatcher.dispatchOrchestration(task) }
        }

    @Test
    fun `value preparation claims both roots without dispatching or deciding activation`(): Unit =
        runBlocking {
            val worldFixture = TestWorld.fromSDL(
                "type Query { consumer: Int!, dependency: Int! }",
                fieldResolvers = { schema ->
                    val empty = schema.loweredSchema.emptyFragmentOf("Query")
                    mapOf(
                        schema.loweredSchema.requireObjectField("Query", "consumer") to fieldResolverOf(
                            objectFragment = empty,
                            queryFragment = schema.fragmentFrom("fragment Input on Query { dependency }"),
                        ) { _, _, _ -> error("Preparation must not invoke the consumer") },
                        schema.loweredSchema.requireObjectField("Query", "dependency") to fieldResolverOf(empty) { _, _ ->
                            error("Preparation must not invoke the dependency")
                        },
                    )
                },
            )
            val world = worldFixture.assumptions
            val operation = OperationContext.create(SharedOperationContext.create(world), this)
            val root = ObjectEngineResult.of(world.schema.requireQueryTypeDef(), mutable = true)
            val task = OrchestrationTask.create(
                operation,
                OEROccurrence(root, emptyList(), root),
                world.resolverRegistry.createRootQueryInput(),
                worldFixture.schemas.operationSelectionsFrom("{ consumer }"),
            )
            assertNull(task.creator)
            val publications = FieldResolverTask.prepareAll(task)
            try {
                assertEquals(2, publications.size)
                assertEquals(setOf(root, task.queryOER.occurrence.target), publications.map { it.oerOccurrence.target }.toSet())
                assertFalse(coroutineContext[Job]!!.children.any(), "Preparation dispatched coroutine work")
                publications.forEach { publication ->
                    assertSame(task, publication.task.creator)
                    assertSame(publication, publication.task.publication)
                    assertFalse(publication.publicationCell.value.isCompleted)
                    assertFailsWith<IllegalStateException> { publication.publicationCell.checkActivated() }
                }
            } finally {
                publications.forEach { FieldResolverTask.cancel(it, CancellationException("Preparation-only test")) }
            }
        }

    @Test
    fun `conditioned passive lists wait for orchestration dispatch and retain checker activation`(): Unit =
        runBlocking {
            for (enabled in listOf(false, true)) {
                for (withChecker in listOf(false, true)) {
                    val worldFixture = TestWorld.fromDSL(
                        """
                        extend type Query {
                          outer: Int! @resolver(of: "values @include(if: ${'$'}enabled)", providerVars: {enabled: $enabled}, result: 1)
                          values: [Int!]!
                          target: Int! @resolver(result: 7)
                        }
                        """.trimIndent(),
                        fieldCheckers = { schema ->
                            if (withChecker) {
                                val values = schema.loweredSchema.requireObjectField("Query", "values")
                                mapOf(values to FieldCheckerResolver.of(values, schema.loweredSchema.requireQueryTypeDef()) { _, _, _ -> CheckerResult.Success })
                            } else {
                                emptyMap()
                            }
                        },
                    )
                    val world = worldFixture.assumptions
                    val operation = OperationContext.create(SharedOperationContext.create(world), this)
                    val root = ObjectEngineResult.of(world.schema.requireQueryTypeDef(), mutable = true)
                    val reference = RootFieldReferenceData.of(listOf(world.schema.requireObjectField("Query", "target")), emptyMap())
                    val source = world.objectOf("Query") { "values" setTo listOf(reference) }
                    val task = OrchestrationTask.create(
                        operation,
                        OEROccurrence(root, emptyList(), root),
                        source,
                        worldFixture.schemas.operationSelectionsFrom("{ outer }"),
                    )
                    PassiveValueResolutionLogic(operation).materializePassiveFields(
                        task,
                        task.closedConstructionDemand.objectRooted.constructionDemand,
                        task.objectOER.closedValueSelections,
                    )
                    val valuesKey = root.keys.single { it.field.name == "values" }
                    val cell = root.getCell(valuesKey)
                    assertFalse(coroutineContext[Job]!!.children.any(), "Passive descent dispatched field work")
                    assertFalse(cell.value.isCompleted)
                    assertFailsWith<IllegalStateException> { cell.checkActivated() }
                    assertFalse(cell.fieldCheckerResult.isCompleted)
                    operation.dispatcher.dispatchOrchestration(task)
                    assertEquals(enabled, cell.fetchActivated())
                    if (enabled) {
                        val values = assertIs<ListEngineResult>(cell.value.await())
                        assertEquals(7, values[0].value.await())
                        assertEquals(if (withChecker) CheckerResult.Success else null, cell.fieldCheckerResult.await())
                    }
                    coroutineContext[Job]!!.children.toList().forEach { it.join() }
                }
            }
        }

    @Test
    fun `checker preparation records every claimed slot and delays absence for pending values`(): Unit =
        runBlocking {
            val worldFixture = TestWorld.fromDSL(
                """
                extend type Query {
                  checked: Int! @resolver(result: 1)
                  pending: Int! @resolver(result: 2)
                  passive: Int!
                }
                """.trimIndent(),
                fieldCheckers = { schema ->
                    val checked = schema.loweredSchema.requireObjectField("Query", "checked")
                    mapOf(checked to FieldCheckerResolver.of(checked, schema.loweredSchema.requireQueryTypeDef()) { _, _, _ -> CheckerResult.Success })
                },
            )
            val world = worldFixture.assumptions
            val operation = OperationContext.create(SharedOperationContext.create(world), this)
            val root = ObjectEngineResult.of(world.schema.requireQueryTypeDef(), mutable = true)
            val source = world.objectOf("Query") { "passive" setTo 3 }
            val task = OrchestrationTask.create(
                operation,
                OEROccurrence(root, emptyList(), root),
                source,
                worldFixture.schemas.operationSelectionsFrom("{ checked pending passive }"),
            )
            val preparation = task.checkerPreparation
            assertEquals(3, preparation.claimedSlots.size)
            assertEquals(1, preparation.executablePublications.size)
            assertEquals(2, preparation.delayedAbsenceSlots.size)
            assertTrue(preparation.claimedSlots.all { !it.cell.fieldCheckerResult.isCompleted })
            PassiveValueResolutionLogic(operation).materializePassiveFields(
                task,
                task.closedConstructionDemand.objectRooted.constructionDemand,
                task.objectOER.closedValueSelections,
            )
            operation.dispatcher.dispatchOrchestration(task)
            val passive = root.getCell(root.keys.single { it.field.name == "passive" })
            val pending = root.getCell(root.keys.single { it.field.name == "pending" })
            assertNull(passive.fieldCheckerResult.get())
            assertFalse(pending.fieldCheckerResult.isCompleted)
            assertFalse(pending.value.isCompleted)
            assertEquals(2, pending.value.await())
            assertNull(pending.fieldCheckerResult.await())
        }

    @Test
    fun `object orchestration validates source and target types at construction`(): Unit =
        runBlocking(resolverDispatcher) {
            coroutineScope {
                val worldFixture = TestWorld
                    .fromSDL(
                        """
                            type Query {
                              item: Item
                            }

                            type Item {
                              value: Int
                            }
                        """.trimIndent(),
                    )
                val world = worldFixture.assumptions
                val baseOperation = SharedOperationContext.create(world)
                val operation =
                    OperationContext.create(
                        base = baseOperation,
                        requestScope = this,
                    )
                val root =
                    ObjectEngineResult.of(
                        world.schema.requireQueryTypeDef(),
                        mutable = true,
                    )
                val target =
                    ObjectEngineResult.of(
                        world.schema.requireType("Item") as ViaductSchema.Object,
                        mutable = true,
                    )

                assertFailsWith<IllegalArgumentException> {
                    OrchestrationTask.create(
                        operation = operation,
                        occurrence =
                            OEROccurrence(
                                root = root,
                                path =
                                    listOf(
                                        ObjectEngineResult.GroundKey.of(
                                            world.schema.requireObjectField("Query", "item"),
                                            emptyMap(),
                                        ),
                                    ),
                                target = target,
                            ),
                        source = world.resolverRegistry.createRootQueryInput(),
                        constructionDemand = selectionForestOf(),
                    )
                }
            }
        }
}
