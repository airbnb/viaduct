package viaduct.engine.runtime2.contract

import viaduct.engine.runtime2.arbitrary.ResolutionWitness
import viaduct.engine.runtime2.arbitrary.ResolverProgramKind

internal fun constructionWitnessCoverage(): ResolverPropertyCoverage {
    var inputSensitiveApplications = 0
    var argumentSensitiveApplications = 0
    var exactAliasCases = 0
    var activatedExactAliasCases = 0
    var activatedDistinctArgumentCases = 0
    var generatedFromArgumentVariables = 0
    return ResolverPropertyCoverage(
        record = { observation ->
            val testCase = observation.testCase
            val registry = testCase.registry
            val witness = ResolutionWitness(observation.ordinaryApplications)
            generatedFromArgumentVariables +=
                testCase.registry.features.fromArgumentVariableCount
            witness.applications.forEach { application ->
                when (registry.resolverProgram(application.key.field)) {
                    ResolverProgramKind.INPUT_SENSITIVE ->
                        inputSensitiveApplications += 1
                    ResolverProgramKind.ARGUMENT_SENSITIVE ->
                        argumentSensitiveApplications += 1
                    ResolverProgramKind.INPUT_AND_ARGUMENT_SENSITIVE -> {
                        inputSensitiveApplications += 1
                        argumentSensitiveApplications += 1
                    }
                    ResolverProgramKind.CONSTANT -> Unit
                }
            }
            if (testCase.query.features.hasExactKeyAliasConvergence) {
                exactAliasCases += 1
            }
            if (
                witness.applications.any { application ->
                    application.key.field in
                        testCase.query.features.exactKeyAliasSourceFields
                }
            ) {
                activatedExactAliasCases += 1
            }
            if (
                witness.applications
                    .filter { application ->
                        application.key.field in
                            testCase.query.features.distinctArgumentSourceFields
                    }.groupBy { application -> application.key.field }
                    .any { (_, applications) ->
                        applications.map { application -> application.key.arguments }
                            .distinct()
                            .size > 1
                    }
            ) {
                activatedDistinctArgumentCases += 1
            }
        },
        requireCovered = { run ->
            run.requireCoverage(
                inputSensitiveApplications >= 10,
                "Too few input-sensitive applications: $inputSensitiveApplications",
            )
            run.requireCoverage(
                argumentSensitiveApplications >= 10,
                "Too few argument-sensitive applications: $argumentSensitiveApplications",
            )
            run.requireCoverage(
                exactAliasCases >= 10,
                "Too few exact-alias cases: $exactAliasCases",
            )
            run.requireCoverage(
                activatedExactAliasCases >= 10,
                "Too few activated exact-alias cases: $activatedExactAliasCases",
            )
            run.requireCoverage(
                activatedDistinctArgumentCases >= 10,
                "Too few activated distinct-argument cases: $activatedDistinctArgumentCases",
            )
            run.requireCoverage(
                generatedFromArgumentVariables > 0,
                "Generated no FromArgument variables",
            )
        },
    )
}
