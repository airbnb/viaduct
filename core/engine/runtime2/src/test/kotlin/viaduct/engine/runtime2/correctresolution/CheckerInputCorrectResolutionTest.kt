package viaduct.engine.runtime2.correctresolution

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import viaduct.engine.api.CheckerResult
import viaduct.engine.runtime2.model.EngineObjectDataEntry
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.Promise
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.ResolverTarget
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.engineObjectDataOf
import viaduct.engine.runtime2.model.registry.CheckerInput
import viaduct.engine.runtime2.model.registry.ResolverFragmentTemplates
import viaduct.engine.runtime2.model.registry.TypeCheckerResolver
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.emptyFragmentOf
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.resolution.framework.CheckerInvocationObservation
import viaduct.engine.runtime2.resolution.framework.CheckerKind
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.graphql.schema.ViaductSchema

/** Equal duplicate projections and a matching constant result must not hide wrong actual inputs. */
class CheckerInputCorrectResolutionTest {
    @Test
    fun `replay rejects wrong scalar projections even when both named inputs agree and return success`() {
        val worldFixture = TestWorld.fromSDL(
            schemaSDL = "type Query { item: Item marker: Int! } type Item { value: Int! marker: Int! }",
            fieldResolvers = { schema ->
                mapOf(
                    schema.loweredSchema.requireObjectField("Query", "item") to fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                        engineObjectDataOf(schema.loweredSchema.requireType("Item") as ViaductSchema.Object)
                    }
                ) + listOf("Query" to "marker", "Item" to "value", "Item" to "marker").associate { (type, name) ->
                    schema.loweredSchema.requireObjectField(type, name) to fieldResolverOf(schema.loweredSchema.emptyFragmentOf(type)) { _, _ -> 7 }
                }
            },
            typeCheckers = { schema ->
                val query = schema.loweredSchema.requireQueryTypeDef()
                val item = schema.loweredSchema.requireType("Item") as ViaductSchema.Object
                val selections = schema.fragmentFrom("fragment Input on Item { alias: marker }").materializeSelections
                val querySelections = schema.fragmentFrom("fragment Input on Query { alias: marker }").materializeSelections
                mapOf(
                    item to TypeCheckerResolver.of(
                        item,
                        query,
                        listOf("left", "right").associateWith { ResolverFragmentTemplates(selections, querySelections) },
                    ) { _, _ -> CheckerResult.Success }
                )
            },
        )
        val world = worldFixture.assumptions
        val query = worldFixture.schema.requireQueryTypeDef()
        val itemType = worldFixture.schema.requireType("Item") as ViaductSchema.Object
        val itemKey = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", "item"), emptyMap())
        val value = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Item", "value"), emptyMap())
        val marker = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Item", "marker"), emptyMap())
        val item = ObjectEngineResult.of(itemType, values = mapOf(value to 7, marker to 7), typeCheckerResult = Promise.of(CheckerResult.Success))
        val root = ObjectEngineResult.of(query, values = mapOf(itemKey to item))
        val queryMarker = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", "marker"), emptyMap())
        val queryInput = ObjectEngineResult.of(query, values = mapOf(queryMarker to 7))
        val target = ResolverTarget.TypeCheckerTarget(itemType)
        val observation = CheckerInvocationObservation(CheckerKind.TYPE, root, listOf(itemKey), null, target)
        val fragment = worldFixture.schemas.fragmentFrom("fragment Query on Query { item { value } }")

        fun conforms(
            objectScalar: Int,
            queryScalar: Int,
            names: Set<String> = setOf("left", "right")
        ): Boolean {
            val observer = CorrectnessResolverObserver()
            observer.onCheckerQueryFragmentPrepared(target, ResolverOccurrenceId.at(root, listOf(itemKey)), queryInput)
            observer.onCheckerInvocation(
                observation,
                names.associateWith {
                    CheckerInput(
                        engineObjectDataOf(itemType, listOf(EngineObjectDataEntry.of("alias", marker.field, objectScalar))),
                        engineObjectDataOf(query, listOf(EngineObjectDataEntry.of("alias", queryMarker.field, queryScalar))),
                    )
                }
            )
            return root.correctResolution(SharedOperationContext.create(world, resolutionObserver = observer), fragment)
        }
        assertTrue(conforms(7, 7))
        assertFalse(conforms(8, 7), "Equal wrong object projections passed the constant success relation")
        assertFalse(conforms(7, 8), "Equal wrong Query projections passed the constant success relation")
        assertFalse(conforms(8, 8))
        assertFalse(conforms(7, 7, setOf("left")), "A missing named input was accepted")
    }
}
