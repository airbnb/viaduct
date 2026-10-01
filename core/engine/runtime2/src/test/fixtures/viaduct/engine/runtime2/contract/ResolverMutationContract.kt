package viaduct.engine.runtime2.contract

import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.next
import kotlin.test.Test
import kotlin.test.assertTrue
import viaduct.engine.runtime2.arbitrary.Config
import viaduct.engine.runtime2.arbitrary.DuplicateSelectionWeight
import viaduct.engine.runtime2.arbitrary.ExplicitFieldResolverWeight
import viaduct.engine.runtime2.arbitrary.FieldArgumentWeight
import viaduct.engine.runtime2.arbitrary.NodeResolversEnabled
import viaduct.engine.runtime2.arbitrary.ObjectFieldCount
import viaduct.engine.runtime2.arbitrary.ResolverFragmentWeight
import viaduct.engine.runtime2.arbitrary.ResolverFragmentsEnabled
import viaduct.engine.runtime2.arbitrary.ResolverFromArgumentVariablesEnabled
import viaduct.engine.runtime2.arbitrary.ResolverProgramMutation
import viaduct.engine.runtime2.arbitrary.ResolverVariableWeight
import viaduct.engine.runtime2.arbitrary.SchemaObjectCount
import viaduct.engine.runtime2.arbitrary.TestCaseCount
import viaduct.engine.runtime2.arbitrary.resolverTestBatch
import viaduct.engine.runtime2.correctresolution.correctResolution
import viaduct.engine.runtime2.model.sameCompletedResultAs
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.testing.objectOf

/** Mutation sensitivity of generated resolver properties. */
interface ResolverMutationContract : ResolverContract {
    @Test
    fun `generated properties reject independent resolver program mutations`() {
        val counts = TestCaseCount(schemas = 12, registriesPerSchema = 3, queriesPerSchema = 5)
        val config =
            Config.default +
                (SchemaObjectCount to 4..6) +
                (ObjectFieldCount to 4..6) +
                (FieldArgumentWeight to 0.9) +
                (ExplicitFieldResolverWeight to 0.85) +
                (DuplicateSelectionWeight to 1.0) +
                (ResolverFragmentsEnabled to true) +
                (ResolverFragmentWeight to 1.0) +
                (ResolverFromArgumentVariablesEnabled to true) +
                (ResolverVariableWeight to 1.0) +
                (NodeResolversEnabled to false)
        val random = RandomSource.seeded(303_303L)
        val killed = ResolverProgramMutation.entries.associateWith { 0 }.toMutableMap()
        val exercised = ResolverProgramMutation.entries.associateWith { 0 }.toMutableMap()
        var generatedFromArgumentVariables = 0

        repeat(counts.schemas) {
            val batch = Arb.resolverTestBatch(counts, config).next(random)
            batch.registries.forEach { registry ->
                generatedFromArgumentVariables += registry.features.fromArgumentVariableCount
                val ordinaryWorld = registry.world(batch.schema)

                batch.queries.forEach { query ->
                    val ordinaryAssumptions = ordinaryWorld.newAssumptions()
                    val ordinaryFragment =
                        ordinaryWorld.schemas.fragmentFrom(query.source)
                    registry.clearResolutionWitness()
                    val ordinaryResolution =
                        observeResolution(
                            ordinaryAssumptions,
                            ordinaryAssumptions.objectOf("Query"),
                            ordinaryFragment.subselections,
                            resolverObserver = registry.resolverObserver(),
                        )
                    val ordinary = ordinaryResolution.result
                    assertTrue(
                        ordinary.correctResolution(
                            ordinaryResolution.operation,
                            ordinaryFragment,
                        ),
                    )

                    ResolverProgramMutation.entries
                        .filterNot { it == ResolverProgramMutation.NONE }
                        .forEach { mutation ->
                            val mutantWorld =
                                registry.world(
                                    schema = batch.schema,
                                    resolverProgramMutation = mutation,
                                )
                            val mutantAssumptions = mutantWorld.newAssumptions()
                            registry.clearResolutionWitness()
                            val mutantResult =
                                runCatching {
                                    val fragment = mutantWorld.schemas.fragmentFrom(query.source)
                                    observeResolution(
                                        mutantAssumptions,
                                        mutantAssumptions.objectOf("Query"),
                                        fragment.subselections,
                                        resolverObserver = registry.resolverObserver(resolverProgramMutation = mutation),
                                    )
                                }
                            val witness = registry.resolutionWitness()
                            if (witness.applications.isNotEmpty()) {
                                exercised[mutation] = exercised.getValue(mutation) + 1
                            }
                            val rejected =
                                mutantResult.fold(
                                    onSuccess = { resolution ->
                                        val result = resolution.result
                                        when (mutation) {
                                            ResolverProgramMutation.DUPLICATE_APPLICATION ->
                                                witness.applicationIdentityCounts() !=
                                                    result
                                                        .registeredResolverApplicationIdentityCounts(resolution.operation)
                                            else -> !result.sameCompletedResultAs(ordinary)
                                        }
                                    },
                                    onFailure = { true },
                                )
                            if (rejected) {
                                killed[mutation] = killed.getValue(mutation) + 1
                            }
                        }
                }
            }
        }

        ResolverProgramMutation.entries
            .filterNot { it == ResolverProgramMutation.NONE }
            .forEach { mutation ->
                assertTrue(
                    exercised.getValue(mutation) >= 100,
                    "$mutation was exercised too rarely: ${exercised.getValue(mutation)}",
                )
                assertTrue(
                    killed.getValue(mutation) >= 10,
                    "$mutation survived too often: killed=${killed.getValue(mutation)}",
                )
            }
        assertTrue(generatedFromArgumentVariables > 0)
    }
}
