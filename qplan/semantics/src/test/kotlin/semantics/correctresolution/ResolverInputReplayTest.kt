package semantics.correctresolution

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame
import model.Arguments
import model.EngineErrorData
import model.EngineObjectDataEntry
import model.ObjectEngineResult
import model.Promise
import model.ResolverOccurrenceId
import model.emptyFragmentOf
import model.engineObjectDataOf
import model.fragmentFrom
import model.materializedEngineObjectDataOf
import model.requireObjectField
import model.requireQueryTypeDef
import model.requireType
import model.testing.TestWorld
import semantics.shared.ResolverInvocationObservation
import semantics.shared.SharedOperationContext
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.CheckerResultContext
import viaduct.graphql.schema.ViaductSchema

class ResolverInputReplayTest {
    @Test
    fun `early denial witness must be the exact applicable field error at that alias`() {
        listOf(true, false).forEach { applicable ->
            val worldFixture = TestWorld.fromSDL("type Query { item: Item! consume: Int! } type Item { value: Int! }")
            val world = worldFixture.assumptions
            val schema = worldFixture.schemas
            val query = schema.loweredSchema.requireQueryTypeDef()
            val item = schema.loweredSchema.requireType("Item") as ViaductSchema.Object
            val itemField = schema.loweredSchema.requireObjectField("Query", "item")
            val itemKey = ObjectEngineResult.GroundKey.of(itemField, emptyMap())
            val fieldError = ReplayDenial(applicable)
            val typeError = ReplayDenial(true)
            val root = ObjectEngineResult.of(
                query,
                values = mapOf(
                    itemKey to ObjectEngineResult.of(item, typeCheckerResult = Promise.of(typeError)),
                ),
                fieldCheckerResults = mapOf(itemKey to fieldError)
            )
            val consumer = schema.loweredSchema.requireObjectField("Query", "consume")
            val consumerKey = ObjectEngineResult.GroundKey.of(consumer, emptyMap())
            val id = ResolverOccurrenceId.at(root, listOf(consumerKey))
            val selections = schema.fragmentFrom("fragment Input on Query { alias: item { value } }").materializeSelections
            val emptySelections = schema.loweredSchema.emptyFragmentOf("Query").materializeSelections
            val emptyQuery = engineObjectDataOf(query)

            fun input(error: Throwable) = materializedEngineObjectDataOf(query, listOf(EngineObjectDataEntry.of("alias", itemField, EngineErrorData.of(error))))
            val expected = input(typeError.error)
            listOf(fieldError.error, IllegalStateException("unrelated error")).forEach { cause ->
                val actual = input(cause)
                val observer = CorrectnessResolverObserver().apply {
                    onResolverInvocation(
                        ResolverInvocationObservation(
                            listOf(consumerKey),
                            consumer,
                            actual,
                            selections,
                            emptyQuery,
                            emptySelections,
                            Arguments.Resolved.of(consumer, emptyMap()),
                            null,
                            id,
                        )
                    )
                }
                val replay = SharedOperationContext.create(world, resolverObserver = observer).resolverInputsForReplay(
                    id,
                    root,
                    selections,
                    emptySelections,
                    expected,
                    emptyQuery,
                )
                if (applicable && cause === fieldError.error) assertSame(actual, replay?.objectValue) else assertNull(replay)
            }
        }
    }
}

private class ReplayDenial(private val applicable: Boolean) : CheckerResult.Error {
    override val error = IllegalStateException("denied")

    override fun isErrorForResolver(ctx: CheckerResultContext): Boolean = applicable

    override fun combine(fieldResult: CheckerResult.Error): CheckerResult.Error = this
}
