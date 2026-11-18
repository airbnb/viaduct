package viaduct.engine.runtime2.correctresolution

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.CheckerResultContext
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.Promise
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.RootFieldReferenceData
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.selectionForestOf
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.emptyFragmentOf
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.resolution.framework.OEROccurrence
import viaduct.engine.runtime2.resolution.framework.RootFieldReferenceInvocationObservation
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.schema.operationSelectionsFrom

/** Mutates the independent Query result without changing the reference's published value. */
class ReferenceQueryTypeCheckerCorrectResolutionTest {
    @Test
    fun `reference Query roots require no type checker result`() {
        assertTrue(accepted(null, includePolicy = false))
        assertFalse(accepted(CheckerResult.Success, includePolicy = false))
        assertFalse(accepted(QueryDenial(), includePolicy = false))
    }

    @Test
    fun `associated Query roots have no type checker result`() {
        assertTrue(accepted(null, includePolicy = false, reference = false))
    }

    @Test
    fun `independent Query roots with excluded inputs need no type check`() {
        assertTrue(accepted(null, includePolicy = false, excludeDependency = true))
    }

    private fun accepted(
        nestedTypeResult: CheckerResult?,
        includePolicy: Boolean = true,
        reference: Boolean = true,
        excludeDependency: Boolean = false,
    ): Boolean {
        val worldFixture = TestWorld.fromSDL(
            "type Query { reference: Int! ordinary: Int! target: Int! dependency: Int! policy: Int! }",
            fieldResolvers = { schema ->
                val empty = schema.loweredSchema.emptyFragmentOf("Query")
                val target = schema.loweredSchema.requireObjectField("Query", "target")
                buildMap {
                    put(schema.loweredSchema.requireObjectField("Query", "reference"), fieldResolverOf(empty) { _, _ -> RootFieldReferenceData.of(listOf(target), emptyMap()) })
                    listOf("ordinary", "target").forEach { name ->
                        val selection = if (excludeDependency) "dependency @skip(if: true)" else "dependency"
                        put(
                            schema.loweredSchema.requireObjectField("Query", name),
                            fieldResolverOf(empty, schema.fragmentFrom("fragment Input on Query { $selection }")) { _, query, _ ->
                                if (excludeDependency) 7 else query.get("dependency")
                            },
                        )
                    }
                    put(schema.loweredSchema.requireObjectField("Query", "dependency"), fieldResolverOf(empty) { _, _ -> 7 })
                    put(schema.loweredSchema.requireObjectField("Query", "policy"), fieldResolverOf(empty) { _, _ -> 11 })
                }
            },
        )
        val world = worldFixture.assumptions
        val query = worldFixture.schema.requireQueryTypeDef()

        fun key(name: String) = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", name), emptyMap())
        val selected = key(if (reference) "reference" else "ordinary")
        val root = ObjectEngineResult.of(
            query,
            values = mapOf(selected to 7, key("policy") to 11),
            typeCheckerResult = Promise.of(null),
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
                )
            )
        } else {
            observer.onQueryFragmentPrepared(ResolverOccurrenceId.at(root, listOf(selected)), nested, OEROccurrence(root, emptyList(), root))
        }
        val operation = SharedOperationContext.create(world, resolutionObserver = observer)
        return root.correctResolution(operation, worldFixture.schemas.operationSelectionsFrom("{ ${selected.field.name} }").merge(query))
    }
}

private class QueryDenial : CheckerResult.Error {
    override val error = IllegalStateException("denied")

    override fun isErrorForResolver(ctx: CheckerResultContext): Boolean = true

    override fun combine(fieldResult: CheckerResult.Error): CheckerResult.Error = this
}
