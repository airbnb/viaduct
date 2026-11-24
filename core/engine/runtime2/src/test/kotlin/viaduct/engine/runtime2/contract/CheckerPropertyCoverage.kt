package viaduct.engine.runtime2.contract

import org.junit.jupiter.api.Assertions.assertTrue
import viaduct.engine.runtime2.arbitrary.GeneratedFieldCheckerMode
import viaduct.engine.runtime2.arbitrary.GeneratedTypeCheckerMode
import viaduct.engine.runtime2.propertytest.ResolverTestSuiteDefinition

internal fun checkerPropertyCoverage(definition: ResolverTestSuiteDefinition): ResolverPropertyCoverage {
    if (definition.typeCheckerMode != GeneratedTypeCheckerMode.NONE) {
        val coverage = GeneratedTypeCheckerCoverage()
        return ResolverPropertyCoverage(
            record = { coverage.record(it.ordinary) },
            requireCovered = { run ->
                coverage.requireCovered(run, definition.typeCheckerMode)
                println("Type-checker coverage profile=${definition.profile} seed=${run.seed} cases=${run.attemptedCases} ${coverage.summary()}")
            },
        )
    }
    require(definition.fieldCheckerMode != GeneratedFieldCheckerMode.NONE) { "No coverage requirements defined for ${definition.id}" }
    val passive = definition.profile.endsWith("-passive")
    val rootReference = definition.profile.endsWith("-root-reference")
    val required = when {
        passive -> setOf(GeneratedFieldCheckerCoverageSignature.FIELD_CHECKER, GeneratedFieldCheckerCoverageSignature.PASSIVE_CHECKED_FIELD)
        rootReference -> setOf(GeneratedFieldCheckerCoverageSignature.FIELD_CHECKER, GeneratedFieldCheckerCoverageSignature.ROOT_FIELD_REFERENCE_RESULT)
        else -> FIELD_CHECKER_SIGNATURES + buildSet {
            when (definition.fieldCheckerMode) {
                GeneratedFieldCheckerMode.SUCCESS, GeneratedFieldCheckerMode.RUNTIME_SUCCESS -> add(GeneratedFieldCheckerCoverageSignature.CHECKER_SUCCESS)
                GeneratedFieldCheckerMode.DENIAL, GeneratedFieldCheckerMode.RUNTIME_DENIAL -> add(GeneratedFieldCheckerCoverageSignature.CHECKER_DENIAL)
                else -> {
                    add(GeneratedFieldCheckerCoverageSignature.CHECKER_SUCCESS)
                    add(GeneratedFieldCheckerCoverageSignature.CHECKER_DENIAL)
                    add(GeneratedFieldCheckerCoverageSignature.RESOLVER_WITHOUT_CHECKER)
                }
            }
            if (definition.fieldCheckerMode.runtimeVariables) {
                add(GeneratedFieldCheckerCoverageSignature.FROM_OBJECT_FIELD_VARIABLE)
                add(GeneratedFieldCheckerCoverageSignature.FROM_QUERY_FIELD_VARIABLE)
                add(GeneratedFieldCheckerCoverageSignature.FROM_PROVIDER_VARIABLE)
                add(GeneratedFieldCheckerCoverageSignature.SYMBOLIC_CHECKER_KEY)
            }
        }
    }
    val coverage = GeneratedFieldCheckerCoverage(definition.fieldCheckerMode)
    return ResolverPropertyCoverage(
        record = { observation ->
            if (!passive && !rootReference) {
                assertTrue(observation.testCase.registry.nodeResolverTypes.isEmpty())
                assertTrue(observation.testCase.registry.generatedFieldCheckerCoordinates.isNotEmpty())
            }
            coverage.record(observation.testCase.registry, observation)
        },
        requireCovered = { run ->
            coverage.requireCovered(run, required)
            println("Field-checker coverage profile=${definition.profile} seed=${run.seed} ${coverage.summary()}")
        },
    )
}

private val FIELD_CHECKER_SIGNATURES =
    setOf(
        GeneratedFieldCheckerCoverageSignature.FIELD_CHECKER,
        GeneratedFieldCheckerCoverageSignature.NONEMPTY_OBJECT_FRAGMENT,
        GeneratedFieldCheckerCoverageSignature.NONEMPTY_QUERY_FRAGMENT,
        GeneratedFieldCheckerCoverageSignature.FROM_ARGUMENT_VARIABLE,
        GeneratedFieldCheckerCoverageSignature.NESTED_FROM_ARGUMENT_VARIABLE,
        GeneratedFieldCheckerCoverageSignature.NULLABLE_FROM_ARGUMENT_TRAVERSAL,
        GeneratedFieldCheckerCoverageSignature.PARENT_READING_RESOLVER_INPUT,
        GeneratedFieldCheckerCoverageSignature.DUPLICATE_NAMED_PAIR_PROJECTIONS,
        GeneratedFieldCheckerCoverageSignature.EMPTY_NAMED_PAIR,
        GeneratedFieldCheckerCoverageSignature.CHECKER_ONLY_OBJECT_DEMAND,
        GeneratedFieldCheckerCoverageSignature.CHECKER_ONLY_QUERY_DEMAND,
        GeneratedFieldCheckerCoverageSignature.SHARED_QUERY_OER_MULTIPLE_OWNERS,
        GeneratedFieldCheckerCoverageSignature.CHECKER_IN_ASSOCIATED_QUERY_OER,
        GeneratedFieldCheckerCoverageSignature.LIST_ELEMENT_OCCURRENCE,
        GeneratedFieldCheckerCoverageSignature.REPEATED_CHECKER_COORDINATE,
        GeneratedFieldCheckerCoverageSignature.ARGUMENT_DISTINCT_OCCURRENCES,
    )
