package viaduct.engine.runtime2.contract

import viaduct.engine.runtime2.arbitrary.ResolverApplicationRecord
import viaduct.engine.runtime2.arbitrary.allowedResolverClosure
import viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver
import viaduct.engine.runtime2.correctresolution.conformsToResolvers
import viaduct.engine.runtime2.correctresolution.conformsToSelections
import viaduct.engine.runtime2.correctresolution.correctResolution
import viaduct.engine.runtime2.correctresolution.isClosedUnderResolverDemand
import viaduct.engine.runtime2.correctresolution.rootedAndWellTyped
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.sameCompletedResultAs
import viaduct.engine.runtime2.resolution.framework.CheckerKind

/** One independently selectable judgment over a completed generated case. */
fun interface GeneratedCaseAssertion {
    fun assertThat(observation: GeneratedCaseObservation)
}

/** Independent judgments shared by property suites and oracle sensitivity tests. */
object GeneratedCaseAssertions {
    val correctResolution: GeneratedCaseAssertion =
        GeneratedCaseAssertion { observation ->
            observation.executions.forEach { execution ->
                val correct = execution.result.correctResolution(execution.operation, execution.fragment)
                if (!correct) {
                    fun diagnostic(
                        name: String,
                        value: () -> Any?,
                    ): String =
                        "$name=" +
                            runCatching(value).fold(
                                onSuccess = Any?::toString,
                                onFailure = { failure ->
                                    "${failure::class.simpleName}: ${failure.message}"
                                },
                            )

                    listOf(
                        diagnostic("rootedAndWellTyped") {
                            execution.result.rootedAndWellTyped(execution.world)
                        },
                        diagnostic("conformsToSelections") {
                            execution.result.conformsToSelections(
                                execution.operation,
                                execution.fragment.subselections,
                            )
                        },
                        diagnostic("isClosedUnderResolverDemand") {
                            execution.result.isClosedUnderResolverDemand(execution.operation)
                        },
                        diagnostic("unclosedResolverOccurrences") {
                            execution.result.unclosedRegisteredResolverOccurrences(execution.operation)
                        },
                        diagnostic("conformsToResolvers") {
                            execution.result.conformsToResolvers(execution.operation)
                        },
                    ).joinToString(separator = "\n")
                        .let { diagnostics ->
                            error(diagnostics)
                        }
                }
            }
        }

    val permutationEquivalentResult =
        GeneratedCaseAssertion { observation ->
            val permuted = requireNotNull(observation.permutationEquivalent) { "Permutation comparison requires executing both queries" }
            check(
                observation.ordinary.result.sameCompletedResultAs(
                    permuted.result,
                ),
            ) { "Permutation-equivalent queries produced different results" }
        }

    val exactOrdinaryApplicationCounts =
        GeneratedCaseAssertion { observation ->
            val expected =
                observation.ordinary.result.registeredResolverApplicationIdentityCounts(observation.ordinary.operation)
            checkAgreement(
                expected,
                observation.ordinaryApplications
                    .groupingBy(ResolverApplicationRecord::identity)
                    .eachCount(),
            )
        }

    val boundedOrdinaryApplicationCounts =
        GeneratedCaseAssertion { observation ->
            val occurrences = observation.ordinary.result
                .registeredResolverOccurrenceApplicationKeyCounts(observation.ordinary.operation)
                .entries.groupingBy { it.key.applicationKey }.fold(0) { count, entry -> count + entry.value }
            observation.ordinaryApplications.groupingBy(ResolverApplicationRecord::key).eachCount().forEach { (key, count) ->
                check(count <= occurrences.getOrDefault(key, 0)) { "Observed $count applications of $key but result contains only ${occurrences.getOrDefault(key, 0)} occurrences" }
            }
        }

    val selectiveNodeCallbacks =
        GeneratedCaseAssertion { observation ->
            val loaders = observation.ordinaryApplications.count { application ->
                observation.testCase.registry.nodeLoaderPossibleTypes(observation.testCase.schema, application.key.field).isNotEmpty()
            }
            check(observation.selectiveNodeResolverApplications.size <= loaders) { "A Query.node dispatch may invoke at most one selective node callback" }
        }

    val constructionWitness =
        GeneratedCaseAssertion { observation ->
            check(observation.ordinaryApplications.all { it.suppliedDemandFingerprint != null }) { "Every selective resolver application must capture its supplied demand" }
            val allowed = observation.ordinary.fragment.subselections.allowedResolverClosure(observation.ordinary.world.resolverRegistry)
            val unrelated = observation.ordinaryApplications.filter { it.key.field !in allowed.canonicalFields }
            check(unrelated.isEmpty()) { "Resolver applied outside operation/registry demand closure: ${unrelated.map { it.key.field }}" }
            val permuted = requireNotNull(observation.permutationApplications) { "Witness comparison requires recording both executions" }
            checkAgreement(
                observation.ordinaryApplications.groupingBy(ResolverApplicationRecord::observation).eachCount(),
                permuted.groupingBy(ResolverApplicationRecord::observation).eachCount(),
            )
            checkAgreement(
                observation.ordinaryApplications.groupingBy(ResolverApplicationRecord::identity).eachCount(),
                permuted.groupingBy(ResolverApplicationRecord::identity).eachCount(),
            )
        }

    val fromFieldBindings =
        GeneratedCaseAssertion { observation ->
            observation.executions.forEach { execution ->
                execution.result.validateFromFieldBindings(
                    execution.operation,
                    requireNotNull(execution.subject.appliedResolverOccurrences) {
                        "From-field binding validation requires exact application " +
                            "occurrences"
                    },
                )
            }
        }

    val exactCheckerApplications =
        GeneratedCaseAssertion { observation ->
            observation.executions.forEach { execution ->
                val inputRecorder = execution.operation.resolutionObserver as CorrectnessResolverObserver
                execution.checkerApplications.groupingBy { it }.eachCount().forEach { (application, count) ->
                    checkAgreement(
                        count,
                        inputRecorder.checkerInputs(
                            application.checkedTarget,
                            ResolverOccurrenceId.at(application.logicalQueryRoot, application.occurrencePath),
                        ).size,
                        "Every checker invocation must retain its actual named inputs",
                    )
                }
                val demanded = execution.result.demandedTypeCheckerApplications(
                    execution.operation,
                    execution.fragment.subselections
                ).associateWith { 1 }
                val invoked = execution.checkerApplications.filter { it.checkerKind == CheckerKind.TYPE }.groupingBy { it }.eachCount()
                checkAgreement(
                    demanded,
                    invoked,
                    "Type-check applications must follow checked demand: missing=${demanded.keys - invoked.keys}; extra=${invoked.keys - demanded.keys}"
                )
                val expected =
                    execution.result.registeredCheckerApplications(execution.operation)
                checkAgreement(
                    expected.groupingBy { application -> application }.eachCount(),
                    execution.checkerApplications
                        .groupingBy { application -> application }
                        .eachCount(),
                )
            }
        }

    val defaultGeneratedContract =
        listOf(
            correctResolution,
            permutationEquivalentResult,
        )
}

private fun checkAgreement(
    expected: Any?,
    actual: Any?,
    message: String = "Oracle observations disagree",
) {
    check(expected == actual) { "$message: expected=$expected; actual=$actual" }
}

fun GeneratedCaseObservation.assertAll(assertions: Iterable<GeneratedCaseAssertion>): GeneratedCaseObservation =
    apply {
        assertions.forEach { assertion -> assertion.assertThat(this) }
    }
