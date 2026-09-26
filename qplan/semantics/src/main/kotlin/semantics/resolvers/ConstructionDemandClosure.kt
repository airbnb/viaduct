package semantics.resolvers

import model.ObjectEngineResult
import model.ObjectSelectionForest
import model.SelectionForest
import model.merge
import model.requireQueryTypeDef
import model.schemaType
import model.selectionForestOf
import semantics.resolver26.liftParentConstructionDemand
import semantics.shared.Demand
import semantics.shared.OEROccurrence
import semantics.shared.ResolverInputConstructionDemand
import semantics.shared.SharedOperationContext
import semantics.shared.applicableGroundSelections
import semantics.shared.argumentsContainErrorValue
import semantics.shared.plus
import viaduct.engine.api.EngineObjectData
import viaduct.graphql.schema.ViaductSchema

/**
 * Closes object- and Query-rooted construction demand for one orchestration scope.
 *
 * Object-side resolvers contribute object fragments to
 * [OrchestratorConstructionDemand.objectRooted] and Query fragments to
 * [OrchestratorConstructionDemand.queryRooted]. Query-side resolvers contribute both fragments
 * back to the Query-rooted component. Every resolver input contribution is checked even when
 * unchecked demand activated its owner.
 *
 * Each step grounds selections under existing bindings, binds variables for newly discovered
 * standard resolvers, and adds their direct input-fragment demand as checked. Fields supplied by
 * the object source remain passive. The associated Query OER has no passive source; every demanded
 * Query field uses its registered resolver. Only demand and the expanded-key sets change between
 * steps.
 */
internal fun EngineObjectData.Sync.closeOrchestratorConstructionDemand(
    operation: SharedOperationContext<*>,
    objectOccurrence: OEROccurrence,
    queryOccurrence: OEROccurrence,
    initialDemand: OrchestratorConstructionDemand<SelectionForest>,
): OrchestratorConstructionDemand<ObjectSelectionForest> {
    require(schemaType == objectOccurrence.target.type) {
        "Source type ${schemaType.name} does not match result type ${objectOccurrence.target.type.name}"
    }
    require(queryOccurrence.target.type == operation.world.schema.requireQueryTypeDef()) {
        "Query-rooted construction demand must target Query"
    }
    require(
        queryOccurrence.root === queryOccurrence.target &&
            queryOccurrence.path.isEmpty(),
    ) {
        "Query-rooted construction demand must use a root OER occurrence"
    }
    require(queryOccurrence.root !== objectOccurrence.root) {
        "Resolver Query demand must not reuse the containing operation root"
    }
    // `accumulatedDemand` will become all construction demand rooted at this OER pair.
    var accumulatedDemand = initialDemand

    // Unlike Resolver26, these resolvers ground each key before expanding its fixed input.
    val expandedObjectResolverKeys = linkedSetOf<ObjectEngineResult.GroundKey>()
    val expandedQueryResolverKeys = linkedSetOf<ObjectEngineResult.GroundKey>()

    var demandNotClosed: Boolean
    do {
        // Assume optimistically that demand is closed. Discovering another resolver key on either
        // root adds its fixed input demand and requires another pass.
        demandNotClosed = false
        val groundedDemand =
            accumulatedDemand.groundWithLiftedParentDemand(
                operation = operation,
                objectType = objectOccurrence.target.type,
            )
        val newObjectResolverKeys =
            groundedDemand.objectRooted.newResolverKeys(
                operation = operation,
                expandedKeys = expandedObjectResolverKeys,
                requiresStandardResolution = ::requiresStandardResolution,
            )
        val newQueryResolverKeys =
            groundedDemand.queryRooted.newResolverKeys(
                operation = operation,
                expandedKeys = expandedQueryResolverKeys,
                requiresStandardResolution = { true },
            )

        if (newObjectResolverKeys.isNotEmpty() || newQueryResolverKeys.isNotEmpty()) {
            demandNotClosed = true
            newObjectResolverKeys.bindFromArguments(
                operation,
                objectOccurrence.root,
                objectOccurrence.path,
            )
            newQueryResolverKeys.bindFromArguments(
                operation,
                queryOccurrence.root,
                queryOccurrence.path,
            )

            val objectResolverInputs =
                newObjectResolverKeys.resolverInputDemand(operation, objectOccurrence)
            val queryResolverInputs =
                newQueryResolverKeys.resolverInputDemand(operation, queryOccurrence)
            val queryInputSelections =
                objectResolverInputs.queryFragment +
                    queryResolverInputs.objectFragment +
                    queryResolverInputs.queryFragment
            accumulatedDemand =
                groundedDemand +
                    OrchestratorConstructionDemand(
                        objectRooted = Demand.checked(objectResolverInputs.objectFragment),
                        queryRooted = Demand.checked(queryInputSelections),
                    )
            expandedObjectResolverKeys += newObjectResolverKeys
            expandedQueryResolverKeys += newQueryResolverKeys
        }
    } while (demandNotClosed)

    return accumulatedDemand.groundWithLiftedParentDemand(
        operation = operation,
        objectType = objectOccurrence.target.type,
    )
}

private fun OrchestratorConstructionDemand<SelectionForest>.groundWithLiftedParentDemand(
    operation: SharedOperationContext<*>,
    objectType: ViaductSchema.Object,
): OrchestratorConstructionDemand<ObjectSelectionForest> {
    val objectWithParentDemand =
        objectRooted + objectRooted.liftParentConstructionDemand(operation.world)
    val queryWithParentDemand =
        queryRooted + queryRooted.liftParentConstructionDemand(operation.world)
    return OrchestratorConstructionDemand(
        objectRooted = objectWithParentDemand.applicableGroundSelections(operation, objectType),
        queryRooted =
            queryWithParentDemand.applicableGroundSelections(
                operation,
                operation.world.schema.requireQueryTypeDef(),
            ),
    )
}

private fun Demand<ObjectSelectionForest>.newResolverKeys(
    operation: SharedOperationContext<*>,
    expandedKeys: Set<ObjectEngineResult.GroundKey>,
    requiresStandardResolution: (ObjectEngineResult.GroundKey) -> Boolean,
): Set<ObjectEngineResult.GroundKey> =
    values
        .merge(checked.type)
        .groundKeys()
        .filterTo(linkedSetOf()) { key ->
            key !in expandedKeys &&
                !key.arguments.argumentsContainErrorValue() &&
                key.field in operation.world.resolverRegistry &&
                requiresStandardResolution(key)
        }

private fun Set<ObjectEngineResult.GroundKey>.resolverInputDemand(
    operation: SharedOperationContext<*>,
    occurrence: OEROccurrence,
): ResolverInputConstructionDemand {
    var objectFragment: SelectionForest = selectionForestOf()
    var queryFragment: SelectionForest = selectionForestOf()
    forEach { key ->
        val fragments =
            operation.world.resolverRegistry
                .resolver(key.field)
                .instantiateFragmentsAt(occurrence.root, occurrence.coordinate(key))
        objectFragment += fragments.objectFragment.constructionSelections
        queryFragment += fragments.queryFragment.constructionSelections
    }
    return ResolverInputConstructionDemand(objectFragment, queryFragment)
}

private fun EngineObjectData.Sync.requiresStandardResolution(
    key: ObjectEngineResult.GroundKey,
): Boolean {
    if (!isPresent(key.field.name)) return true
    require(key.field.args.isEmpty()) {
        "Resolver output must not supply argument-bearing field " +
            "${schemaType.name}/${key.field.name}"
    }
    return false
}
