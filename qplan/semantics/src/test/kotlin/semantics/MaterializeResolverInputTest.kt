package semantics

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import model.EngineErrorData
import model.ErrorEngineResult
import model.ListEngineResult
import model.MaterializeSelectionForest
import model.ObjectEngineResult
import model.fragmentFrom
import model.outputType
import model.outputValue
import model.requireObjectField
import model.requireQueryTypeDef
import model.requireType
import model.testing.TestWorld
import semantics.resolvers.materializeResolverInput as materializeResolver01To23Input
import semantics.shared.CycleCheckState
import semantics.shared.CycleTask
import semantics.shared.SharedOperationContext
import semantics.shared.fieldResolverCycleTask
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.CheckerResultContext
import viaduct.engine.api.EngineObjectData

class MaterializeResolverInputTest {
    @Test
    fun `Resolver01-23 input materialization is driven by checker slot presence`() =
        runBlocking {
            assertSlotDrivenMaterialization { result, operation, selections, reader ->
                result.materializeResolver01To23Input(
                    operation = operation,
                    cycleChecker = CycleCheckState.createNOP(),
                    selections = selections,
                    reader = reader,
                )
            }
        }

}

private suspend fun assertSlotDrivenMaterialization(
    materialize: suspend (
        ObjectEngineResult,
        SharedOperationContext<*>,
        MaterializeSelectionForest,
        CycleTask,
    ) -> EngineObjectData.Sync,
) {
    val world =
        TestWorld
            .fromSDL(
                """
                type Query {
                  value: Value!
                  values: [Value!]!
                }

                type Value {
                  text: String!
                }
                """.trimIndent(),
            ).assumptions
    val operation = SharedOperationContext.create(world)
    val valueType = world.schema.requireType("Value")
    require(valueType is viaduct.graphql.schema.ViaductSchema.Object)
    val textKey =
        ObjectEngineResult.GroundKey.of(
            world.schema.requireObjectField("Value", "text"),
            emptyMap(),
        )
    fun valueResult(text: String): ObjectEngineResult =
        ObjectEngineResult.of(
            type = valueType,
            values = mapOf(textKey to text),
        )
    val key =
        ObjectEngineResult.GroundKey.of(
            world.schema.requireObjectField("Query", "value"),
            emptyMap(),
        )
    val selections =
        world
            .fragmentFrom("fragment ignored on Query { value { text } }")
            .materializeSelections

    val openResult =
        ObjectEngineResult.of(
            type = world.schema.requireQueryTypeDef(),
            values = mapOf(key to valueResult("open")),
            fieldCheckerResults = emptyMap(),
            typeCheckerResults = emptyMap(),
        )
    val openInput =
        materialize(
            openResult,
            operation,
            selections,
            openResult.fieldResolverCycleTask(listOf(key)),
        )

    assertFalse(openResult.getCell(key).isFieldCheckerResultSet())
    assertFalse(openResult.getCell(key).isTypeCheckerResultSet())
    assertEquals("open", assertIs<EngineObjectData.Sync>(openInput.get("value")).get("text"))

    val denial = ResolverInputDenial("field denied")
    val deniedResult =
        ObjectEngineResult.of(
            type = world.schema.requireQueryTypeDef(),
            values = mapOf(key to valueResult("field denied")),
            fieldCheckerResults = mapOf(key to denial),
            typeCheckerResults = emptyMap(),
        )
    val deniedInput =
        materialize(
            deniedResult,
            operation,
            selections,
            deniedResult.fieldResolverCycleTask(listOf(key)),
        )

    assertSame(denial.error, assertIs<EngineErrorData>(deniedInput.outputValue("value")).cause)

    val typeDenial = ResolverInputDenial("type denied")
    val typeDeniedResult =
        ObjectEngineResult.of(
            type = world.schema.requireQueryTypeDef(),
            values = mapOf(key to valueResult("type denied")),
            fieldCheckerResults = emptyMap(),
            typeCheckerResults = mapOf(key to typeDenial),
        )
    val typeDeniedInput =
        materialize(
            typeDeniedResult,
            operation,
            selections,
            typeDeniedResult.fieldResolverCycleTask(listOf(key)),
        )

    assertSame(typeDenial.error, assertIs<EngineErrorData>(typeDeniedInput.outputValue("value")).cause)

    val fieldDenial = ResolverInputDenial("combined field denial")
    val combinedDenial = ResolverInputDenial("combined denial")
    val combiningTypeDenial = CombiningTypeDenial(fieldDenial, combinedDenial)
    val multiplyDeniedResult =
        ObjectEngineResult.of(
            type = world.schema.requireQueryTypeDef(),
            values = mapOf(key to valueResult("multiply denied")),
            fieldCheckerResults = mapOf(key to fieldDenial),
            typeCheckerResults = mapOf(key to combiningTypeDenial),
        )
    val multiplyDeniedInput =
        materialize(
            multiplyDeniedResult,
            operation,
            selections,
            multiplyDeniedResult.fieldResolverCycleTask(listOf(key)),
        )

    assertSame(
        combinedDenial.error,
        assertIs<EngineErrorData>(multiplyDeniedInput.outputValue("value")).cause,
    )

    val valuesKey =
        ObjectEngineResult.GroundKey.of(
            world.schema.requireObjectField("Query", "values"),
            emptyMap(),
        )
    val listItemDenial = ResolverInputDenial("list item type denied")
    val listResult =
        ListEngineResult.of(
            typeExpr = valuesKey.field.outputType.unwrapList()!!,
            values = listOf(valueResult("list item")),
            typeCheckerResults = listOf(listItemDenial),
        )
    val listedResult =
        ObjectEngineResult.of(
            type = world.schema.requireQueryTypeDef(),
            values = mapOf(valuesKey to listResult),
            fieldCheckerResults = emptyMap(),
            typeCheckerResults = emptyMap(),
        )
    val listedSelections =
        world
            .fragmentFrom("fragment ignored on Query { values { text } }")
            .materializeSelections
    val listedInput =
        materialize(
            listedResult,
            operation,
            listedSelections,
            listedResult.fieldResolverCycleTask(listOf(valuesKey)),
        )

    val listedValues = assertIs<List<*>>(listedInput.outputValue("values"))
    assertSame(listItemDenial.error, assertIs<EngineErrorData>(listedValues.single()).cause)

    val rawFailure = EngineErrorData.of(IllegalStateException("raw failure"))
    val failedResult = ObjectEngineResult.of(world.schema.requireQueryTypeDef(), mutable = true)
    val failedCell = failedResult.reserveCell(key)
    failedCell.setFieldCheckerResult(CheckerResult.Success)
    failedCell.setTypeCheckerResult(CheckerResult.Success)
    failedCell.setValue(ErrorEngineResult.of(rawFailure))
    val failedInput =
        withTimeout(1_000) {
            materialize(
                failedResult,
                operation,
                selections,
                failedResult.fieldResolverCycleTask(listOf(key)),
            )
        }

    assertSame(rawFailure, failedInput.outputValue("value"))

    val shortCircuitDenial = ResolverInputDenial("short circuit")
    val shortCircuitedResult = ObjectEngineResult.of(world.schema.requireQueryTypeDef(), mutable = true)
    val shortCircuitedCell = shortCircuitedResult.reserveCell(key)
    shortCircuitedCell.reserveValue()
    shortCircuitedCell.setFieldCheckerResult(shortCircuitDenial)
    val shortCircuitedInput =
        withTimeout(1_000) {
            materialize(
                shortCircuitedResult,
                operation,
                selections,
                shortCircuitedResult.fieldResolverCycleTask(listOf(key)),
            )
        }

    assertSame(
        shortCircuitDenial.error,
        assertIs<EngineErrorData>(shortCircuitedInput.outputValue("value")).cause,
    )
    assertFalse(shortCircuitedCell.getValue().isCompleted)
}

private class ResolverInputDenial(message: String) : CheckerResult.Error {
    override val error: Exception = IllegalStateException(message)

    override fun isErrorForResolver(ctx: CheckerResultContext): Boolean {
        val directives = checkNotNull(ctx.fieldDirectives)
        check(!directives.hasDirective("testDirective"))
        return true
    }

    override fun combine(fieldResult: CheckerResult.Error): CheckerResult.Error = this
}

private class CombiningTypeDenial(
    private val expectedFieldResult: CheckerResult.Error,
    private val combinedResult: CheckerResult.Error,
) : CheckerResult.Error {
    override val error: Exception = IllegalStateException("uncombined type denial")

    override fun isErrorForResolver(ctx: CheckerResultContext): Boolean =
        error("The combined result must determine resolver applicability")

    override fun combine(fieldResult: CheckerResult.Error): CheckerResult.Error {
        assertSame(expectedFieldResult, fieldResult)
        return combinedResult
    }
}
