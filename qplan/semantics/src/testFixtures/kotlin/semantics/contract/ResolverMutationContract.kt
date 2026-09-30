package semantics.contract

import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.next
import kotlin.test.Test
import kotlin.test.assertTrue
import model.fragmentFrom
import model.objectOf
import model.sameCompletedResultAs
import semantics.arbitrary.Config
import semantics.arbitrary.DuplicateSelectionWeight
import semantics.arbitrary.ExplicitFieldResolverWeight
import semantics.arbitrary.FieldArgumentWeight
import semantics.arbitrary.NodeResolversEnabled
import semantics.arbitrary.ObjectFieldCount
import semantics.arbitrary.ResolverFragmentWeight
import semantics.arbitrary.ResolverFragmentsEnabled
import semantics.arbitrary.ResolverFromArgumentVariablesEnabled
import semantics.arbitrary.ResolverProgramMutation
import semantics.arbitrary.ResolverVariableWeight
import semantics.arbitrary.SchemaObjectCount
import semantics.arbitrary.TestCaseCount
import semantics.arbitrary.resolverTestBatch
import semantics.correctresolution.correctResolution

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
