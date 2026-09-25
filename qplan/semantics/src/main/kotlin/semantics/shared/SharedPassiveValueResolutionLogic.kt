package semantics.shared

import model.EngineErrorData
import model.EngineResult
import model.EngineResultCell
import model.ErrorEngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.ObjectSelection
import model.ObjectSelectionForest
import model.PathComponent
import model.ResolverOutputData
import model.RootFieldReferenceData
import model.Selection
import model.SelectionForest
import model.invariants.conformsToResolverOutputSchemaType
import model.isParentField
import model.merge
import model.outputType
import model.outputValue
import model.requireField
import model.schemaType
import model.selectionForestOf
import model.toEngineResult
import viaduct.engine.api.EngineObjectData
import viaduct.graphql.schema.ViaductSchema

/**
 * Passively resolves resolver-returned values and initiates active resolution for each object
 * occurrence. This traversal owns result shape, occurrence identity, source coverage, and
 * propagation of construction versus invocation demand. Its factory hook prepares each object
 * before passive descent; the operation dispatcher schedules its active work afterward.
 * [T] connects the factory's result to the dispatcher's task type; [O] retains the concrete operation type.
 */
internal abstract class SharedPassiveValueResolutionLogic<
    T : SharedOrchestrationTask<*>,
    O : SharedOperationContext<SharedTaskDispatcher<T, *>>,
>(
    protected val operation: O,
) {
    /**
     * Ensures demand is closed and active resolution occurs for OERs on the fringe. Resolver26's
     * factory closes symbolic demand and declares runtime bindings. Other implementations can
     * close grounded demand and initialize their own task state. The returned task is ready
     * for passive descent; creating it does not dispatch active work.
     */
    protected abstract fun createOrchestrationTask(
        occurrence: OEROccurrence,
        source: EngineObjectData.Sync,
        constructionDemand: Demand<SelectionForest>,
    ): T

    /** Returns the checked/unchecked demand closed by [orchestration]. */
    protected open fun closedConstructionDemand(
        orchestration: T,
    ): Demand<ObjectSelectionForest> =
        Demand.checked(orchestration.objectOER.closedDemand)
            .merge(orchestration.objectOER.source.schemaType)

    /**
     * Collects selections for [type]. Resolver26 merges while retaining unresolved arguments;
     * grounded implementations also instantiate available argument bindings and coalesce keys that become equal.
     */
    protected abstract fun collect(
        selections: SelectionForest,
        type: ViaductSchema.Object,
    ): ObjectSelectionForest

    /**
     * Dispatches executable reference work discovered during passive list resolution. Resolver26 uses
     * its field-task protocol, including runtime binding support; Resolver21-23 use grounded field tasks.
     * Resolver01-03 execute synchronously, while Resolver06-08 queue tasks at their publication depth.
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
    )

    /**
     * Gates passive resolution of a reference-bearing list. Resolver26 defers conditional lists so their
     * references execute only if inclusion succeeds, and omits Never lists. Resolvers without
     * conditional activation use the default immediate passive resolution.
     *
     * @return true when the hook has scheduled or omitted the list; false to resolve it passively now.
     */
    protected open fun deferReferenceList(
        occurrence: OEROccurrence,
        selection: ObjectSelection,
        value: ResolverOutputData?,
        invocationDemand: SelectionForest,
        constructionDemand: Demand<SelectionForest>,
    ): Boolean = false

    /**
     * Passively resolves [value] at [path], preserving nulls, errors, and list positions. Each object
     * gets an OER and an orchestration lifecycle; lists retain [parent] as their containing object.
     * Direct root-field references must be followed by the caller; list references use
     * [resolveListReference]. The returned result may still have pending active fields.
     *
     * [constructionDemand] is the demand to close for returned objects. [invocationDemand] is the
     * demand supplied to the producer and is used to validate selective output.
     */
    fun resolvePassiveValues(
        value: ResolverOutputData?,
        root: ObjectEngineResult,
        expectedType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
        path: List<PathComponent>,
        constructionDemand: Demand<SelectionForest>,
        invocationDemand: SelectionForest,
        parent: OEROccurrence? = null,
    ): EngineResult? {
        require(value.conformsToResolverOutputSchemaType(expectedType)) {
            "Resolver output does not conform to $expectedType"
        }
        return when (value) {
            null -> null
            is EngineErrorData -> ErrorEngineResult.of(value)
            is RootFieldReferenceData ->
                error("A direct root-field reference must be resolved before passive resolution")
            is EngineObjectData.Sync -> {
                val target = ObjectEngineResult.of(type = value.schemaType, mutable = true)
                resolvePassiveObjectValues(
                    source = value,
                    occurrence = OEROccurrence(root, path, target, parent),
                    constructionDemand = constructionDemand,
                    invocationDemand = invocationDemand,
                )
                target
            }
            is List<*> -> {
                val containingOccurrence = requireNotNull(parent)
                val elementType = checkNotNull(expectedType.unwrapList())
                val result = ListEngineResult.ofPendingValues(elementType, value.size)
                value.forEachIndexed { index, element ->
                    val elementPath = path + ListEngineResult.Index.of(index)
                    val cell = result[index]
                    if (element is RootFieldReferenceData) {
                        val key = elementPath.filterIsInstance<ObjectEngineResult.ObjectKey>().last()
                        val selection = selectionForestOf(
                            Selection.of(
                                key,
                                setOf(containingOccurrence.target.type),
                                constructionDemand.values,
                            ),
                        ).merge(containingOccurrence.target.type).byKey().getValue(key)
                        resolveListReference(
                            reference = element,
                            cell = cell,
                            path = elementPath,
                            expectedType = elementType,
                            selection = selection,
                            constructionDemand = constructionDemand,
                            invocationDemand = invocationDemand,
                            parent = containingOccurrence,
                        )
                    } else {
                        check(
                            cell.getValue().complete(
                                resolvePassiveValues(
                                    value = element,
                                    root = root,
                                    expectedType = elementType,
                                    path = elementPath,
                                    constructionDemand = constructionDemand,
                                    invocationDemand = invocationDemand,
                                    parent = parent,
                                ),
                            ),
                        ) { "List element value was completed twice" }
                    }
                }
                result
            }
            else -> value.toEngineResult(expectedType.baseTypeDef as ViaductSchema.SimpleTypeDef)
        }
    }

    /**
     * Passively resolves [source] into the OER in [occurrence]. Prepares the object's closed demand
     * before resolving passive fields recursively, then dispatches its active work. The occurrence
     * may be a root or a newly allocated child; it must enter this lifecycle only once.
     */
    fun resolvePassiveObjectValues(
        source: EngineObjectData.Sync,
        occurrence: OEROccurrence,
        constructionDemand: Demand<SelectionForest>,
        invocationDemand: SelectionForest = constructionDemand.values,
    ) {
        require(source.schemaType == occurrence.target.type) {
            "Source type ${source.schemaType.name} does not match result type ${occurrence.target.type.name}"
        }
        val orchestration = createOrchestrationTask(occurrence, source, constructionDemand)
        materializePassiveFields(
            orchestration.objectOER,
            closedConstructionDemand(orchestration),
            invocationDemand,
        )
        operation.dispatcher.dispatchOrchestrator(orchestration)
    }

    /**
     * Passively resolves source-supplied argumentless fields into [objectOER] without creating or
     * dispatching an orchestration task. Validates selective output against [invocationDemand] and
     * propagates the OER's closed demand to descendants. Parent fields are provided structurally,
     * and direct references belong to active resolution. Reference-bearing lists are omitted when
     * undemanded or deferred by [deferReferenceList].
     */
    internal fun materializePassiveFields(
        objectOER: SharedOERContext,
        closedDemand: Demand<ObjectSelectionForest>,
        invocationDemand: SelectionForest,
    ) {
        val source = objectOER.source
        val occurrence = objectOER.occurrence
        val type = source.schemaType
        val invocationByKey = collect(invocationDemand, type).byKey()
        val passiveByKey = collect(invocationDemand + closedDemand.values, type).byKey()
        val checkedByKey = closedDemand.checked.byKey()
        val uncheckedByKey = closedDemand.unchecked.byKey()
        if (operation.world.selectiveResolvers) {
            val selectedNames = invocationByKey.keys.mapTo(linkedSetOf()) { it.field.name }
            val unselectedFields =
                source.getSelections()
                    .filterNot { type.requireField(it).isParentField() }.toSet() - selectedNames
            require(unselectedFields.isEmpty()) {
                "Selective resolver output ${type.name} contains unselected fields: " +
                    unselectedFields.joinToString()
            }
        }
        source.getSelections().forEach { fieldName ->
            val field = type.requireField(fieldName)
            if (field.isParentField()) return@forEach
            require(field.args.isEmpty()) {
                "Resolver output must not supply argument-bearing field ${type.name}/$fieldName"
            }
            val value = source.outputValue(fieldName)
            // Direct references are classified as executable work by object orchestration.
            if (value is RootFieldReferenceData) return@forEach
            val containsReference = value.containsListElementRootFieldReference()
            val demandedKeys = passiveByKey.keys.filterTo(linkedSetOf()) { it.field == field }
            if (demandedKeys.isEmpty()) {
                if (containsReference) return@forEach
                demandedKeys += ObjectEngineResult.GroundKey.of(field, emptyMap())
            }
            for (key in demandedKeys) {
                check(key is ObjectEngineResult.GroundKey) {
                    "Passive returned field has an open key: $key"
                }
                val childInvocation = invocationByKey[key]?.subselections ?: selectionForestOf()
                val childConstruction =
                    Demand(
                        checked = checkedByKey[key]?.subselections ?: selectionForestOf(),
                        unchecked = uncheckedByKey[key]?.subselections ?: selectionForestOf(),
                    )
                if (
                    containsReference &&
                    deferReferenceList(
                        occurrence = occurrence,
                        selection = passiveByKey.getValue(key),
                        value = value,
                        invocationDemand = childInvocation,
                        constructionDemand = childConstruction,
                    )
                ) continue
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
private fun Any?.containsListElementRootFieldReference(): Boolean =
    this is List<*> && any { it is RootFieldReferenceData || it.containsListElementRootFieldReference() }
