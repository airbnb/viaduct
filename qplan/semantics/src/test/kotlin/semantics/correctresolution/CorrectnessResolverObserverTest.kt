package semantics.correctresolution

import model.Arguments
import model.ObjectEngineResult
import model.ResolverOccurrenceId
import model.emptyFragmentOf
import model.engineObjectDataOf
import model.requireObjectField
import model.requireQueryTypeDef
import model.testing.TestWorld
import model.toCanonicalMaterializeSelectionForest
import org.junit.jupiter.api.Test
import semantics.shared.ResolverInvocationObservation
import kotlin.test.assertEquals

class CorrectnessResolverObserverTest {
    @Test
    fun `invocation identities deduplicate repeated observations`() {
        val world = TestWorld.fromSDL("type Query { first: Int second: Int }").assumptions
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
        observer.onResolverInvocation(observations.last().copy(
            resolverOccurrenceId = ResolverOccurrenceId.at(root, observations.last().occurrencePath),
        ))
        assertEquals(expectedIds, observer.invokedResolverOccurrences())
    }
}
