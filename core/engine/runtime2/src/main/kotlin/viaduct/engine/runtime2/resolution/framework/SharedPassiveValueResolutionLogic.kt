package viaduct.engine.runtime2.resolution.framework

import viaduct.apiannotations.InternalApi
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.EngineErrorData
import viaduct.engine.runtime2.model.EngineResult
import viaduct.engine.runtime2.model.EngineResultCell
import viaduct.engine.runtime2.model.ErrorEngineResult
import viaduct.engine.runtime2.model.ListEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ObjectSelection
import viaduct.engine.runtime2.model.ObjectSelectionForest
import viaduct.engine.runtime2.model.PathComponent
import viaduct.engine.runtime2.model.ResolverOutputData
import viaduct.engine.runtime2.model.RootFieldReferenceData
import viaduct.engine.runtime2.model.Selection
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.additionalContextForResolver
import viaduct.engine.runtime2.model.isParentField
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.outputType
import viaduct.engine.runtime2.model.outputValue
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.rootFieldReferenceDataOrNull
import viaduct.engine.runtime2.model.schemaType
import viaduct.engine.runtime2.model.selectionForestOf
import viaduct.engine.runtime2.model.toEngineResult
import viaduct.graphql.schema.ViaductSchema

/**
 * Passively resolves resolver-returned values and initiates active resolution for each object
 * occurrence. This traversal owns result shape, occurrence identity, source coverage, and
 * propagation of construction versus invocation demand. [resolveObjectOccurrence] is the per-object
 * loop: close demand, prepare the orchestration, supply passive fields, then dispatch active work.
 * [T] connects the prepared task to the dispatcher's task type; [O] retains the concrete operation type;
 * [C] is the family's closed demand for an object and its fresh Query OER.
 */
@InternalApi
abstract class SharedPassiveValueResolutionLogic<
    T : SharedOrchestrationTask<*>,
    O : SharedOperationContext<SharedTaskDispatcher<T, *, *>>,
    C,
>(
    protected val operation: O,
) {
    /**
     * Closes [constructionDemand] for [occurrence] and [queryOccurrence], adding the inputs of every
     * resolver and checker that will run. Resolution closes symbolic demand; other families can close
     * grounded demand. Closing installs no cells and dispatches no work.
     */
    protected abstract fun closeConstructionDemand(
        occurrence: OEROccurrence,
        queryOccurrence: OEROccurrence,
        source: EngineObjectData.Sync,
        constructionDemand: Demand<SelectionForest>,
    ): C

    /**
     * Returns a task for [occurrence] that is ready for passive descent. Preparation may declare
     * bindings, install `@parent` cells and reserve checker cells, but it does not dispatch active work.
     */
    protected abstract fun prepareOrchestration(
        occurrence: OEROccurrence,
        queryOccurrence: OEROccurrence,
        source: EngineObjectData.Sync,
        closedDemand: C,
        creator: ResolutionTask?,
    ): T

    /**
     * Creates the concrete OER before its orchestration closes and prepares local work.
     *
     * The default is an empty mutable OER whose type-check result is immediate null, which is
     * sufficient for resolver families that do not schedule access checks. Checker-capable
     * families override this hook to inspect [constructionDemand] and install a deferred
     * type-check result when the occurrence demands one and its concrete type has a registered
     * checker.
     *
     * The promise choice belongs at creation because the new OER may become observable as soon as
     * passive resolution publishes it. A completed null result cannot subsequently be replaced by
     * a deferred checker result.
     */
    protected open fun createObjectResult(
        type: ViaductSchema.Object,
        constructionDemand: Demand<SelectionForest>,
    ): ObjectEngineResult = ObjectEngineResult.of(type = type, mutable = true)

    /** Returns the checked/unchecked demand closed by [orchestration]. */
    protected open fun closedConstructionDemand(orchestration: T): Demand<ObjectSelectionForest> =
        Demand.checked(orchestration.objectOER.closedValueSelections)
            .merge(orchestration.objectOER.source.schemaType(operation.world.schema))

    /**
     * Collects selections for [type]. Resolution merges while retaining unresolved arguments;
     * grounded implementations also instantiate available argument bindings and coalesce keys that become equal.
     */
    protected abstract fun collect(
        selections: SelectionForest,
        type: ViaductSchema.Object,
    ): ObjectSelectionForest

    /**
     * Dispatches executable reference work discovered during passive list resolution. Resolution uses
     * its field-task protocol, including runtime binding support; Resolver21-23 use grounded field tasks.
     * Resolver06-08 queue tasks at their publication depth.
     * [selection] describes the containing field with this reference's construction demand. Each implementation
     * claims and publishes [cell] at this occurrence.
     */
    protected abstract fun resolveListReference(
        reference: RootFieldReferenceData,
        cell: EngineResultCell,
        path: List<PathComponent>,
        expectedType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
        selection: ObjectSelection,
        constructionDemand: Demand<SelectionForest>,
        invocationDemand: SelectionForest,
        parent: OEROccurrence,
        creator: ResolutionTask?,
    )

    /**
     * Gates passive resolution of a reference-bearing list. Resolution defers conditional lists so their
     * references execute only if inclusion succeeds, and omits Never lists. Resolvers without
     * conditional activation use the default immediate passive resolution.
     *
     * @return true when the hook has registered deferred work or omitted the list; false to resolve it passively now.
     */
    protected open fun deferReferenceList(
        orchestration: T,
        selection: ObjectSelection,
        value: ResolverOutputData?,
        invocationDemand: SelectionForest,
        constructionDemand: Demand<SelectionForest>,
    ): Boolean = false

    protected open fun passiveFieldKeys(
        source: EngineObjectData.Sync,
        field: ViaductSchema.ObjectField,
        demandedKeys: List<ObjectEngineResult.ObjectKey>?,
    ): List<ObjectEngineResult.ObjectKey> = demandedKeys.orEmpty()

    /**
     * Passively resolves [value] at [path], preserving nulls, errors, and list positions. Each object
     * gets an OER and an orchestration lifecycle; lists retain [parent] as their containing object.
     * Direct root-field references must be followed by the caller; list references use
     * [resolveListReference]. The returned result may still have pending active fields.
     *
     * [constructionDemand] is the demand to close for returned objects. [invocationDemand] is the
     * demand supplied to the producer and contributes to passive consumption.
     */
    fun resolvePassiveValues(
        value: ResolverOutputData?,
        root: ObjectEngineResult,
        expectedType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
        path: List<PathComponent>,
        constructionDemand: Demand<SelectionForest>,
        invocationDemand: SelectionForest,
        parent: OEROccurrence? = null,
        creator: ResolutionTask? = null,
    ): EngineResult? {
        if (value == null) return null
        if (value is EngineErrorData) return ErrorEngineResult.of(value)
        check(value.rootFieldReferenceDataOrNull() == null) {
            "A direct root-field reference must be resolved before passive resolution"
        }

        val elementType = expectedType.unwrapList()
        if (elementType != null) {
            val values = value as List<*>
            val containingOccurrence = requireNotNull(parent)
            val result = ListEngineResult.ofPendingValues(elementType, values.size)
            values.forEachIndexed { index, element ->
                val elementPath = path + ListEngineResult.Index.of(index)
                val cell = result[index]
                val reference = element.rootFieldReferenceDataOrNull()
                if (reference != null) {
                    val key = elementPath.filterIsInstance<ObjectEngineResult.ObjectKey>().last()
                    val selection = selectionForestOf(
                        Selection.of(
                            key,
                            setOf(containingOccurrence.target.type),
                            constructionDemand.values,
                        ),
                    ).merge(containingOccurrence.target.type).byKey().getValue(key)
                    resolveListReference(
                        reference = reference,
                        cell = cell,
                        path = elementPath,
                        expectedType = elementType,
                        selection = selection,
                        constructionDemand = constructionDemand,
                        invocationDemand = invocationDemand,
                        parent = containingOccurrence,
                        creator = creator,
                    )
                } else {
                    check(
                        cell.value.set(
                            resolvePassiveValues(
                                value = element,
                                root = root,
                                expectedType = elementType,
                                path = elementPath,
                                constructionDemand = constructionDemand,
                                invocationDemand = invocationDemand,
                                parent = parent,
                                creator = creator,
                            ),
                        ),
                    ) { "List element value was completed twice" }
                }
            }
            return result
        }
        return when (val type = expectedType.baseTypeDef) {
            is ViaductSchema.SimpleTypeDef -> value.toEngineResult(type)
            is ViaductSchema.CompositeTypeDef -> {
                val source = value as EngineObjectData.Sync
                val target = createObjectResult(source.schemaType(operation.world.schema), constructionDemand)
                resolveObjectOccurrence(
                    source = source,
                    occurrence = OEROccurrence(root, path, target, parent, source.additionalContextForResolver, invocationDemand),
                    constructionDemand = constructionDemand,
                    invocationDemand = invocationDemand,
                    creator = creator,
                )
                target
            }
            else -> error("Unsupported output type: ${type.name}")
        }
    }

    /**
     * Resolves [source] into the OER in [occurrence] under [constructionDemand]. On return, the object's
     * active work has been handed to the dispatcher, but cell values may still be pending. The
     * occurrence may be a root or a newly allocated child; it must enter this lifecycle only once.
     * Child objects in [source] recursively enter the same lifecycle.
     */
    fun resolveObjectOccurrence(
        source: EngineObjectData.Sync,
        occurrence: OEROccurrence,
        constructionDemand: Demand<SelectionForest>,
        invocationDemand: SelectionForest = constructionDemand.values,
        creator: ResolutionTask? = null,
    ) {
        // Close demand and prepare the task first: passive descent writes into its OER and may add
        // deferred work to it.
        val orchestration = closeAndPrepareOrchestration(occurrence, source, constructionDemand, creator)

        // Supply passive fields from the source.
        materializePassiveFields(orchestration, closedConstructionDemand(orchestration), invocationDemand)

        // Orchestrate: hand the active field and checker work to the dispatcher.
        operation.dispatcher.dispatchOrchestration(orchestration)
    }

    /**
     * Closes [constructionDemand] for [occurrence] and returns its prepared task, without supplying
     * passive fields or dispatching work. Callers that skip [resolveObjectOccurrence] must dispatch
     * the task themselves.
     *
     * @throws IllegalArgumentException when [source] does not have the type of [occurrence]'s target.
     */
    fun closeAndPrepareOrchestration(
        occurrence: OEROccurrence,
        source: EngineObjectData.Sync,
        constructionDemand: Demand<SelectionForest>,
        creator: ResolutionTask? = null,
    ): T {
        require(source.schemaType(operation.world.schema) == occurrence.target.type) {
            "Source type ${source.schemaType(operation.world.schema).name} does not match result type ${occurrence.target.type.name}"
        }

        // Close demand: add the inputs of every resolver and checker that will run, on the object
        // and on a fresh Query OER for its Query-rooted inputs.
        val queryResult = ObjectEngineResult.of(operation.world.schema.requireQueryTypeDef(), mutable = true)
        val queryOccurrence = OEROccurrence(queryResult, emptyList(), queryResult)
        val closedDemand = closeConstructionDemand(occurrence, queryOccurrence, source, constructionDemand)

        return prepareOrchestration(occurrence, queryOccurrence, source, closedDemand, creator)
    }

    /**
     * Passively resolves source-supplied argumentless fields into [orchestration] without creating or
     * dispatching an orchestration task. Ignores fields outside effective demand and
     * propagates the OER's closed demand to descendants. Parent fields are provided structurally,
     * and direct references belong to active resolution. Reference-bearing lists are omitted when
     * undemanded or deferred by [deferReferenceList].
     */
    internal fun materializePassiveFields(
        orchestration: T,
        closedConstructionDemand: Demand<ObjectSelectionForest>,
        invocationDemand: SelectionForest,
    ) {
        val objectOER = orchestration.objectOER
        val source = objectOER.source
        val occurrence = objectOER.occurrence
        val type = source.schemaType(operation.world.schema)
        // An empty source, such as a root's, supplies no passive fields.
        val sourceFields = source.getSelections().mapNotNull { type.field(it) }.filterNot { it.isParentField() }
        if (sourceFields.isEmpty()) return

        val invocationByKey = collect(invocationDemand, type).byKey()
        val passiveByKey = collect(invocationDemand + closedConstructionDemand.values, type).byKey()
        val passiveKeysByField = passiveByKey.keys.groupBy { it.field }
        sourceFields.forEach { field ->
            val fieldName = field.name
            val demandedKeys = passiveFieldKeys(source, field, passiveKeysByField[field])
            if (demandedKeys.isEmpty()) return@forEach
            require(field.args.isEmpty()) {
                "Resolver output must not supply argument-bearing field ${type.name}/$fieldName"
            }
            val value = source.outputValue(fieldName)
            // Direct references are classified as executable work by object orchestration.
            if (value.rootFieldReferenceDataOrNull() != null) return@forEach
            val containsReference = value.containsListElementRootFieldReference()
            for (key in demandedKeys) {
                check(key is ObjectEngineResult.GroundKey) {
                    "Passive returned field has an open key: $key"
                }
                val childInvocation = invocationByKey[key]?.subselections ?: selectionForestOf()
                val childConstruction =
                    closedConstructionDemand.descendants(key)
                if (
                    containsReference &&
                    deferReferenceList(
                        orchestration = orchestration,
                        selection = passiveByKey.getValue(key),
                        value = value,
                        invocationDemand = childInvocation,
                        constructionDemand = childConstruction,
                    )
                ) {
                    continue
                }
                occurrence.target.setCellValue(
                    key,
                    resolvePassiveValues(
                        value = value,
                        root = occurrence.root,
                        expectedType = key.field.outputType,
                        path = occurrence.coordinate(key),
                        constructionDemand = childConstruction,
                        invocationDemand = childInvocation,
                        parent = occurrence,
                        // Passive descent still runs in the task that created this orchestrator.
                        creator = orchestration.creator,
                    ),
                )
            }
        }
    }
}

/**
 * Whether this value is a list containing a root-field reference, directly or through nested lists.
 * References inside objects are handled by those objects' own resolution lifecycles.
 */
private fun Any?.containsListElementRootFieldReference(): Boolean = this is List<*> && any { it.rootFieldReferenceDataOrNull() != null || it.containsListElementRootFieldReference() }
