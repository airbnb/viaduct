@file:Suppress("ForbiddenImport")

package semantics.resolver26

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
import model.ListEngineResult
import model.ObjectEngineResult
import model.RootFieldReferenceData
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.parsing.operationSelectionsFrom
import model.registry.FieldCheckerResolver
import model.registry.fieldResolverOf
import model.requireObjectField
import model.requireQueryTypeDef
import model.requireType
import model.selectionForestOf
import model.testing.TestWorld
import semantics.shared.OEROccurrence
import semantics.shared.SharedOperationContext
import viaduct.engine.api.CheckerResult
import viaduct.graphql.schema.ViaductSchema

class OrchestrationTaskTest : Resolver26DispatcherResource {
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
            assertFalse(coroutineContext[kotlinx.coroutines.Job]!!.children.any())

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
            val publications = FieldResolverTask.prepareAll(task)
            try {
                assertEquals(2, publications.size)
                assertEquals(setOf(root, task.queryOER.occurrence.target), publications.map { it.oerOccurrence.target }.toSet())
                assertFalse(coroutineContext[Job]!!.children.any(), "Preparation dispatched coroutine work")
                publications.forEach { publication ->
                    assertFalse(publication.publicationCell.value.isCompleted)
                    assertFailsWith<IllegalStateException> { publication.publicationCell.checkActivated() }
                }
            } finally {
                publications.forEach { FieldResolverTask.cancel(it, CancellationException("Preparation-only test")) }
            }
        }

    @Test
    fun `conditioned passive lists wait for orchestration dispatch and retain checker activation`() =
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
    fun `checker preparation records every claimed slot and delays absence for pending values`() =
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
