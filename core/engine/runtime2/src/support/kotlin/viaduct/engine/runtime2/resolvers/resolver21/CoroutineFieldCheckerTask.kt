package viaduct.engine.runtime2.resolvers.resolver21

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.EngineResultCell
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ObjectSelection
import viaduct.engine.runtime2.model.PathComponent
import viaduct.engine.runtime2.model.ResolverTarget
import viaduct.engine.runtime2.model.registry.CheckerInput
import viaduct.engine.runtime2.model.registry.FieldCheckerResolver
import viaduct.engine.runtime2.model.registry.ResolutionExecutionContext
import viaduct.engine.runtime2.model.registry.ResolverFragments
import viaduct.engine.runtime2.resolution.CoroutinePublicationOccurrence
import viaduct.engine.runtime2.resolution.framework.CheckerInvocationObservation
import viaduct.engine.runtime2.resolution.framework.CheckerKind
import viaduct.engine.runtime2.resolution.framework.OEROccurrence
import viaduct.engine.runtime2.resolution.framework.SharedOERContext
import viaduct.engine.runtime2.resolution.framework.fieldCheckerCycleSlot
import viaduct.engine.runtime2.resolution.framework.fieldCheckerCycleTask
import viaduct.engine.runtime2.resolution.framework.materializeResult

/** Immutable inputs to one grounded field-checker-result publication in Resolver21-23. */
internal class GroundedFieldCheckerPublicationOccurrence(
    val operation: CoroutineOperationContext,
    val oerOccurrence: OEROccurrence,
    val selection: ObjectSelection,
    val publicationCell: EngineResultCell,
    val checker: FieldCheckerResolver?,
    val checkerFragments: ResolverFragments?,
    val arguments: Arguments.Resolved?,
    val publicationPath: List<PathComponent>,
    val queryOER: SharedOERContext,
) : CoroutinePublicationOccurrence {
    override fun dispatch(requestScope: CoroutineScope) {
        requestScope.launch {
            CoroutineFieldCheckerTask.execute(this@GroundedFieldCheckerPublicationOccurrence, this)
        }.invokeOnCompletion { cause ->
            if (cause is CancellationException) CoroutineFieldCheckerTask.cancel(this@GroundedFieldCheckerPublicationOccurrence, cause)
        }
    }
}

/**
 * Materializes one checker's paired inputs, invokes it, and publishes independently from the
 * field value. Its lifecycle deliberately parallels the field-resolver task without introducing
 * a shared task abstraction.
 */
internal class CoroutineFieldCheckerTask private constructor(
    private val publication: GroundedFieldCheckerPublicationOccurrence,
) {
    companion object {
        /** Claims every selected checker slot without dispatching a producer. */
        fun prepareAll(orchestrationTask: CoroutineOrchestrationTask): List<GroundedFieldCheckerPublicationOccurrence> =
            listOf(
                orchestrationTask.objectOER to orchestrationTask.closedConstructionDemand.objectRooted.checked,
                orchestrationTask.queryOER to orchestrationTask.closedConstructionDemand.queryRooted.checked,
            ).flatMap { (oer, checkedDemand) ->
                checkedDemand.byGroundKey().map { (key, selection) ->
                    prepare(orchestrationTask, oer, key, selection)
                }
            }

        private fun prepare(
            orchestrationTask: CoroutineOrchestrationTask,
            oer: SharedOERContext,
            key: ObjectEngineResult.GroundKey,
            selection: ObjectSelection,
        ): GroundedFieldCheckerPublicationOccurrence {
            val cell = oer.occurrence.target.getCell(key)
            cell.fieldCheckerResult
            val checker =
                if (key is ObjectEngineResult.ParentKey || key.arguments !is Arguments.Resolved) {
                    null
                } else {
                    orchestrationTask.operation.world.resolverRegistry
                        .fieldChecker(key.field)
                }
            val publicationPath = oer.occurrence.coordinate(key)
            val publication = GroundedFieldCheckerPublicationOccurrence(
                operation = orchestrationTask.operation,
                oerOccurrence = oer.occurrence,
                selection = selection,
                publicationCell = cell,
                checker = checker,
                checkerFragments =
                    checker?.instantiateFragmentsAt(
                        oer.occurrence.root,
                        publicationPath,
                    ),
                arguments = key.arguments as? Arguments.Resolved,
                publicationPath = publicationPath,
                queryOER = orchestrationTask.queryOER,
            )
            if (checker != null) {
                publication.operation.cycleChecker.registerWriter(
                    slot = cell.fieldCheckerCycleSlot,
                    writer = publication.oerOccurrence.fieldCheckerCycleTask(key),
                )
            }
            return publication
        }

        internal suspend fun execute(
            publication: GroundedFieldCheckerPublicationOccurrence,
            @Suppress("UNUSED_PARAMETER") scope: CoroutineScope,
        ) {
            CoroutineFieldCheckerTask(publication).run()
        }

        internal fun cancel(
            publication: GroundedFieldCheckerPublicationOccurrence,
            cause: CancellationException,
        ) {
            publication.publicationCell.fieldCheckerResult.cancel(cause)
        }
    }

    private suspend fun run() {
        try {
            executeAndPublish()
        } catch (cause: Exception) {
            currentCoroutineContext().ensureActive()
            publishFailure(cause)
        }
    }

    private suspend fun executeAndPublish() {
        if (!publication.publicationCell.fetchActivated()) return
        val checker = checkNotNull(publication.checker)
        val fragments = checkNotNull(publication.checkerFragments)
        val reader =
            publication.oerOccurrence.root.fieldCheckerCycleTask(publication.publicationPath)
        check(
            fragments.queryFragment.constructionSelections.isEmpty() ||
                publication.queryOER.isDemanded(),
        ) {
            "Nonempty checker Query fragment has no demanded shared Query OER"
        }
        val objectInputs =
            checker
                .instantiateObjectMaterializationSelections(
                    fragments.objectFragment.resolverOccurrenceId,
                ).mapValues { (_, selections) ->
                    publication.oerOccurrence.target.materializeResult(
                        operation = publication.operation,
                        selections = selections,
                        reader = reader,
                        cycleChecker = publication.operation.cycleChecker,
                        checked = false,
                    )
                }
        val queryValues =
            checker
                .instantiateQueryMaterializationSelections(
                    fragments.queryFragment.resolverOccurrenceId,
                ).mapValues { (_, selections) ->
                    publication.queryOER.occurrence.target.materializeResult(
                        operation = publication.operation,
                        selections = selections,
                        reader = reader,
                        cycleChecker = publication.operation.cycleChecker,
                        checked = false,
                    )
                }
        if (!fragments.queryFragment.constructionSelections.isEmpty()) {
            publication.operation.checkerObserver.onCheckerQueryFragmentPrepared(
                checker.target,
                fragments.queryFragment.resolverOccurrenceId,
                publication.queryOER.occurrence.target,
            )
        }
        val inputs =
            checker.fragmentTemplates.keys.associateWith { name ->
                CheckerInput(
                    objectValue = objectInputs.getValue(name),
                    queryValue = queryValues.getValue(name),
                )
            }
        val arguments = checkNotNull(publication.arguments)
        publication.operation.checkerObserver.onCheckerInvocation(
            CheckerInvocationObservation(
                checkerKind = CheckerKind.FIELD,
                logicalQueryRoot = publication.oerOccurrence.root,
                occurrencePath = publication.publicationPath,
                arguments = arguments,
                checkedTarget = ResolverTarget.FieldCheckerTarget(publication.selection.key.field),
            ),
            inputs,
        )
        val result =
            checker(
                arguments,
                inputs,
                ResolutionExecutionContext.Unsupported,
            )
        check(publication.publicationCell.fieldCheckerResult.complete(result)) {
            "Field-checker result was completed twice"
        }
    }

    private fun publishFailure(cause: Exception) {
        check(publication.publicationCell.fieldCheckerResult.fail(cause)) {
            "Field-checker failure was published twice"
        }
    }
}
