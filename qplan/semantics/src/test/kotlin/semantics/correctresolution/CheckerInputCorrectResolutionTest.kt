package semantics.correctresolution

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import model.EngineObjectDataEntry
import model.ObjectEngineResult
import model.Promise
import model.ResolverOccurrenceId
import model.emptyFragmentOf
import model.engineObjectDataOf
import model.fragmentFrom
import model.registry.CheckerInput
import model.registry.ResolverFragmentTemplates
import model.registry.ResolverTarget
import model.registry.TypeCheckerResolver
import model.registry.fieldResolverOf
import model.requireObjectField
import model.requireQueryTypeDef
import model.testing.TestWorld
import semantics.shared.CheckerInvocationObservation
import semantics.shared.CheckerKind
import semantics.shared.SharedOperationContext
import viaduct.engine.api.CheckerResult

/** Equal duplicate projections and a matching constant result must not hide wrong actual inputs. */
class CheckerInputCorrectResolutionTest {
    @Test
    fun `replay rejects wrong scalar projections even when both named inputs agree and return success`() {
        val worldFixture = TestWorld.fromSDL(
            schemaSDL = "type Query { value: Int! marker: Int! }",
            fieldResolvers = { schema ->
                listOf("value", "marker").associate { name ->
                    schema.loweredSchema.requireObjectField("Query", name) to fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 7 }
                }
            },
            typeCheckers = { schema ->
                val query = schema.loweredSchema.requireQueryTypeDef()
                val selections = schema.fragmentFrom("fragment Input on Query { alias: marker }").materializeSelections
                mapOf(
                    query to TypeCheckerResolver.of(
                        query,
                        query,
                        listOf("left", "right").associateWith { ResolverFragmentTemplates(selections, selections) },
                    ) { _, _ -> CheckerResult.Success }
                )
            },
        )
        val world = worldFixture.assumptions
        val query = worldFixture.schema.requireQueryTypeDef()
        val value = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", "value"), emptyMap())
        val marker = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", "marker"), emptyMap())
        val root = ObjectEngineResult.of(query, values = mapOf(value to 7, marker to 7), typeCheckerResult = Promise.of(CheckerResult.Success))
        val queryInput = ObjectEngineResult.of(query, values = mapOf(marker to 7))
        val target = ResolverTarget.TypeCheckerTarget(query)
        val observation = CheckerInvocationObservation(CheckerKind.TYPE, root, emptyList(), null, target)
        val fragment = worldFixture.schemas.fragmentFrom("fragment Query on Query { value }")

        fun conforms(
            objectScalar: Int,
            queryScalar: Int,
            names: Set<String> = setOf("left", "right")
        ): Boolean {
            val observer = CorrectnessCheckerObserver()
            observer.onCheckerQueryFragmentPrepared(target, ResolverOccurrenceId.at(root, emptyList()), queryInput)
            observer.onCheckerInvocation(
                observation,
                names.associateWith {
                    CheckerInput(
                        engineObjectDataOf(query, listOf(EngineObjectDataEntry.of("alias", marker.field, objectScalar))),
                        engineObjectDataOf(query, listOf(EngineObjectDataEntry.of("alias", marker.field, queryScalar))),
                    )
                }
            )
            return root.correctResolution(SharedOperationContext.create(world, checkerObserver = observer), fragment)
        }
        assertTrue(conforms(7, 7))
        assertFalse(conforms(8, 7), "Equal wrong object projections passed the constant success relation")
        assertFalse(conforms(7, 8), "Equal wrong Query projections passed the constant success relation")
        assertFalse(conforms(8, 8))
        assertFalse(conforms(7, 7, setOf("left")), "A missing named input was accepted")
    }
}
