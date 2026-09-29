package semantics.resolvers.resolver21

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import model.ObjectEngineResult
import model.registry.CheckerInput
import model.registry.ResolutionExecutionContext
import model.registry.ResolverFragments
import model.registry.ResolverTarget
import model.registry.TypeCheckerResolver
import semantics.resolver26.CoroutinePublicationOccurrence
import semantics.shared.CheckerInvocationObservation
import semantics.shared.CheckerKind
import semantics.shared.OEROccurrence
import semantics.shared.SharedOERContext
import semantics.shared.materializeResult
import semantics.shared.typeCheckerCycleSlot
import semantics.shared.typeCheckerCycleTask

/** Immutable inputs to one grounded OER-owned type-checker publication in Resolver21-23. */
internal class GroundedTypeCheckerPublicationOccurrence(
    val operation: CoroutineOperationContext,
    val oerOccurrence: OEROccurrence,
    val publicationResult: ObjectEngineResult,
    val checker: TypeCheckerResolver,
    val checkerFragments: ResolverFragments,
    val queryOER: SharedOERContext,
) : CoroutinePublicationOccurrence {
    override fun dispatch(requestScope: CoroutineScope) {
        requestScope.launch {
            CoroutineTypeCheckerTask.execute(this@GroundedTypeCheckerPublicationOccurrence, this)
        }.invokeOnCompletion { cause ->
            if (cause is CancellationException) CoroutineTypeCheckerTask.cancel(this@GroundedTypeCheckerPublicationOccurrence, cause)
        }
    }
}

/** Materializes one type checker's raw inputs, invokes it, and publishes to its OER-owned slot. */
internal class CoroutineTypeCheckerTask private constructor(
    private val publication: GroundedTypeCheckerPublicationOccurrence,
) {
    companion object {
        /** Claims the checked concrete OER's type-checker slot before any local producer dispatch. */
        fun prepareAll(orchestrationTask: CoroutineOrchestrationTask): List<GroundedTypeCheckerPublicationOccurrence> =
            listOf(
                orchestrationTask.objectOER to orchestrationTask.closedConstructionDemand.objectRooted,
                orchestrationTask.queryOER to orchestrationTask.closedConstructionDemand.queryRooted,
            ).mapNotNull { (oer, constructionDemand) ->
                if (!constructionDemand.typeCheckDemanded) return@mapNotNull null
                val checker =
                    orchestrationTask.operation.world.resolverRegistry.typeChecker(oer.occurrence.target.type)
                        ?: return@mapNotNull null
                require(checker.variables.isEmpty()) {
                    "Grounded type checker ${checker.target.type.name} cannot declare variables"
                }
                check(!oer.occurrence.target.typeCheckerResult.isCompleted) {
                    "Applicable type-checker result was completed before orchestration preparation"
                }
                val publication =
                    GroundedTypeCheckerPublicationOccurrence(
                        operation = orchestrationTask.operation,
                        oerOccurrence = oer.occurrence,
                        publicationResult = oer.occurrence.target,
                        checker = checker,
                        checkerFragments =
                            checker.instantiateFragmentsAt(
                                oer.occurrence.root,
                                oer.occurrence.path,
                            ),
                        queryOER = orchestrationTask.queryOER,
                    )
                orchestrationTask.operation.cycleChecker.registerWriter(
                    slot = publication.publicationResult.typeCheckerCycleSlot,
                    writer = publication.oerOccurrence.typeCheckerCycleTask(),
                )
                publication
            }

        internal suspend fun execute(
            publication: GroundedTypeCheckerPublicationOccurrence,
            @Suppress("UNUSED_PARAMETER") scope: CoroutineScope,
        ) {
            CoroutineTypeCheckerTask(publication).run()
        }

        internal fun cancel(
            publication: GroundedTypeCheckerPublicationOccurrence,
            cause: CancellationException,
        ) {
            publication.publicationResult.typeCheckerResult.cancel(cause)
        }
    }

    private suspend fun run() {
        try {
            executeAndPublish()
        } catch (cause: Exception) {
            currentCoroutineContext().ensureActive()
            check(publication.publicationResult.typeCheckerResult.fail(cause)) {
                "Type-checker failure was published twice"
            }
        }
    }

    private suspend fun executeAndPublish() {
        val reader = publication.oerOccurrence.typeCheckerCycleTask()
        val fragmentId = publication.checkerFragments.objectFragment.resolverOccurrenceId
        val objectSelections = publication.checker.instantiateObjectMaterializationSelections(fragmentId)
        val querySelections = publication.checker.instantiateQueryMaterializationSelections(fragmentId)
        val inputs =
            publication.checker.fragmentTemplates.keys.associateWith { name ->
                CheckerInput(
                    objectValue =
                        publication.oerOccurrence.target.materializeResult(
                            operation = publication.operation,
                            selections = objectSelections.getValue(name),
                            reader = reader,
                            cycleChecker = publication.operation.cycleChecker,
                            checked = false,
                        ),
                    queryValue =
                        publication.queryOER.occurrence.target.materializeResult(
                            operation = publication.operation,
                            selections = querySelections.getValue(name),
                            reader = reader,
                            cycleChecker = publication.operation.cycleChecker,
                            checked = false,
                        ),
                )
            }
        if (!publication.checkerFragments.queryFragment.constructionSelections.isEmpty()) {
            publication.operation.checkerObserver.onCheckerQueryFragmentPrepared(
                publication.checker.target,
                fragmentId,
                publication.queryOER.occurrence.target,
            )
        }
        publication.operation.checkerObserver.onCheckerInvocation(
            CheckerInvocationObservation(
                checkerKind = CheckerKind.TYPE,
                logicalQueryRoot = publication.oerOccurrence.root,
                occurrencePath = publication.oerOccurrence.path,
                arguments = null,
                checkedTarget = ResolverTarget.TypeCheckerTarget(publication.publicationResult.type),
            ),
        )
        val result = publication.checker(inputs, ResolutionExecutionContext.Unsupported)
        check(publication.publicationResult.typeCheckerResult.complete(result)) {
            "Type-checker result was completed twice"
        }
    }
}
