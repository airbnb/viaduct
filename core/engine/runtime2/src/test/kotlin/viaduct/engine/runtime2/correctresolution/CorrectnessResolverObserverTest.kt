package viaduct.engine.runtime2.correctresolution

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.engineObjectDataOf
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.emptyFragmentOf
import viaduct.engine.runtime2.model.toCanonicalMaterializeSelectionForest
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation

class CorrectnessResolverObserverTest {
    @Test
    fun `invocation identities deduplicate repeated observations`() {
        val worldFixture = TestWorld.fromSDL("type Query { first: Int second: Int }")
        val world = worldFixture.assumptions
        val root = ObjectEngineResult.of(type = world.schema.requireQueryTypeDef(), mutable = true)
        val observations = listOf("first", "second").map { name ->
            val field = world.schema.requireObjectField("Query", name)
            val path = listOf(ObjectEngineResult.GroundKey.of(field, emptyMap()))
            ResolverInvocationObservation(
                occurrencePath = path,
                field = field,
                input = engineObjectDataOf(world.schema.requireQueryTypeDef()),
                inputSelections = world.emptyFragmentOf("Query").subselections.toCanonicalMaterializeSelectionForest(),
                queryValue = engineObjectDataOf(world.schema.requireQueryTypeDef()),
                queryInputSelections = world.emptyFragmentOf("Query").materializeSelections,
                arguments = Arguments.Resolved.of(field, emptyMap()),
                suppliedDemand = null,
                resolverOccurrenceId = ResolverOccurrenceId.at(root, path),
            )
        }
        val observer = CorrectnessResolverObserver()
        observations.forEach(observer::onResolverInvocation)
        val expectedIds = observations.map { it.resolverOccurrenceId }.toSet()
        assertEquals(2, expectedIds.size)
        assertEquals(expectedIds, observer.invokedResolverOccurrences())

        // A new event and ID instance for the same occurrence must still deduplicate.
        observer.onResolverInvocation(
            observations.last().copy(
                resolverOccurrenceId = ResolverOccurrenceId.at(root, observations.last().occurrencePath),
            )
        )
        assertEquals(expectedIds, observer.invokedResolverOccurrences())
    }
}
