package viaduct.engine.runtime2.correctresolution

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.EngineErrorData
import viaduct.engine.runtime2.model.ErrorEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ObjectSelectionForest
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.VariableBinding
import viaduct.engine.runtime2.model.engineObjectDataOf
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.selectionForestOf
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.engineResultOf
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.resolution.framework.OEROccurrence
import viaduct.engine.runtime2.resolution.framework.SharedOERContext
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

/** Negative witnesses must still be checked against each owner's declared Query demand. */
class SharedQueryOwnerDemandRegressionTest {
    @Test
    fun `cached shared scope validation cannot erase a second owner's input obligation`() {
        val worldFixture = TestWorld.fromDSL(
            """
            extend type Query {
              first(value: Int!): Int! @resolver(qf: "left", script: "throw 'Erroneous arguments must suppress invocation'")
              second(value: Int!): Int! @resolver(qf: "right", script: "throw 'Erroneous arguments must suppress invocation'")
              left: Int! @resolver(script: "7")
              right: Int! @resolver(script: "9")
            }
            """.trimIndent(),
        )
        val world = worldFixture.assumptions
        val queryType = worldFixture.schema.requireQueryTypeDef()
        val result = ObjectEngineResult.of(queryType, mutable = true)
        val observer = CorrectnessResolverObserver()
        val operation = SharedOperationContext.create(world, resolutionObserver = observer)
        val incompleteQuery = world.engineResultOf("Query") { "left" resolvesTo 7 }
        listOf("first", "second").forEach { name ->
            val field = worldFixture.schema.requireObjectField("Query", name)
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
            observer.onQueryFragmentPrepared(ResolverOccurrenceId.at(result, listOf(key)), incompleteQuery, null)
        }
        result.freeze()
        assertFalse(
            result.correctResolution(operation, selectionForestOf().merge(queryType)),
            "Validating left for the first owner must not waive right for the second owner",
        )
    }

    @Test
    fun `observed closure cannot erase an error argument owner's Query input obligation`() {
        val worldFixture = TestWorld.fromDSL(
            """
            extend type Query {
              consumer(value: Int!): Int! @resolver(qf: "source", script: "throw 'Erroneous arguments must suppress invocation'")
              source: Int! @resolver(script: "7")
            }
            """.trimIndent(),
        )
        val world = worldFixture.assumptions
        val queryType = worldFixture.schema.requireQueryTypeDef()
        val consumer = worldFixture.schema.requireObjectField("Query", "consumer")
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
        val sourceDemand = worldFixture.schemas.fragmentFrom("fragment Demand on Query { source }").subselections.merge(queryType)
        val emptyDemand = selectionForestOf().merge(queryType)

        fun operation(
            query: ObjectEngineResult,
            observedDemand: ObjectSelectionForest?
        ): SharedOperationContext<*> {
            val observer = CorrectnessResolverObserver()
            observer.onQueryFragmentPrepared(owner, query, null)
            observedDemand?.let { demand ->
                observer.recordQueryOERPrepared(
                    SharedOERContext(
                        OEROccurrence(query, emptyList(), query),
                        engineObjectDataOf(queryType),
                        demand,
                    ),
                    null,
                    1
                )
            }
            return SharedOperationContext.create(world, resolutionObserver = observer).also {
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
