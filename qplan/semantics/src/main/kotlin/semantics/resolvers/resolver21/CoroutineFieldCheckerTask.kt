package semantics.resolvers.resolver21

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import model.Arguments
import model.EngineResultCell
import model.ObjectEngineResult
import model.ObjectSelection
import model.PathComponent
import model.registry.FieldChecker
import model.registry.ResolutionExecutionContext
import semantics.resolver26.CoroutineFieldCheckerPublicationOccurrence
import semantics.shared.fieldCheckerCycleSlot
import semantics.shared.fieldCheckerCycleTask
import semantics.shared.OEROccurrence
import semantics.shared.SharedOERContext

/** Immutable inputs to one grounded field-checker-result publication in Resolver21-23. */
internal class GroundedFieldCheckerPublicationOccurrence(
    val operation: CoroutineOperationContext,
    val oerOccurrence: OEROccurrence,
    val selection: ObjectSelection,
    override val publicationCell: EngineResultCell,
    val checker: FieldChecker?,
    val arguments: Arguments.Resolved?,
    val publicationPath: List<PathComponent>,
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
            listOf(orchestrationTask.objectOER, orchestrationTask.queryOER).flatMap { oer ->
                oer.closedDemand.byGroundKey().map { (key, selection) ->
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
                        ?.also(::requireNoRequiredSelections)
                }
            val publication = GroundedFieldCheckerPublicationOccurrence(
                operation = orchestrationTask.operation,
                oerOccurrence = oer.occurrence,
                selection = selection,
                publicationCell = cell,
                checker = checker,
                arguments = key.arguments as? Arguments.Resolved,
                publicationPath = oer.occurrence.coordinate(key),
            )
            if (checker != null) {
                publication.operation.cycleChecker.registerWriter(
                    slot = cell.fieldCheckerCycleSlot,
                    writer = publication.oerOccurrence.fieldCheckerCycleTask(key),
                )
            }
            return publication
        }

        internal suspend fun execute(publication: GroundedFieldCheckerPublicationOccurrence) {
            CoroutineFieldCheckerTask(publication).run()
        }

        private fun requireNoRequiredSelections(checker: FieldChecker) {
            require(checker.objectFragment.isEmpty()) {
                "Resolver21 field checker ${checker.field.containingDef.name}/${checker.field.name} " +
                    "cannot declare object required selections"
            }
            require(checker.queryFragment.isEmpty()) {
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
        val result =
            checkNotNull(publication.checker)(
                checkNotNull(publication.arguments),
                emptyMap(),
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
