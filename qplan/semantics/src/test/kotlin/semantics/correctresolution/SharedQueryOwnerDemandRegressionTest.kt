package semantics.correctresolution

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import model.Arguments
import model.EngineErrorData
import model.ErrorEngineResult
import model.ObjectEngineResult
import model.ResolverOccurrenceId
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

/** Negative witnesses must still be checked against each owner's declared Query demand. */
class SharedQueryOwnerDemandRegressionTest {
    @Test
    fun `cached shared scope validation cannot erase a second owner's input obligation`() {
        val world = TestWorld.fromSDL(
            schemaSDL = "type Query { first(value: Int!): Int! second(value: Int!): Int! left: Int! right: Int! }",
            fieldResolvers = { schema ->
                mapOf(
                    schema.requireObjectField("Query", "first") to fieldResolverOf(
                        objectFragment = schema.emptyFragmentOf("Query"),
                        queryFragment = schema.fragmentFrom("fragment First on Query { left }"),
                    ) { _, _, _ -> error("Erroneous arguments must suppress invocation") },
                    schema.requireObjectField("Query", "second") to fieldResolverOf(
                        objectFragment = schema.emptyFragmentOf("Query"),
                        queryFragment = schema.fragmentFrom("fragment Second on Query { right }"),
                    ) { _, _, _ -> error("Erroneous arguments must suppress invocation") },
                    schema.requireObjectField("Query", "left") to
                        fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 7 },
                    schema.requireObjectField("Query", "right") to
                        fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 9 },
                )
            },
        ).assumptions
        val queryType = world.schema.requireQueryTypeDef()
        val result = ObjectEngineResult.of(queryType, mutable = true)
        val observer = CorrectnessResolverObserver()
        val operation = SharedOperationContext.create(world, resolverObserver = observer)
        val incompleteQuery = world.engineResultOf("Query") { "left" resolvesTo 7 }
        listOf("first", "second").forEach { name ->
            val field = world.schema.requireObjectField("Query", name)
            val variable = Arguments.Variable.of(field, "value").instantiate(
                ResolverOccurrenceId.at(result, emptyList()),
            )
            val key = ObjectEngineResult.ObjectKey.of(
                field,
                Arguments.of(field, mapOf("value" to variable)),
            )
            result.reserveCell(key).apply {
                value.set(ErrorEngineResult.of(EngineErrorData.of()))
                fieldCheckerResult.complete(null)
            }
            operation.variableBindings.bindVariable(requireNotNull(variable.instanceId), VariableBinding.Error)
            observer.onQueryFragmentPrepared(ResolverOccurrenceId.at(result, listOf(key)), incompleteQuery)
        }
        result.freeze()
        assertFalse(
            result.correctResolution(operation, selectionForestOf().merge(queryType)),
            "Validating left for the first owner must not waive right for the second owner",
        )
    }

    @Test
    fun `observed closure cannot erase an error argument owner's Query input obligation`() {
        val world = TestWorld.fromSDL(
            schemaSDL = "type Query { consumer(value: Int!): Int! source: Int! }",
            fieldResolvers = { schema ->
                mapOf(
                    schema.requireObjectField("Query", "consumer") to fieldResolverOf(
                        objectFragment = schema.emptyFragmentOf("Query"),
                        queryFragment = schema.fragmentFrom("fragment Input on Query { source }"),
                    ) { _, _, _ -> error("Erroneous arguments must suppress invocation") },
                    schema.requireObjectField("Query", "source") to
                        fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 7 },
                )
            },
        ).assumptions
        val queryType = world.schema.requireQueryTypeDef()
        val consumer = world.schema.requireObjectField("Query", "consumer")
        val result = ObjectEngineResult.of(queryType, mutable = true)
        val variable = Arguments.Variable.of(consumer, "value").instantiate(
            ResolverOccurrenceId.at(result, emptyList()),
        )
        val key = ObjectEngineResult.ObjectKey.of(
            consumer,
            Arguments.of(consumer, mapOf("value" to variable)),
        )
        result.reserveCell(key).apply {
            value.set(ErrorEngineResult.of(EngineErrorData.of()))
            fieldCheckerResult.complete(null)
        }
        result.freeze()
        val owner = ResolverOccurrenceId.at(result, listOf(key))
        val sourceDemand = world.fragmentFrom("fragment Demand on Query { source }").subselections.merge(queryType)
        val emptyDemand = selectionForestOf().merge(queryType)

        fun operation(query: ObjectEngineResult, observedDemand: model.ObjectSelectionForest?): SharedOperationContext<*> {
            val observer = CorrectnessResolverObserver()
            observer.onQueryFragmentPrepared(owner, query)
            observedDemand?.let { demand ->
                observer.onQueryOERPrepared(
                    SharedOERContext(
                        OEROccurrence(query, emptyList(), query),
                        engineObjectDataOf(queryType),
                        demand,
                    ),
                    queryOERDepth = 1,
                )
            }
            return SharedOperationContext.create(world, resolverObserver = observer).also {
                it.variableBindings.bindVariable(requireNotNull(variable.instanceId), VariableBinding.Error)
            }
        }

        val completeQuery = world.engineResultOf("Query") { "source" resolvesTo 7 }
        assertTrue(result.correctResolution(operation(completeQuery, sourceDemand), emptyDemand))
        val missingQuery = world.engineResultOf("Query")
        assertFalse(result.correctResolution(operation(missingQuery, null), emptyDemand))
        assertFalse(
            result.correctResolution(operation(missingQuery, emptyDemand), emptyDemand),
            "An observed empty shared closure must not make a missing declared Query input correct",
        )
    }
}
