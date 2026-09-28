@file:Suppress("ForbiddenImport")

package semantics

import graphql.schema.GraphQLTypeUtil
import model.requireQueryTypeDef
import model.requireType
import model.requireObjectField
import semantics.contract.selectionValues
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import model.Arguments
import model.EngineErrorData
import model.EngineIDResult
import model.ErrorEngineResult
import model.InclusionCondition
import model.ListEngineResult
import model.MaterializeSelection
import model.ObjectEngineResult
import model.ResolverOccurrenceId
import model.outputType
import model.outputValue
import model.PathComponent
import viaduct.graphql.schema.ViaductSchema
import model.fragmentFrom
import viaduct.graphql.schema.graphqljava.gjDef
import model.materializeSelectionForestOf
import model.testing.TestWorld
import semantics.shared.CycleCheckState
import semantics.shared.fieldCheckerCycleTask
import semantics.shared.fieldResolverCycleTask
import semantics.shared.valueCycleSlot
import semantics.shared.ResolverReadCycleException
import semantics.shared.materializeResult
import semantics.shared.SharedOperationContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.CheckerResultContext

class MaterializeTest {
    @Test
    fun `excluded selections do not read value or checker slots`() =
        runBlocking {
            listOf(false, true).forEach { checked ->
                val world = TestWorld.fromSDL("type Query { value: String! }").assumptions
                val type = world.schema.requireQueryTypeDef()
                val field = world.schema.requireObjectField("Query", "value")
                val key = ObjectEngineResult.GroundKey.of(field, emptyMap())
                val result = ObjectEngineResult.of(type, mutable = true)
                val variable =
                    Arguments.Variable.of(field, "included").instantiate(
                        ResolverOccurrenceId.at(result, listOf(key)),
                    )
                val selections =
                    materializeSelectionForestOf(
                        MaterializeSelection.of(
                            responseKey = "value",
                            key = key,
                            possibleTypes = setOf(type),
                            subselections = materializeSelectionForestOf(),
                            inclusionCondition =
                                InclusionCondition.requires(mapOf(variable to true)),
                        ),
                    )
                val cell = result.reserveCell(key)
                val value = cell.createValuePromise()
                val checker = cell.createFieldCheckerResultPromise()
                cell.setActivated(true)
                val operation = SharedOperationContext.create(world)
                operation.variableBindings.bindVariable(
                    requireNotNull(variable.instanceId),
                    false,
                )

                val materialized =
                    withTimeout(1_000) {
                        result.materializeResult(
                            operation = operation,
                            selections = selections,
                            reader = result.fieldResolverCycleTask(listOf(key)),
                            checked = checked,
                        )
                    }

                assertEquals(emptySet(), materialized.getSelections(), "checked=$checked")
                assertFalse(value.isCompleted, "checked=$checked")
                assertFalse(checker.isCompleted, "checked=$checked")
            }
        }

    @Test
    fun `included selections materialize in checked and raw modes`() =
        runBlocking {
            listOf(false, true).forEach { checked ->
                val world = TestWorld.fromSDL("type Query { value: String! }").assumptions
                val type = world.schema.requireQueryTypeDef()
                val field = world.schema.requireObjectField("Query", "value")
                val key = ObjectEngineResult.GroundKey.of(field, emptyMap())
                val result =
                    ObjectEngineResult.of(
                        type = type,
                        values = mapOf(key to "included"),
                        fieldCheckerResults = mapOf(key to CheckerResult.Success),
                    )
                val variable =
                    Arguments.Variable.of(field, "included").instantiate(
                        ResolverOccurrenceId.at(result, listOf(key)),
                    )
                val selections =
                    materializeSelectionForestOf(
                        MaterializeSelection.of(
                            responseKey = "value",
                            key = key,
                            possibleTypes = setOf(type),
                            subselections = materializeSelectionForestOf(),
                            inclusionCondition =
                                InclusionCondition.requires(mapOf(variable to true)),
                        ),
                    )
                val operation = SharedOperationContext.create(world)
                operation.variableBindings.bindVariable(
                    requireNotNull(variable.instanceId),
                    true,
                )

                val materialized =
                    result.materializeResult(
                        operation = operation,
                        selections = selections,
                        reader = result.fieldResolverCycleTask(listOf(key)),
                        checked = checked,
                    )

                assertEquals("included", materialized.get("value"), "checked=$checked")
            }
        }

    @Test
    fun `materialization awaits a present deferred value`() =
        runBlocking {
            val world =
                TestWorld
                    .fromSDL(
                        """
                        type Query { value: String! }
                        """.trimIndent(),
                    ).assumptions
            val field =
                ObjectEngineResult.GroundKey.of(
                    world.schema.requireObjectField("Query", "value"),
                    emptyMap(),
                )
            val selections =
                world
                    .fragmentFrom("fragment ignored on Query { value }")
                    .materializeSelections
            val result =
                ObjectEngineResult.of(
                    type = world.schema.requireQueryTypeDef(),
                    mutable = true,
                )
            val cell = result.reserveCell(field)
            val promise = cell.createValuePromise()
            val materialized =
                async(start = CoroutineStart.UNDISPATCHED) {
                    result.materializeResult(
                        operation = SharedOperationContext.create(world),
                        selections = selections,
                        reader = result.fieldResolverCycleTask(emptyList()),
                    )
                }

            assertFalse(materialized.isCompleted)
            cell.setActivated(true)
            promise.complete("ready")

            assertEquals(
                "ready",
                materialized.await().get("value"),
            )
        }

    @Test
    fun `materialization rejects an absent value immediately`() {
        val world =
            TestWorld
                .fromSDL(
                    """
                    type Query { value: String! }
                    """.trimIndent(),
                ).assumptions
        val selections =
            world
                .fragmentFrom("fragment ignored on Query { value }")
                .materializeSelections
        val result = ObjectEngineResult.of(world.schema.requireQueryTypeDef())

        assertFailsWith<NoSuchElementException> {
            runBlocking {
                result.materializeResult(
                    operation = SharedOperationContext.create(world),
                    selections = selections,
                    reader = result.fieldResolverCycleTask(emptyList()),
                )
            }
        }
    }

    @Test
    fun `nested materialization checks a cycle before awaiting`() {
        val world =
            TestWorld
                .fromSDL(
                    """
                    type Query { child: Child! }
                    type Child { value: String! }
                    """.trimIndent(),
                ).assumptions
        val childType = world.schema.requireType("Child") as ViaductSchema.Object
        val childKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.requireObjectField("Query", "child"),
                emptyMap(),
            )
        val valueKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.requireObjectField("Child", "value"),
                emptyMap(),
            )
        val reader: List<PathComponent> = listOf(childKey, valueKey)
        val childResult = ObjectEngineResult.of(childType, mutable = true)
        val valueCell = childResult.reserveCell(valueKey)
        valueCell.createValuePromise()
        val result =
            ObjectEngineResult.of(
                type = world.schema.requireQueryTypeDef(),
                values = mapOf(childKey to childResult),
            )
        val selections =
            world
                .fragmentFrom("fragment ignored on Query { child { value } }")
                .materializeSelections
        val cycleChecker = CycleCheckState.create()
        val readerIdentity = result.fieldResolverCycleTask(reader)
        cycleChecker.registerWriter(
            slot = valueCell.valueCycleSlot,
            writer = readerIdentity,
        )

        val failure =
            assertFailsWith<ResolverReadCycleException> {
                runBlocking {
                    result.materializeResult(
                        operation = SharedOperationContext.create(world),
                        cycleChecker = cycleChecker,
                        selections = selections,
                        reader = readerIdentity,
                    )
                }
            }

        assertEquals(listOf(readerIdentity, readerIdentity), failure.cycle)
    }

    @Test
    fun `Node fields materialize at their source coordinates`() =
        runBlocking {
            val world =
                TestWorld
                    .fromSDL(
                        """
                        interface Node {
                          id: ID!
                        }

                        type User implements Node {
                          id: ID!
                        }

                        type Parent {
                          user: Node!
                          users: [Node]!
                        }

                        type Query {
                          parent: Parent!
                        }
                        """.trimIndent(),
                    ).assumptions
            val parent = world.schema.requireType("Parent") as ViaductSchema.Object
            val user = world.schema.requireType("User") as ViaductSchema.Object
            val producer =
                ObjectEngineResult.GroundKey.of(
                    world.schema.requireObjectField("Parent", "user"),
                    emptyMap(),
                )
            val listProducer =
                ObjectEngineResult.GroundKey.of(
                    world.schema.requireObjectField("Parent", "users"),
                    emptyMap(),
                )
            val id =
                ObjectEngineResult.GroundKey.of(
                    world.schema.requireObjectField("User", "id"),
                    emptyMap(),
                )
            val userResult =
                ObjectEngineResult.of(
                    type = user,
                    values = mapOf(id to EngineIDResult.of("user-1")),
                )
            val errorData = EngineErrorData.of()
            val parentResult =
                ObjectEngineResult.of(
                    type = parent,
                    values =
                        mapOf(
                            producer to userResult,
                            listProducer to
                                ListEngineResult.of(
                                    typeExpr = listProducer.field.outputType.unwrapList()!!,
                                    values =
                                        listOf(
                                            userResult,
                                            null,
                                            ErrorEngineResult.of(errorData),
                                        ),
                                ),
                        ),
                )
            val selections =
                world
                    .fragmentFrom(
                        "fragment ParentInput on Parent { user { id } users { id } }",
                    )
                    .materializeSelections

            val materialized =
                parentResult.materializeResult(
                    SharedOperationContext.create(world),
                    selections,
                    parentResult.fieldResolverCycleTask(emptyList()),
                )

            assertSame(parent.gjDef, materialized.type)
            assertNotNull(materialized.type.getFieldDefinition("user"))
            assertNull(materialized.type.getFieldDefinition("user_V_A_node"))
            val nested = assertIs<EngineObjectData.Sync>(materialized.get("user"))
            assertEquals("User", nested.type.name)
            assertSame(user.gjDef, nested.type)
            assertEquals("user-1", nested.get("id"))
            assertEquals(
                "Node",
                GraphQLTypeUtil
                    .unwrapAll(materialized.type.getFieldDefinition("user").type)
                    .name,
            )

            val users = assertIs<List<*>>(materialized.outputValue("users"))
            assertEquals(3, users.size)
            val listedUser = assertIs<EngineObjectData.Sync>(users[0])
            assertSame(user.gjDef, listedUser.type)
            assertEquals("user-1", listedUser.get("id"))
            assertNull(users[1])
            assertSame(errorData, users[2])
        }

    @Test
    fun `distinct response aliases can read one exact stored key`() =
        runBlocking {
            val world =
                TestWorld
                    .fromSDL(
                        """
                        type Query { value: String! }
                        """.trimIndent(),
                    ).assumptions
            val field = world.schema.requireObjectField("Query", "value")
            val storedKey = ObjectEngineResult.GroundKey.of(field, emptyMap())
            val selections =
                materializeSelectionForestOf(
                    MaterializeSelection.of(
                        responseKey = "first",
                        key = storedKey,
                        possibleTypes = setOf(world.schema.requireQueryTypeDef()),
                        subselections = materializeSelectionForestOf(),
                    ),
                    MaterializeSelection.of(
                        responseKey = "second",
                        key = storedKey,
                        possibleTypes = setOf(world.schema.requireQueryTypeDef()),
                        subselections = materializeSelectionForestOf(),
                    ),
                )
            val result =
                ObjectEngineResult.of(
                    type = world.schema.requireQueryTypeDef(),
                    values = mapOf(storedKey to "same"),
                )

            val materialized =
                result.materializeResult(
                    SharedOperationContext.create(world),
                    selections,
                    result.fieldResolverCycleTask(emptyList()),
                )

            assertEquals(setOf("first", "second"), materialized.selectionValues().keys)
            assertEquals("same", materialized.selectionValues().getValue("first"))
            assertEquals("same", materialized.selectionValues().getValue("second"))
        }

    @Test
    fun `missing field checker slot defaults materialization open`() =
        runBlocking {
            val world =
                TestWorld.fromSDL("type Query { value: String! }").assumptions
            val key =
                ObjectEngineResult.GroundKey.of(
                    world.schema.requireObjectField("Query", "value"),
                    emptyMap(),
                )
            val result =
                ObjectEngineResult.of(
                    type = world.schema.requireQueryTypeDef(),
                    values = mapOf(key to "open"),
                    fieldCheckerResults = emptyMap(),
                )
            val selections =
                world
                    .fragmentFrom("fragment ignored on Query { value }")
                    .materializeSelections

            val materialized =
                result.materializeResult(
                    operation = SharedOperationContext.create(world),
                    selections = selections,
                    reader = result.fieldResolverCycleTask(emptyList()),
                )

            assertFalse(result.getCell(key).isFieldCheckerResultSet())
            assertEquals("open", materialized.get("value"))
        }

    @Test
    fun `present field checker slot is enforced without a policy flag`() =
        runBlocking {
            val world =
                TestWorld.fromSDL("type Query { value: String! }").assumptions
            val key =
                ObjectEngineResult.GroundKey.of(
                    world.schema.requireObjectField("Query", "value"),
                    emptyMap(),
                )
            val denial = MaterializationDenial()
            val result =
                ObjectEngineResult.of(
                    type = world.schema.requireQueryTypeDef(),
                    values = mapOf(key to "denied"),
                    fieldCheckerResults = mapOf(key to denial),
                )
            val selections =
                world
                    .fragmentFrom("fragment ignored on Query { value }")
                    .materializeSelections

            val materialized =
                result.materializeResult(
                    operation = SharedOperationContext.create(world),
                    selections = selections,
                    reader = result.fieldResolverCycleTask(emptyList()),
                )

            assertSame(denial.error, assertIs<EngineErrorData>(materialized.outputValue("value")).cause)
        }

    @Test
    fun `checker denial completes without awaiting the raw value`() =
        runBlocking {
            val world =
                TestWorld
                    .fromSDL(
                        """
                        type Query { value: String! }
                        """.trimIndent(),
                    ).assumptions
            val key =
                ObjectEngineResult.GroundKey.of(
                    world.schema.requireObjectField("Query", "value"),
                    emptyMap(),
                )
            val denial = MaterializationDenial()
            val result = ObjectEngineResult.of(world.schema.requireQueryTypeDef(), mutable = true)
            val cell = result.reserveCell(key)
            cell.reserveValue()
            val checkerPromise = cell.createFieldCheckerResultPromise()
            cell.setActivated(true)
            val selections =
                world
                    .fragmentFrom("fragment ignored on Query { value }")
                    .materializeSelections

            val materialized =
                async(start = CoroutineStart.UNDISPATCHED) {
                    result.materializeResult(
                        operation = SharedOperationContext.create(world),
                        selections = selections,
                        reader = result.fieldResolverCycleTask(emptyList()),
                    )
                }
            assertFalse(materialized.isCompleted)

            checkerPromise.complete(denial)
            val completed = withTimeout(1_000) { materialized.await() }

            assertSame(
                denial.error,
                assertIs<EngineErrorData>(completed.outputValue("value")).cause,
            )
            assertFalse(cell.getValue().isCompleted)
        }

    @Test
    fun `raw materialization skips unfinished checker slots`() =
        runBlocking {
            val world =
                TestWorld.fromSDL("type Query { value: String! }").assumptions
            val key =
                ObjectEngineResult.GroundKey.of(
                    world.schema.requireObjectField("Query", "value"),
                    emptyMap(),
                )
            val result = ObjectEngineResult.of(world.schema.requireQueryTypeDef(), mutable = true)
            val cell = result.reserveCell(key)
            cell.setValue("raw")
            val fieldChecker = cell.createFieldCheckerResultPromise()
            val typeChecker = cell.createTypeCheckerResultPromise()
            val selections =
                world
                    .fragmentFrom("fragment ignored on Query { value }")
                    .materializeSelections

            val materialized =
                withTimeout(1_000) {
                    result.materializeResult(
                        operation = SharedOperationContext.create(world),
                        selections = selections,
                        reader = result.fieldCheckerCycleTask(listOf(key)),
                        checked = false,
                    )
                }

            assertEquals("raw", materialized.get("value"))
            assertFalse(fieldChecker.isCompleted)
            assertFalse(typeChecker.isCompleted)
        }

    @Test
    fun `raw materialization cycle-checks the value slot`() {
        val world = TestWorld.fromSDL("type Query { value: String! }").assumptions
        val key =
            ObjectEngineResult.GroundKey.of(
                world.schema.requireObjectField("Query", "value"),
                emptyMap(),
            )
        val result = ObjectEngineResult.of(world.schema.requireQueryTypeDef(), mutable = true)
        val cell = result.reserveCell(key)
        cell.reserveValue()
        val checker = cell.createFieldCheckerResultPromise()
        val reader = result.fieldCheckerCycleTask(listOf(key))
        val cycleChecker = CycleCheckState.create()
        cycleChecker.registerWriter(cell.valueCycleSlot, reader)
        val selections =
            world
                .fragmentFrom("fragment ignored on Query { value }")
                .materializeSelections

        assertFailsWith<ResolverReadCycleException> {
            runBlocking {
                result.materializeResult(
                    operation = SharedOperationContext.create(world),
                    selections = selections,
                    reader = reader,
                    cycleChecker = cycleChecker,
                    checked = false,
                )
            }
        }
        assertFalse(checker.isCompleted)
    }

    @Test
    fun `raw materialization remains raw through nested objects and lists`() =
        runBlocking {
            val world =
                TestWorld
                    .fromSDL(
                        """
                        type Query { values: [Value!]! }
                        type Value { text: String! }
                        """.trimIndent(),
                    ).assumptions
            val valueType = world.schema.requireType("Value") as ViaductSchema.Object
            val textKey =
                ObjectEngineResult.GroundKey.of(
                    world.schema.requireObjectField("Value", "text"),
                    emptyMap(),
                )
            val deniedText =
                ObjectEngineResult.of(
                    type = valueType,
                    values = mapOf(textKey to "raw nested"),
                    fieldCheckerResults = mapOf(textKey to MaterializationDenial()),
                )
            val valuesKey =
                ObjectEngineResult.GroundKey.of(
                    world.schema.requireObjectField("Query", "values"),
                    emptyMap(),
                )
            val values =
                ListEngineResult.of(
                    typeExpr = valuesKey.field.outputType.unwrapList()!!,
                    values = listOf(deniedText),
                    typeCheckerResults = listOf(MaterializationDenial()),
                )
            val result =
                ObjectEngineResult.of(
                    type = world.schema.requireQueryTypeDef(),
                    values = mapOf(valuesKey to values),
                )
            val selections =
                world
                    .fragmentFrom("fragment ignored on Query { values { text } }")
                    .materializeSelections

            val materialized =
                result.materializeResult(
                    operation = SharedOperationContext.create(world),
                    selections = selections,
                    reader = result.fieldCheckerCycleTask(listOf(valuesKey)),
                    checked = false,
                )

            val listedValues = assertIs<List<*>>(materialized.outputValue("values"))
            val nested = assertIs<EngineObjectData.Sync>(listedValues.single())
            assertEquals("raw nested", nested.get("text"))
        }

}

private class MaterializationDenial : CheckerResult.Error {
    override val error: Exception = IllegalStateException("denied")

    override fun isErrorForResolver(ctx: CheckerResultContext): Boolean = true

    override fun combine(fieldResult: CheckerResult.Error): CheckerResult.Error = this
}
