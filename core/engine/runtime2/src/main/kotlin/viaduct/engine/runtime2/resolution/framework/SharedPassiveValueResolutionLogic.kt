package viaduct.engine.runtime2.resolution.framework

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
import viaduct.engine.runtime2.model.invariants.conformsToResolverOutputSchemaType
import viaduct.engine.runtime2.model.isParentField
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.outputType
import viaduct.engine.runtime2.model.outputValue
import viaduct.engine.runtime2.model.requireField
import viaduct.engine.runtime2.model.schemaType
import viaduct.engine.runtime2.model.selectionForestOf
import viaduct.engine.runtime2.model.toEngineResult
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
    O : SharedOperationContext<SharedTaskDispatcher<T, *, *>>,
>(
    protected val operation: O,
) {
    /**
     * Ensures demand is closed and active resolution occurs for OERs on the fringe. Resolution's
     * factory closes symbolic demand and declares runtime bindings. Other implementations can
     * close grounded demand and initialize their own task state. The returned task is ready
     * for passive descent; creating it does not dispatch active work.
     */
    protected abstract fun createOrchestrationTask(
        occurrence: OEROccurrence,
        source: EngineObjectData.Sync,
        constructionDemand: Demand<SelectionForest>,
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
            .merge(orchestration.objectOER.source.schemaType)

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
        if (value == null) return null
        if (value is EngineErrorData) return ErrorEngineResult.of(value)
        check(value !is RootFieldReferenceData) {
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
                        cell.value.set(
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
            return result
        }
        return when (val type = expectedType.baseTypeDef) {
            is ViaductSchema.SimpleTypeDef -> value.toEngineResult(type)
            is ViaductSchema.CompositeTypeDef -> {
                val source = value as EngineObjectData.Sync
                val target = createObjectResult(source.schemaType, constructionDemand)
                resolvePassiveObjectValues(
                    source = source,
                    occurrence = OEROccurrence(root, path, target, parent),
                    constructionDemand = constructionDemand,
                    invocationDemand = invocationDemand,
                )
                target
            }
            else -> error("Unsupported output type: ${type.name}")
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
            orchestration,
            closedConstructionDemand(orchestration),
            invocationDemand,
        )
        operation.dispatcher.dispatchOrchestration(orchestration)
    }

    /**
     * Passively resolves source-supplied argumentless fields into [orchestration] without creating or
     * dispatching an orchestration task. Validates selective output against [invocationDemand] and
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
        val type = source.schemaType
        val invocationByKey = collect(invocationDemand, type).byKey()
        val passiveByKey = collect(invocationDemand + closedConstructionDemand.values, type).byKey()
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
private fun Any?.containsListElementRootFieldReference(): Boolean = this is List<*> && any { it is RootFieldReferenceData || it.containsListElementRootFieldReference() }
