package semantics.correctresolution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import model.ObjectEngineResult
import model.Promise
import model.ResolverOccurrenceId
import model.RootFieldReferenceData
import model.emptyFragmentOf
import model.fragmentFrom
import model.materializeSelectionForestOf
import model.merge
import model.operationSelectionsFrom
import model.registry.ResolverFragmentTemplates
import model.registry.TypeCheckerResolver
import model.requireObjectField
import model.requireQueryTypeDef
import model.selectionForestOf
import model.testing.TestWorld
import model.testing.fieldResolverOf
import semantics.shared.OEROccurrence
import semantics.shared.RootFieldReferenceInvocationObservation
import semantics.shared.SharedOperationContext
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.CheckerResultContext

/** Mutates the independent Query result without changing the reference's published value. */
class ReferenceQueryTypeCheckerCorrectResolutionTest {
    @Test
    fun `reference Query root requires its own type result`() {
        assertTrue(accepted(CheckerResult.Success))
        assertFalse(accepted(null), "The primary root's successful check cannot cover an independently executed Query root")
    }

    @Test
    fun `reference Query root requires its checker inputs even when its type result is missing`() {
        assertFalse(accepted(null, includePolicy = false))
    }

    @Test
    fun `reference Query root must agree with its checker relation`() {
        assertFalse(accepted(QueryDenial()), "Correct selected field values cannot justify a forged root type result")
    }

    @Test
    fun `associated Query root has no incoming type-check requirement`() {
        assertTrue(accepted(null, includePolicy = false, reference = false))
    }

    @Test
    fun `independent Query root remains checked when all its input fields are excluded`() {
        assertTrue(accepted(CheckerResult.Success, excludeDependency = true))
        assertFalse(accepted(null, includePolicy = false, excludeDependency = true))
    }

    @Test
    fun `cached raw validation cannot discharge an independent root check`() {
        var checkerReplays = 0
        val world = TestWorld.fromSDL(
            "type Query { dependency: Int! }",
            fieldResolvers = { schema ->
                mapOf(schema.requireObjectField("Query", "dependency") to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 7 })
            },
            typeCheckers = { schema ->
                val query = schema.requireQueryTypeDef()
                mapOf(
                    query to TypeCheckerResolver.of(query, query) { _, _ ->
                        checkerReplays++
                        CheckerResult.Success
                    },
                )
            },
        ).assumptions
        val query = world.schema.requireQueryTypeDef()
        val operation = SharedOperationContext.create(world)
        val primary = ObjectEngineResult.of(query)
        val witness = operation.rootFieldReferenceWitness(primary)
        val state = QueryOERValidationState()
        val cache = state.replayCache(primary, witness)
        val key = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", "dependency"), emptyMap())
        val selections = world.operationSelectionsFrom("{ dependency }").merge(query)
        for (typeResult in listOf(null, CheckerResult.Success)) {
            val nested = ObjectEngineResult.of(query, values = mapOf(key to 7), typeCheckerResult = Promise.of(typeResult))
            assertTrue(cache.queryResultConforms(operation, nested, selections, selectionsAreChecked = false))
            assertEquals(typeResult != null, cache.queryResultConforms(operation, nested, selections, rootIsChecked = true))
            // Exercise the recursion path where the cached result is also the replay cache's root.
            val nestedCache = state.replayCache(nested, witness)
            assertEquals(typeResult != null, nestedCache.queryResultConforms(operation, nested, selections, rootIsChecked = true))
            assertTrue(cache.queryResultConforms(operation, nested, selections), "Associated-root obligations stay independent")
        }
        assertEquals(1, checkerReplays, "Repeated checked reads share one relation replay per OER")
    }

    private fun accepted(
        nestedTypeResult: CheckerResult?,
        includePolicy: Boolean = true,
        reference: Boolean = true,
        excludeDependency: Boolean = false,
    ): Boolean {
        val world = TestWorld.fromSDL(
            "type Query { reference: Int! ordinary: Int! target: Int! dependency: Int! policy: Int! }",
            fieldResolvers = { schema ->
                val empty = schema.emptyFragmentOf("Query")
                val target = schema.requireObjectField("Query", "target")
                buildMap {
                    put(schema.requireObjectField("Query", "reference"), fieldResolverOf(empty) { _, _ -> RootFieldReferenceData.of(listOf(target), emptyMap()) })
                    listOf("ordinary", "target").forEach { name ->
                        val selection = if (excludeDependency) "dependency @skip(if: true)" else "dependency"
                        put(
                            schema.requireObjectField("Query", name),
                            fieldResolverOf(empty, schema.fragmentFrom("fragment Input on Query { $selection }")) { _, query, _ ->
                                if (excludeDependency) 7 else query.get("dependency")
                            },
                        )
                    }
                    put(schema.requireObjectField("Query", "dependency"), fieldResolverOf(empty) { _, _ -> 7 })
                    put(schema.requireObjectField("Query", "policy"), fieldResolverOf(empty) { _, _ -> 11 })
                }
            },
            typeCheckers = { schema ->
                val query = schema.requireQueryTypeDef()
                mapOf(
                    query to TypeCheckerResolver.of(
                        query,
                        query,
                        mapOf(
                            "input" to ResolverFragmentTemplates(
                                schema.fragmentFrom("fragment Input on Query { policy }").materializeSelections,
                                materializeSelectionForestOf(),
                            ),
                        ),
                    ) { _, _ -> CheckerResult.Success },
                )
            },
        ).assumptions
        val query = world.schema.requireQueryTypeDef()

        fun key(name: String) = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", name), emptyMap())
        val selected = key(if (reference) "reference" else "ordinary")
        val root = ObjectEngineResult.of(
            query,
            values = mapOf(selected to 7, key("policy") to 11),
            typeCheckerResult = Promise.of(CheckerResult.Success),
        )
        val nested = ObjectEngineResult.of(
            query,
            values = buildMap {
                if (!excludeDependency) put(key("dependency"), 7)
                if (includePolicy) put(key("policy"), 11)
            },
            typeCheckerResult = Promise.of(nestedTypeResult),
        )
        val observer = CorrectnessResolverObserver()
        if (reference) {
            val invocationRoot = ObjectEngineResult.of(query)
            val target = key("target")
            observer.onIndependentQueryFragmentPrepared(ResolverOccurrenceId.at(invocationRoot, listOf(target)), nested)
            observer.onRootFieldReferenceInvocation(
                RootFieldReferenceInvocationObservation(
                    publicationRoot = root,
                    publicationPath = listOf(selected),
                    reference = RootFieldReferenceData.of(listOf(target.field), emptyMap()),
                    invocationRoot = invocationRoot,
                    invocationPath = listOf(target),
                    invocationKey = target,
                    suppliedDemand = selectionForestOf(),
                ),
            )
        } else {
            observer.onQueryFragmentPrepared(ResolverOccurrenceId.at(root, listOf(selected)), nested, OEROccurrence(root, emptyList(), root))
        }
        val operation = SharedOperationContext.create(world, resolverObserver = observer)
        return root.correctResolution(operation, world.operationSelectionsFrom("{ ${selected.field.name} }").merge(query))
    }
}

private class QueryDenial : CheckerResult.Error {
    override val error = IllegalStateException("denied")

    override fun isErrorForResolver(ctx: CheckerResultContext): Boolean = true

    override fun combine(fieldResult: CheckerResult.Error): CheckerResult.Error = this
}
