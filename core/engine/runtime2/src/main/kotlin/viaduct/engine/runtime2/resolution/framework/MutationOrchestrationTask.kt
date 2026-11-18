package viaduct.engine.runtime2.resolution.framework

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import viaduct.engine.api.CheckerResult
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.MutationObjectEngineResult
import viaduct.engine.runtime2.model.MutationSelectionForest
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ObjectSelection
import viaduct.engine.runtime2.model.Promise
import viaduct.engine.runtime2.model.registry.CheckerInput
import viaduct.engine.runtime2.model.registry.CheckerResolverBase
import viaduct.engine.runtime2.model.registry.ResolutionExecutionContext
import viaduct.engine.runtime2.model.registry.emptyResolverInput
import viaduct.engine.runtime2.model.requireQueryTypeDef

/** One ordered namespace traversal shared by every maintained resolver family. */
internal class MutationOrchestrationTask<P> private constructor(
    val operation: SharedOperationContext<SharedTaskDispatcher<*, *, P>>,
    val occurrence: OEROccurrence,
    selections: MutationSelectionForest,
    private val prepareField: (ResolutionTask, OEROccurrence, ObjectSelection) -> P,
    private val namespacePrepared: (OEROccurrence) -> Unit,
    private val checkerExecutionContext: ((ResolutionTask, OEROccurrence, ObjectEngineResult.MutationKey?) -> ResolutionExecutionContext)?,
    override val creator: ResolutionTask?,
) : ResolutionTask {
    private val target = occurrence.target as MutationObjectEngineResult
    private val members = selections.orderedSelections().map { selection ->
        selection to ObjectEngineResult.MutationKey.of(selection.key, selection.responseKey)
    }
    private val dispatched = AtomicBoolean(false)
    private val publications = mutableMapOf<ObjectEngineResult.MutationKey, P>()

    init {
        require(target.type == selections.type) { "Mutation forest does not match its namespace occurrence" }
        if (checkerExecutionContext == null) {
            require(operation.world.resolverRegistry.typeChecker(target.type) == null) { "Mutation namespace type checkers are not supported" }
        }
    }

    companion object {
        fun <P> createRoot(
            operation: SharedOperationContext<SharedTaskDispatcher<*, *, P>>,
            selections: MutationSelectionForest,
            prepareField: (ResolutionTask, OEROccurrence, ObjectSelection) -> P,
            namespacePrepared: (OEROccurrence) -> Unit = {},
            checkerExecutionContext: ((ResolutionTask, OEROccurrence, ObjectEngineResult.MutationKey?) -> ResolutionExecutionContext)? = null,
            creator: ResolutionTask? = null,
        ): MutationOrchestrationTask<P> {
            require(selections.type == operation.world.schema.mutationTypeDef) { "Mutation execution must start at the Mutation root" }
            val result = MutationObjectEngineResult.of(selections)
            return create(operation, OEROccurrence(result, emptyList(), result), selections, prepareField, namespacePrepared, checkerExecutionContext, creator)
        }

        fun <P> create(
            operation: SharedOperationContext<SharedTaskDispatcher<*, *, P>>,
            occurrence: OEROccurrence,
            selections: MutationSelectionForest,
            prepareField: (ResolutionTask, OEROccurrence, ObjectSelection) -> P,
            namespacePrepared: (OEROccurrence) -> Unit = {},
            checkerExecutionContext: ((ResolutionTask, OEROccurrence, ObjectEngineResult.MutationKey?) -> ResolutionExecutionContext)? = null,
            creator: ResolutionTask? = null,
        ): MutationOrchestrationTask<P> =
            MutationOrchestrationTask(operation, occurrence, selections, prepareField, namespacePrepared, checkerExecutionContext, creator).apply {
                prepare()
                operation.resolutionObserver.onTaskPrepared?.invoke(this)
            }
    }

    /** All local publications are claimed before any mutation is dispatched. */
    private fun prepare() {
        members.forEach { (_, key) -> target.reserveCell(key) }
        for ((selection, key) in members) {
            if (checkerExecutionContext == null) {
                require(operation.world.resolverRegistry.fieldChecker(key.field) == null) { "Mutation namespace field checkers are not supported" }
            }
            if (selection.subselections is MutationSelectionForest) {
                target.getCell(key).value.claim()
            } else {
                val dependencies = operation.world.resolverRegistry.resolver(key.field).dependenciesFor(operation.engineExecutionContext)
                require(dependencies.objectFragment.isEmpty() && dependencies.queryFragment.isEmpty()) {
                    "Mutation resolvers cannot declare object or Query fragments; use ctx.query()"
                }
                publications[key] = prepareField(this, occurrence, ObjectSelection.of(key, selection.possibleTypes, selection.subselections))
            }
        }
        target.freeze()
        namespacePrepared(occurrence)
    }

    fun checkDispatch() {
        check(dispatched.compareAndSet(false, true)) { "Mutation orchestration dispatched twice" }
    }

    suspend fun run() {
        operation.resolutionObserver.onTaskExecution?.invoke(this, ResolutionTaskState.STARTED, null)
        try {
            currentCoroutineContext().ensureActive()
            if (!checkType()) {
                suppressMembers()
            } else {
                for ((selection, key) in members) {
                    currentCoroutineContext().ensureActive()
                    val cell = target.getCell(key)
                    check(cell.setActivated(true)) { "Mutation cell activation was decided twice" }
                    if (checkerExecutionContext == null) {
                        check(cell.fieldCheckerResult.complete(null)) { "Mutation field-checker result was completed twice" }
                    } else if (!checkField(key)) {
                        check(cell.value.complete(null))
                        continue
                    }
                    val subselections = selection.subselections
                    if (subselections is MutationSelectionForest) {
                        val typeResult: Promise<CheckerResult?> =
                            if (checkerExecutionContext != null) Promise.ofDeferred() else Promise.of(null)
                        val result = MutationObjectEngineResult.of(subselections, typeResult)
                        val nextOccurrence = OEROccurrence(occurrence.root, occurrence.coordinate(key), result, occurrence)
                        val next = create(operation, nextOccurrence, subselections, prepareField, namespacePrepared, checkerExecutionContext, creator = this)
                        next.run()
                        cell.value.complete(result)
                    } else {
                        operation.dispatcher.dispatchMutationField(publications.getValue(key), cell)
                    }
                }
            }
        } catch (e: CancellationException) {
            cancel(e)
            throw e
        } catch (e: Exception) {
            operation.resolutionObserver.onTaskExecution?.invoke(this, ResolutionTaskState.FINISHED, e)
            throw e
        }
        operation.resolutionObserver.onTaskExecution?.invoke(this, ResolutionTaskState.FINISHED, null)
    }

    private suspend fun checkType(): Boolean {
        if (checkerExecutionContext == null) return true
        val checker = operation.world.resolverRegistry.typeChecker(target.type)
        if (checker == null) {
            target.typeCheckerResult.complete(null)
            return true
        }
        return publishCheckerResult(target.typeCheckerResult) {
            if (!checker.isApplicable(occurrence.root, occurrence.path, operation.engineExecutionContext)) {
                null
            } else {
                val inputs = checkerInputs(checker)
                operation.resolutionObserver.onCheckerInvocation?.invoke(
                    CheckerInvocationObservation(CheckerKind.TYPE, occurrence.root, occurrence.path, null, checker.target),
                    inputs,
                )
                checker(inputs, checkerExecutionContext.invoke(this, occurrence, null))
            }
        }
    }

    private suspend fun checkField(key: ObjectEngineResult.MutationKey): Boolean =
        publishCheckerResult(target.getCell(key).fieldCheckerResult) {
            val checker = operation.world.resolverRegistry.fieldChecker(key.field)
            val arguments = key.arguments
            if (checker == null || arguments !is Arguments.Resolved) {
                null
            } else {
                val inputs = checkerInputs(checker)
                operation.resolutionObserver.onCheckerInvocation?.invoke(
                    CheckerInvocationObservation(CheckerKind.FIELD, occurrence.root, occurrence.coordinate(key), arguments, checker.target),
                    inputs,
                )
                checker(arguments, inputs, requireNotNull(checkerExecutionContext).invoke(this, occurrence, key))
            }
        }

    private fun checkerInputs(checker: CheckerResolverBase<*>): Map<String, CheckerInput> {
        val registry = operation.world.resolverRegistry
        return checker.fragmentTemplates.keys.associateWith {
            CheckerInput(registry.emptyResolverInput(target.type), registry.emptyResolverInput(operation.world.schema.requireQueryTypeDef()))
        }
    }

    private fun suppressMembers() {
        members.forEach { (_, key) ->
            val cell = target.getCell(key)
            check(cell.setActivated(true)) { "Mutation cell activation was decided twice" }
            check(cell.fieldCheckerResult.complete(null))
            check(cell.value.complete(null))
        }
    }

    fun cancel(cause: CancellationException) {
        target.typeCheckerResult.cancel(cause)
        target.keys.forEach { key ->
            val cell = target.getCell(key)
            cell.value.cancel(cause)
            cell.fieldCheckerResult.cancel(cause)
        }
        operation.resolutionObserver.onTaskExecution?.invoke(this, ResolutionTaskState.FINISHED, cause)
    }
}
