@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.correctresolution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import viaduct.engine.runtime2.arbitrary.BoundedRecorder
import viaduct.engine.runtime2.arbitrary.FieldCoordinate
import viaduct.engine.runtime2.arbitrary.ResolutionOccurrenceWitness
import viaduct.engine.runtime2.arbitrary.ResolverOccurrenceApplicationRecord
import viaduct.engine.runtime2.contract.registeredResolverOccurrenceApplicationIdentityCounts
import viaduct.engine.runtime2.contract.selectionValues
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.Assumptions
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.engineObjectDataOf
import viaduct.engine.runtime2.model.materializeSelectionForestOf
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.emptyFragmentOf
import viaduct.engine.runtime2.model.testing.engineResultOf
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.resolution.framework.OEROccurrence
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation
import viaduct.engine.runtime2.resolution.framework.SharedOERContext
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolution.framework.fieldResolverCycleTask
import viaduct.engine.runtime2.resolution.framework.materializeResult

/** Retained review regression: reconstructing a scope carrier must not create a new semantic scope. */
class ScopeTokenIdentityAdversarialReviewTest {
    @Test
    fun `separate Query roots for the same containing occurrence are rejected with fresh scope wrappers`() {
        val worldFixture = TestWorld.fromSDL(
            schemaSDL = "type Query { left: Int! right: Int! source: Int! }",
            fieldResolvers = { schema ->
                buildMap {
                    listOf("left", "right").forEach { name ->
                        put(
                            schema.loweredSchema.requireObjectField("Query", name),
                            fieldResolverOf(
                                schema.loweredSchema.emptyFragmentOf("Query"),
                                schema.fragmentFrom("fragment Input on Query { source }"),
                            ) { _, query, _ -> query.selectionValues().getValue("source") },
                        )
                    }
                    put(
                        schema.loweredSchema.requireObjectField("Query", "source"),
                        fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 7 },
                    )
                }
            },
        )
        val world = worldFixture.assumptions
        val queryType = worldFixture.schema.requireQueryTypeDef()
        val result = world.engineResultOf("Query") {
            "left" resolvesTo 7
            "right" resolvesTo 7
        }
        val resultDemand = worldFixture.schemas.fragmentFrom("fragment Result on Query { left right }").subselections.merge(queryType)
        val queryDemand = worldFixture.schemas.fragmentFrom("fragment Input on Query { source }").subselections.merge(queryType)
        val queries = listOf(world.engineResultOf("Query") { "source" resolvesTo 7 }, world.engineResultOf("Query") { "source" resolvesTo 7 })

        fun observe(reuseScopeWrapper: Boolean): CorrectnessResolverObserver {
            val observer = CorrectnessResolverObserver()
            val sharedWrapper = OEROccurrence(result, emptyList(), result)
            listOf("left", "right").zip(queries).forEach { (name, query) ->
                val key = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", name), emptyMap())
                val owner = ResolverOccurrenceId.at(result, listOf(key))
                val containing = if (reuseScopeWrapper) sharedWrapper else OEROccurrence(result, emptyList(), result)
                observer.onQueryOERPrepared(
                    SharedOERContext(OEROccurrence(query, emptyList(), query), engineObjectDataOf(queryType), queryDemand),
                    queryOERDepth = 1,
                )
                observer.onQueryFragmentPrepared(owner, query, containing)
                observer.onQueryFragmentOwnerAddress(owner, containing, key)
            }
            return observer
        }

        // Control: the same invalid per-owner policy is rejected when callers retain one carrier.
        assertFalse(
            observe(reuseScopeWrapper = true).queryFragmentOwnershipIsConsistent(emptySet()),
        )
        val observer = observe(reuseScopeWrapper = false)
        val witness = recordApplications(world, observer, listOf(result) + queries)
        val operation = SharedOperationContext.create(world, resolverObserver = observer)
        assertTrue(result.correctResolution(operation, resultDemand), "The mutation preserves all values and declared input projections")
        val accepted = runCatching {
            // A per-owner execution runs source twice; reconstruction currently blesses all four
            // ordinary occurrences, even though singular ownership requires three applications.
            assertEquals(4, result.registeredResolverOccurrenceApplicationIdentityCounts(operation).values.sum())
            result.registeredResolverOccurrenceApplicationIdentityCounts(operation) == witness.applicationIdentityCounts()
        }.getOrElse { false }
        assertFalse(accepted, "Two structurally identical containing occurrences must own one shared Query result regardless of carrier allocation")
    }

    @Test
    fun `a resolver on the associated Query root cannot claim a new containing scope`() {
        val worldFixture = TestWorld.fromSDL(
            schemaSDL = "type Query { answer: Int! middle: Int! source: Int! }",
            fieldResolvers = { schema ->
                buildMap {
                    listOf("answer" to "middle", "middle" to "source").forEach { (name, dependency) ->
                        put(
                            schema.loweredSchema.requireObjectField("Query", name),
                            fieldResolverOf(
                                schema.loweredSchema.emptyFragmentOf("Query"),
                                schema.fragmentFrom("fragment Input on Query { $dependency }"),
                            ) { _, query, _ -> query.selectionValues().getValue(dependency) }
                        )
                    }
                    put(schema.loweredSchema.requireObjectField("Query", "source"), fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 7 })
                }
            },
        )
        val world = worldFixture.assumptions
        val queryType = worldFixture.schema.requireQueryTypeDef()
        val result = world.engineResultOf("Query") { "answer" resolvesTo 7 }
        val firstQuery = world.engineResultOf("Query") { "middle" resolvesTo 7 }
        val secondQuery = world.engineResultOf("Query") { "source" resolvesTo 7 }
        val resultDemand = worldFixture.schemas.fragmentFrom("fragment Result on Query { answer }").subselections.merge(queryType)
        val sharedQuery = world.engineResultOf("Query") {
            "middle" resolvesTo 7
            "source" resolvesTo 7
        }
        val control = CorrectnessResolverObserver()
        val controlScope = OEROccurrence(result, emptyList(), result)
        control.onQueryOERPrepared(
            SharedOERContext(
                OEROccurrence(sharedQuery, emptyList(), sharedQuery),
                engineObjectDataOf(queryType),
                worldFixture.schemas.fragmentFrom("fragment Input on Query { middle source }").subselections.merge(queryType)
            ),
            queryOERDepth = 1,
        )
        listOf(result to "answer", sharedQuery to "middle").forEach { (root, name) ->
            val key = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", name), emptyMap())
            val owner = ResolverOccurrenceId.at(root, listOf(key))
            control.onQueryFragmentPrepared(owner, sharedQuery, controlScope)
            control.onQueryFragmentOwnerAddress(owner, OEROccurrence(root, emptyList(), root), key)
        }
        val controlWitness = recordApplications(world, control, listOf(result, sharedQuery))
        val controlOperation = SharedOperationContext.create(world, resolverObserver = control)
        assertTrue(result.correctResolution(controlOperation, resultDemand))
        assertEquals(controlWitness.applicationIdentityCounts(), result.registeredResolverOccurrenceApplicationIdentityCounts(controlOperation))

        val observer = CorrectnessResolverObserver()
        listOf(Triple(result, "answer", firstQuery), Triple(firstQuery, "middle", secondQuery)).forEach { (root, name, query) ->
            val key = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", name), emptyMap())
            val owner = ResolverOccurrenceId.at(root, listOf(key))
            val containing = OEROccurrence(root, emptyList(), root)
            val selected = if (name == "answer") "middle" else "source"
            observer.onQueryOERPrepared(
                SharedOERContext(
                    OEROccurrence(query, emptyList(), query),
                    engineObjectDataOf(queryType),
                    worldFixture.schemas.fragmentFrom("fragment Input on Query { $selected }").subselections.merge(queryType)
                ),
                queryOERDepth = if (name == "answer") 1 else 2,
            )
            observer.onQueryFragmentPrepared(owner, query, containing)
            observer.onQueryFragmentOwnerAddress(owner, containing, key)
        }
        val witness = recordApplications(world, observer, listOf(result, firstQuery, secondQuery))
        assertEquals(3, witness.applications.size)
        val operation = SharedOperationContext.create(world, resolverObserver = observer)
        assertTrue(result.correctResolution(operation, resultDemand), "This misplaced root boundary preserves values")
        val accepted = runCatching {
            result.registeredResolverOccurrenceApplicationIdentityCounts(operation) == witness.applicationIdentityCounts()
        }.getOrElse { false }
        assertFalse(accepted, "Query-side ordinary resolvers must read the already associated Query root, not introduce another scope")
    }

    private fun recordApplications(
        world: Assumptions,
        observer: CorrectnessResolverObserver,
        roots: List<ObjectEngineResult>,
    ): ResolutionOccurrenceWitness {
        val log = BoundedRecorder<ResolverOccurrenceApplicationRecord>()
        roots.forEach { root ->
            root.keys.forEach { key ->
                val owner = ResolverOccurrenceId.at(root, listOf(key))
                val arguments = key.arguments as Arguments.Resolved
                val input = engineObjectDataOf(root.type)
                val resolver = world.resolverRegistry.resolver(key.field)
                val fragments = resolver.instantiateFragments(owner)
                val queryInputSelections =
                    resolver.instantiateQueryMaterializationSelections(
                        fragments.queryFragment.resolverOccurrenceId,
                    )
                val queryValue =
                    observer.queryFragmentResults(owner).singleOrNull()?.let { queryRoot ->
                        runBlocking {
                            queryRoot.materializeResult(
                                operation = SharedOperationContext.create(world),
                                selections = queryInputSelections,
                                reader = root.fieldResolverCycleTask(listOf(key)),
                            )
                        }
                    } ?: engineObjectDataOf(world.schema.requireQueryTypeDef())
                observer.onResolverInvocation(
                    ResolverInvocationObservation(
                        occurrencePath = listOf(key),
                        field = key.field,
                        input = input,
                        inputSelections = materializeSelectionForestOf(),
                        queryValue = queryValue,
                        queryInputSelections = queryInputSelections,
                        arguments = arguments,
                        suppliedDemand = null,
                        resolverOccurrenceId = owner,
                    ),
                )
                log.record(
                    ResolverOccurrenceApplicationRecord.capture(
                        resolverOccurrenceId = owner,
                        occurrencePath = listOf(key),
                        field = FieldCoordinate(key.field.containingDef.name, key.field.name),
                        arguments = arguments,
                        input = input,
                        suppliedDemand = null,
                    ),
                )
            }
        }
        return ResolutionOccurrenceWitness(log.snapshot())
    }
}
