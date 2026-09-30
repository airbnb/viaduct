package semantics.correctresolution

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import model.Arguments
import model.ObjectEngineResult
import model.ResolverOccurrenceId
import model.Selection
import model.VariableBinding
import model.emptyFragmentOf
import model.engineObjectDataOf
import model.engineResultOf
import model.fragmentFrom
import model.merge
import model.requireObjectField
import model.requireQueryTypeDef
import model.selectionForestOf
import model.testing.TestWorld
import model.testing.fieldResolverOf
import semantics.shared.OEROccurrence
import semantics.shared.SharedOERContext
import semantics.shared.SharedOperationContext

/** Exact shared Query demand must reject undeclared symbolic cells even if their values coalesce. */
class SharedQuerySymbolicDomainRegressionTest {
    @Test
    fun `grounding equality cannot hide an extra symbolic Query cell`() {
        assertTrue(validate(false), "The exact declared Query key is valid")
        assertFalse(validate(true), "An undeclared symbolic Query cell is extra work even when it grounds to an existing key")
    }

    @Test
    fun `separate symbolic cell remains valid when explicitly declared`() {
        assertTrue(validate(extraSymbolicCell = true, declareExtra = true))
    }

    private fun validate(
        extraSymbolicCell: Boolean,
        declareExtra: Boolean = false
    ): Boolean {
        val worldFixture = TestWorld.fromSDL(
            schemaSDL = "type Query { consumer: Int!, source(value: Int!): Int! }",
            fieldResolvers = { schema ->
                mapOf(
                    schema.loweredSchema.requireObjectField("Query", "consumer") to fieldResolverOf(
                        objectFragment = schema.loweredSchema.emptyFragmentOf("Query"),
                        queryFragment = schema.fragmentFrom("fragment Input on Query { source(value: 7) }"),
                    ) { _, _, _ -> 7 },
                    schema.loweredSchema.requireObjectField("Query", "source") to fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 7 },
                )
            },
        )
        val world = worldFixture.assumptions
        val queryType = worldFixture.schema.requireQueryTypeDef()
        val source = worldFixture.schema.requireObjectField("Query", "source")
        val consumerKey = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", "consumer"), emptyMap())
        val result = world.engineResultOf("Query") { "consumer" resolvesTo 7 }
        val closedValueSelections = worldFixture.schemas.fragmentFrom("fragment Demand on Query { source(value: 7) }").subselections.merge(queryType)

        var observedDemand = closedValueSelections
        val query = ObjectEngineResult.of(queryType, mutable = true)
        val observer = CorrectnessResolverObserver()
        val operation = SharedOperationContext.create(world, resolverObserver = observer)
        query.reserveCell(ObjectEngineResult.GroundKey.of(source, mapOf("value" to 7))).apply {
            value.set(7)
            fieldCheckerResult.complete(null)
        }
        if (extraSymbolicCell) {
            val variable = Arguments.Variable.of(source, "value").instantiate(ResolverOccurrenceId.at(query, emptyList()))
            val key = ObjectEngineResult.ObjectKey.of(source, Arguments.of(source, mapOf("value" to variable)))
            operation.variableBindings.bindVariable(requireNotNull(variable.instanceId), VariableBinding.of(7))
            query.reserveCell(key).apply {
                value.set(7)
                fieldCheckerResult.complete(null)
            }
            if (declareExtra) {
                observedDemand = (
                    closedValueSelections + selectionForestOf(
                        Selection.of(
                            key = key,
                            possibleTypes = setOf(queryType),
                            subselections = selectionForestOf(),
                        )
                    )
                ).merge(queryType)
            }
        }
        query.freeze()
        observer.onQueryOERPrepared(SharedOERContext(OEROccurrence(query, emptyList(), query), engineObjectDataOf(queryType), observedDemand))
        observer.onQueryFragmentPrepared(ResolverOccurrenceId.at(result, listOf(consumerKey)), query)
        return result.correctResolution(operation, selectionForestOf().merge(queryType))
    }
}
