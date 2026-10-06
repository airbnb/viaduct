package viaduct.engine.runtime2.correctresolution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.CheckerResultContext
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.Promise
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.ResolverTarget
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.materializeSelectionForestOf
import viaduct.engine.runtime2.model.registry.ResolverFragmentTemplates
import viaduct.engine.runtime2.model.registry.TypeCheckerResolver
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.emptyFragmentOf
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.testing.objectOf
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.graphql.schema.ViaductSchema

/** Mutated completed results exercise the oracle independently of any resolver algorithm. */
class TypeCheckerCorrectResolutionTest {
    @Test
    fun `checked OER requires its type result raw input and matching replayed variant`() {
        var calls = 0
        val worldFixture = TestWorld.fromSDL(
            schemaSDL = "type Query { item: Item! } type Item { value: Int! policy: Int! }",
            fieldResolvers = { schema ->
                mapOf(
                    schema.loweredSchema.requireObjectField("Query", "item") to
                        fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                            schema.loweredSchema.objectOf("Item") {
                                "value" setTo 1
                                "policy" setTo 7
                            }
                        }
                )
            },
            typeCheckers = { schema ->
                val item = schema.loweredSchema.requireType("Item") as ViaductSchema.Object
                mapOf(
                    item to TypeCheckerResolver.of(
                        item,
                        schema.loweredSchema.requireQueryTypeDef(),
                        mapOf(
                            "input" to ResolverFragmentTemplates(
                                schema.fragmentFrom("fragment Input on Item { alias: policy }").materializeSelections,
                                materializeSelectionForestOf(),
                            )
                        ),
                    ) { inputs, _ ->
                        calls++
                        assertEquals(7, inputs.getValue("input").objectValue.get("alias"))
                        CheckerResult.Success
                    }
                )
            },
        )
        val world = worldFixture.assumptions
        val itemType = worldFixture.schema.requireType("Item") as ViaductSchema.Object
        val itemKey = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", "item"), emptyMap())
        val valueKey = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Item", "value"), emptyMap())
        val policyKey = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Item", "policy"), emptyMap())

        fun result(
            check: CheckerResult?,
            includePolicy: Boolean = true
        ): ObjectEngineResult =
            ObjectEngineResult.of(
                world.schema.requireQueryTypeDef(),
                values = mapOf(
                    itemKey to ObjectEngineResult.of(
                        itemType,
                        values = buildMap {
                            put(valueKey, 1)
                            if (includePolicy) put(policyKey, 7)
                        },
                        typeCheckerResult = Promise.of(check),
                    ),
                )
            )
        val operation = SharedOperationContext.create(world)
        val selections = worldFixture.schemas.fragmentFrom("fragment Query on Query { item { value } }")
        assertFalse(result(null).correctResolution(operation, selections))
        assertFalse(result(CheckerResult.Success, includePolicy = false).correctResolution(operation, selections))
        assertFalse(result(OracleTypeDenial()).correctResolution(operation, selections))
        assertTrue(result(CheckerResult.Success).correctResolution(operation, selections))
        assertEquals(2, calls)
    }

    @Test
    fun `type checker Query replay rejects missing duplicate and incorrect witnesses`() {
        val worldFixture = TestWorld.fromSDL(
            schemaSDL = "type Query { item: Item! marker: Int! } type Item { value: Int! }",
            fieldResolvers = { schema ->
                mapOf(
                    schema.loweredSchema.requireObjectField("Query", "item") to fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                        schema.loweredSchema.objectOf("Item") { "value" setTo 1 }
                    },
                    schema.loweredSchema.requireObjectField("Query", "marker") to fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 7 },
                )
            },
            typeCheckers = { schema ->
                val item = schema.loweredSchema.requireType("Item") as ViaductSchema.Object
                mapOf(
                    item to TypeCheckerResolver.of(
                        item,
                        schema.loweredSchema.requireQueryTypeDef(),
                        mapOf(
                            "input" to ResolverFragmentTemplates(
                                materializeSelectionForestOf(),
                                schema.fragmentFrom("fragment Input on Query { alias: marker }").materializeSelections,
                            )
                        ),
                    ) { inputs, _ ->
                        assertEquals(7, inputs.getValue("input").queryValue.get("alias"))
                        CheckerResult.Success
                    }
                )
            },
        )
        val world = worldFixture.assumptions
        val item = worldFixture.schema.requireType("Item") as ViaductSchema.Object
        val itemKey = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", "item"), emptyMap())
        val valueKey = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Item", "value"), emptyMap())
        val markerKey = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", "marker"), emptyMap())
        val root = ObjectEngineResult.of(
            world.schema.requireQueryTypeDef(),
            values = mapOf(
                itemKey to ObjectEngineResult.of(item, values = mapOf(valueKey to 1), typeCheckerResult = Promise.of(CheckerResult.Success)),
            )
        )
        val selections = worldFixture.schemas.fragmentFrom("fragment Query on Query { item { value } }")

        fun operation(
            vararg markers: Int,
            wrongTarget: Boolean = false
        ): SharedOperationContext<*> {
            val observer = CorrectnessCheckerObserver()
            markers.forEach { marker ->
                observer.onCheckerQueryFragmentPrepared(
                    if (wrongTarget) ResolverTarget.FieldCheckerTarget(itemKey.field) else ResolverTarget.TypeCheckerTarget(item),
                    ResolverOccurrenceId.at(root, listOf(itemKey)),
                    ObjectEngineResult.of(world.schema.requireQueryTypeDef(), values = mapOf(markerKey to marker)),
                )
            }
            return SharedOperationContext.create(world, checkerObserver = observer)
        }
        assertFalse(root.correctResolution(operation(), selections))
        assertFalse(root.correctResolution(operation(8), selections))
        assertFalse(root.correctResolution(operation(7, 7), selections))
        assertFalse(root.correctResolution(operation(7, wrongTarget = true), selections))
        assertTrue(root.correctResolution(operation(7), selections))
    }

    @Test
    fun `primary Query root is checked even with no selected fields`() {
        val worldFixture = TestWorld.fromSDL(
            schemaSDL = "type Query { value: Int! }",
            fieldResolvers = { schema -> mapOf(schema.loweredSchema.requireObjectField("Query", "value") to fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 1 }) },
            typeCheckers = { schema ->
                val query = schema.loweredSchema.requireQueryTypeDef()
                mapOf(query to TypeCheckerResolver.of(query, query) { _, _ -> CheckerResult.Success })
            },
        )
        val world = worldFixture.assumptions
        val selections = worldFixture.schema.emptyFragmentOf("Query")
        val operation = SharedOperationContext.create(world)
        assertFalse(ObjectEngineResult.of(world.schema.requireQueryTypeDef()).correctResolution(operation, selections))
        assertTrue(ObjectEngineResult.of(world.schema.requireQueryTypeDef(), typeCheckerResult = Promise.of(CheckerResult.Success)).correctResolution(operation, selections))
    }
}

private class OracleTypeDenial : CheckerResult.Error {
    override val error = IllegalStateException("denied")

    override fun isErrorForResolver(ctx: CheckerResultContext): Boolean = true

    override fun combine(fieldResult: CheckerResult.Error): CheckerResult.Error = this
}
