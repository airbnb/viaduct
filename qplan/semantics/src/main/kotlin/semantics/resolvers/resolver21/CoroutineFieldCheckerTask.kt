package semantics.resolvers.resolver21

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import model.Arguments
import model.EngineResultCell
import model.ObjectEngineResult
import model.ObjectSelection
import model.PathComponent
import model.registry.FieldChecker
import model.registry.CheckerInput
import model.registry.ResolutionExecutionContext
import model.registry.ResolverFragments
import semantics.resolver26.CoroutineFieldCheckerPublicationOccurrence
import semantics.shared.fieldCheckerCycleSlot
import semantics.shared.fieldCheckerCycleTask
import semantics.shared.OEROccurrence
import semantics.shared.SharedOERContext
import semantics.shared.materializeResult

/** Immutable inputs to one grounded field-checker-result publication in Resolver21-23. */
internal class GroundedFieldCheckerPublicationOccurrence(
    val operation: CoroutineOperationContext,
    val oerOccurrence: OEROccurrence,
    val selection: ObjectSelection,
    override val publicationCell: EngineResultCell,
    val checker: FieldChecker?,
    val checkerFragments: ResolverFragments?,
    val arguments: Arguments.Resolved?,
    val publicationPath: List<PathComponent>,
    val queryOER: SharedOERContext,
) : CoroutineFieldCheckerPublicationOccurrence

/**
 * Invokes one argument-only checker and publishes independently from the field value. Its
 * lifecycle method names deliberately mirror [semantics.resolver26.CoroutineFieldResolverTask]
 * until checker RSS provides enough real common structure to evaluate a shared abstraction.
 */
internal class CoroutineFieldCheckerTask private constructor(
    private val publication: GroundedFieldCheckerPublicationOccurrence,
) {
    companion object {
        /** Claims every selected checker slot without dispatching a producer. */
        fun prepareAll(
            orchestrationTask: CoroutineOrchestrationTask,
        ): List<GroundedFieldCheckerPublicationOccurrence> =
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
            cell.createFieldCheckerResultPromise()
            val checker =
                if (key is ObjectEngineResult.ParentKey || key.arguments !is Arguments.Resolved) {
                    null
                } else {
                    orchestrationTask.operation.world.resolverRegistry
                        .fieldChecker(key.field)
                        ?.also { checker ->
                            requireSupportedRequiredSelections(orchestrationTask.operation, checker)
                        }
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

        private fun requireSupportedRequiredSelections(
            operation: CoroutineOperationContext,
            checker: FieldChecker,
        ) {
            require(operation.supportsCheckerFragments || checker.objectFragment.isEmpty()) {
                "Resolver21 field checker ${checker.field.containingDef.name}/${checker.field.name} " +
                    "cannot declare object required selections"
            }
            require(operation.supportsCheckerFragments || checker.queryFragment.isEmpty()) {
                "Resolver21 field checker ${checker.field.containingDef.name}/${checker.field.name} " +
                    "cannot declare Query required selections"
            }
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
        val inputs =
            checker.fragmentTemplates.keys.associateWith { name ->
                CheckerInput(
                    objectValue = objectInputs.getValue(name),
                    queryValue = queryValues.getValue(name),
                )
            }
        val result =
            checker(
                checkNotNull(publication.arguments),
                inputs,
                ResolutionExecutionContext.Unsupported,
            )
        check(publication.publicationCell.getFieldCheckerResult().complete(result)) {
            "Field-checker result was completed twice"
        }
    }

    private fun publishFailure(cause: Exception) {
        check(publication.publicationCell.failFieldCheckerResult(cause)) {
            "Field-checker failure was published twice"
        }
    }

}
