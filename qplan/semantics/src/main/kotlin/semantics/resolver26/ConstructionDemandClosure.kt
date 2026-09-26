package semantics.resolver26

import model.Assumptions
import model.InclusionCondition
import model.ObjectEngineResult
import model.ObjectSelection
import model.ObjectSelectionForest
import model.PathComponent
import model.ResolverOccurrenceId
import model.RootFieldReferenceData
import model.SelectionForest
import model.selectionForestOf
import model.merge
import model.guardedBy
import model.registry.FieldResolver
import model.registry.InstantiatedFieldPathDefinition
import model.registry.ResolverFragments
import model.registry.VariableInstanceDefinition
import model.requireQueryTypeDef
import model.schemaType
import model.outputValue
import model.satisfiableAlternatives
import semantics.shared.argumentsContainErrorValue
import semantics.shared.ResolverInputConstructionDemand
import viaduct.engine.api.EngineObjectData
import semantics.shared.OEROccurrence
import viaduct.graphql.schema.ViaductSchema

/**
 * The result of joint construction-demand closure for one object orchestration and its associated
 * Query OER. Retained across binding declaration, dispatch validation, and field installation.
 */
internal class ClosedConstructionDemandContext(
    val objectRooted: ClosedOERConstructionDemandContext,
    val queryRooted: ClosedOERConstructionDemandContext,
)

/** Closed symbolic demand and its executable occurrences for one side of an orchestration pair. */
internal class ClosedOERConstructionDemandContext(
    val demand: ObjectSelectionForest,
    val fieldResolverOccurrences:
        Map<ObjectEngineResult.ObjectKey, FieldResolverOccurrence>,
    val rootFieldReferenceOccurrences:
        Map<ObjectEngineResult.ObjectKey, RootFieldReferenceOccurrence>,
    val variableProviderReadsByResolverOccurrence:
        Map<ResolverOccurrenceId, List<VariableProviderReadOccurrence>>,
)

/**
 * One planned provider-path read that produces an instantiated variable binding.
 * The definition identifies the provider path and destination variable; the condition controls
 * execution, and the reader path identifies the consumer for cycle checking. The containing
 * object or Query result supplies the root from which the provider path is read.
 */
internal class VariableProviderReadOccurrence(
    val providerResult: ObjectEngineResult,
    val definition: InstantiatedFieldPathDefinition,
    val readerPath: List<PathComponent>,
)

/**
 * Closes demand for one object orchestration and returns the closed construction demand,
 * the field resolver and root field reference occurrences that satisfy it, and the object- and
 * Query-fragment variable-provider reads required by those resolver occurrences. The associated
 * Query OER has no passive source; every demanded Query field uses its registered resolver.
 */
internal fun EngineObjectData.Sync.closeOrchestratorConstructionDemand(
    world: Assumptions,
    objectOccurrence: OEROccurrence,
    queryOccurrence: OEROccurrence,
    initialDemand: SelectionForest,
): ClosedConstructionDemandContext {
    require(schemaType == objectOccurrence.target.type) {
        "Source type ${schemaType.name} does not match result type ${objectOccurrence.target.type.name}"
    }
    require(queryOccurrence.target.type == world.schema.requireQueryTypeDef()) {
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

    // These become all construction demand rooted at the two OERs, expressed through possibly
    // abstract or concrete field coordinates.
    var objectDemand: SelectionForest =
        initialDemand + initialDemand.liftParentConstructionDemand(world)
    var queryDemand: SelectionForest = selectionForestOf()

    // These eventually contain the concrete top-level keys handled by each OER's
    // standard-resolution machinery.
    val objectResolvers = linkedMapOf<ObjectEngineResult.ObjectKey, ResolverContext>()
    val queryResolvers = linkedMapOf<ObjectEngineResult.ObjectKey, ResolverContext>()

    var demandNotClosed: Boolean
    do {
        // One joint step discovers newly activated resolvers on both sides before routing their
        // fragments back into the object or shared Query demand.
        val newObjectResolverInputs =
            newResolverInputDemand(
                world = world,
                type = schemaType,
                occurrence = objectOccurrence,
                accumulatedDemand = objectDemand,
                requiredResolvers = objectResolvers,
                requiresStandardResolution = ::requiresStandardResolution,
            )
        val newQueryResolverInputs =
            newResolverInputDemand(
                world = world,
                type = queryOccurrence.target.type,
                occurrence = queryOccurrence,
                accumulatedDemand = queryDemand,
                requiredResolvers = queryResolvers,
                requiresStandardResolution = { true },
            )
        demandNotClosed = newObjectResolverInputs != null || newQueryResolverInputs != null
        if (demandNotClosed) {
            val objectResolverInputs =
                newObjectResolverInputs ?: ResolverInputConstructionDemand.EMPTY
            val queryResolverInputs =
                newQueryResolverInputs ?: ResolverInputConstructionDemand.EMPTY
            val queryInputSelections =
                objectResolverInputs.queryFragment +
                    queryResolverInputs.objectFragment +
                    queryResolverInputs.queryFragment
            objectDemand +=
                objectResolverInputs.objectFragment +
                    objectResolverInputs.objectFragment.liftParentConstructionDemand(world)
            queryDemand +=
                queryInputSelections +
                    queryInputSelections.liftParentConstructionDemand(world)
        }
    } while (demandNotClosed)

    return ClosedConstructionDemandContext(
        objectRooted =
            closeOERConstructionDemand(
                world = world,
                passiveSource = this,
                occurrence = objectOccurrence,
                accumulatedDemand = objectDemand,
                requiredResolvers = objectResolvers,
                requiresStandardResolution = ::requiresStandardResolution,
                objectProviderResult = objectOccurrence.target,
                queryProviderResult = queryOccurrence.target,
            ),
        queryRooted =
            closeOERConstructionDemand(
                world = world,
                passiveSource = null,
                occurrence = queryOccurrence,
                accumulatedDemand = queryDemand,
                requiredResolvers = queryResolvers,
                requiresStandardResolution = { true },
                objectProviderResult = queryOccurrence.target,
                queryProviderResult = queryOccurrence.target,
            ),
    )
}

private fun newResolverInputDemand(
    world: Assumptions,
    type: ViaductSchema.Object,
    occurrence: OEROccurrence,
    accumulatedDemand: SelectionForest,
    requiredResolvers: MutableMap<ObjectEngineResult.ObjectKey, ResolverContext>,
    requiresStandardResolution: (ObjectEngineResult.ObjectKey) -> Boolean,
): ResolverInputConstructionDemand? {
    var objectFragment: SelectionForest = selectionForestOf()
    var queryFragment: SelectionForest = selectionForestOf()
    var expanded = false
    accumulatedDemand
        .merge(type)
        .byKey()
        .filter { (objectKey, _) ->
            objectKey.field in world.resolverRegistry && requiresStandardResolution(objectKey)
        }
        .forEach { (objectKey, resolverSelection) ->
            // This selection belongs in `requiredResolvers`; create its occurrence only once.
            val resolverContext =
                requiredResolvers.getOrPut(objectKey) {
                    createResolverContext(world, occurrence, objectKey)
                }
            if (
                objectKey is ObjectEngineResult.GroundKey &&
                objectKey.arguments.argumentsContainErrorValue()
            ) {
                // An argument-error occurrence must publish its field error but cannot invoke its
                // resolver or contribute resolver-input demand.
                return@forEach
            }
            // A merged selection can reveal a new condition under which an existing resolver key
            // is active. Only those new alternatives expand its fixed input fragments.
            val newKeyInclusions =
                resolverSelection.inclusionCondition
                    .satisfiableAlternatives()
                    .filter(resolverContext.accumulatedKeyInclusions::add)
            newKeyInclusions.forEach { keyInclusion ->
                expanded = true
                objectFragment +=
                    resolverContext.fragments.objectFragment.constructionSelections
                        .guardedBy(keyInclusion)
                queryFragment +=
                    resolverContext.fragments.queryFragment.constructionSelections
                        .guardedBy(keyInclusion)
            }
        }
    return ResolverInputConstructionDemand(
        objectFragment = objectFragment,
        queryFragment = queryFragment,
    ).takeIf { expanded }
}

private fun closeOERConstructionDemand(
    world: Assumptions,
    passiveSource: EngineObjectData.Sync?,
    occurrence: OEROccurrence,
    accumulatedDemand: SelectionForest,
    requiredResolvers: Map<ObjectEngineResult.ObjectKey, ResolverContext>,
    requiresStandardResolution: (ObjectEngineResult.ObjectKey) -> Boolean,
    objectProviderResult: ObjectEngineResult,
    queryProviderResult: ObjectEngineResult,
): ClosedOERConstructionDemandContext {
    val type = passiveSource?.schemaType ?: occurrence.target.type
    val closedDemand = accumulatedDemand.merge(type)
    val requiredResolverSelections =
        closedDemand
            .byKey()
            .filter { (objectKey, _) ->
                objectKey.field in world.resolverRegistry && requiresStandardResolution(objectKey)
            }
    check(requiredResolverSelections.keys == requiredResolvers.keys) {
        "Resolver26 closed demand and required resolvers are misaligned"
    }
    val fieldResolverOccurrences =
        requiredResolvers.mapValues { (objectKey, resolverContext) ->
            resolverContext.toFieldResolverOccurrence(
                selection = closedDemand.byKey().getValue(objectKey),
            )
        }
    val referenceOccurrences =
        passiveSource?.discoverRootFieldReferences(world, occurrence, closedDemand).orEmpty()
    check(fieldResolverOccurrences.keys.intersect(referenceOccurrences.keys).isEmpty()) {
        "Resolver26 classified one field as both an ordinary resolver and a root reference"
    }
    return ClosedOERConstructionDemandContext(
        demand = closedDemand,
        fieldResolverOccurrences = fieldResolverOccurrences,
        rootFieldReferenceOccurrences = referenceOccurrences,
        variableProviderReadsByResolverOccurrence =
            requiredResolvers.map { (objectKey, resolverContext) ->
                val resolverOccurrenceId =
                    fieldResolverOccurrences.getValue(objectKey).resolverOccurrenceId
                val providerReads =
                    if (
                        objectKey is ObjectEngineResult.GroundKey &&
                        objectKey.arguments.argumentsContainErrorValue()
                    ) {
                        emptyList()
                    } else {
                        resolverContext.fragments.objectFragment.pathVariableDefinitions.map { definition ->
                            VariableProviderReadOccurrence(
                                providerResult = objectProviderResult,
                                definition = definition,
                                readerPath = occurrence.coordinate(objectKey),
                            )
                        } + resolverContext.fragments.queryFragment.pathVariableDefinitions.map { definition ->
                            VariableProviderReadOccurrence(
                                providerResult = queryProviderResult,
                                definition = definition,
                                readerPath = occurrence.coordinate(objectKey),
                            )
                        }
                    }
                resolverOccurrenceId to providerReads
            }.toMap(),
    )
}

// Closure inputs and bookkeeping for one required standard resolver occurrence.
private data class ResolverContext(
    val invocationRoot: ObjectEngineResult,
    val invocationPath: List<PathComponent>,
    val resolverOccurrenceId: ResolverOccurrenceId,
    val resolver: FieldResolver,
    val variableDefinitions: List<VariableInstanceDefinition>,
    val fragments: ResolverFragments,
) {
    val accumulatedKeyInclusions: MutableSet<InclusionCondition> = linkedSetOf()

    fun toFieldResolverOccurrence(
        selection: ObjectSelection,
    ): FieldResolverOccurrence =
        FieldResolverOccurrence(
            selection = selection,
            invocationRoot = invocationRoot,
            invocationPath = invocationPath,
            resolverOccurrenceId = resolverOccurrenceId,
            resolver = resolver,
            variableDefinitions = variableDefinitions,
            fragments = fragments,
        )
}

private fun EngineObjectData.Sync.discoverRootFieldReferences(
    world: Assumptions,
    occurrence: OEROccurrence,
    demand: ObjectSelectionForest,
): Map<ObjectEngineResult.ObjectKey, RootFieldReferenceOccurrence> =
    buildMap {
        demand.byKey().forEach { (objectKey, selection) ->
            if (selection.inclusionCondition === InclusionCondition.Never) return@forEach
            if (!isPresent(objectKey.field.name)) return@forEach
            val reference = outputValue(objectKey.field.name) as? RootFieldReferenceData
                ?: return@forEach
            require(objectKey is ObjectEngineResult.GroundKey) {
                "Source-provided root-field reference has an open consumer key: $objectKey"
            }
            val consumerArguments = objectKey.arguments
            require(consumerArguments is model.Arguments.Resolved && consumerArguments.fieldValues.isEmpty()) {
                "Source-provided root-field reference must occupy an argumentless field: $objectKey"
            }
            require(reference.targetField in world.resolverRegistry) {
                "Root-field-reference target has no registered resolver: " +
                    "${reference.targetField.containingDef.name}/${reference.targetField.name}"
            }
            check(
                put(
                    objectKey,
                    RootFieldReferenceOccurrence(
                        selection = selection,
                        reference = reference,
                        publicationPath = occurrence.coordinate(objectKey),
                    ),
                ) == null,
            ) {
                "Resolver26 discovered a root-field reference twice: $objectKey"
            }
        }
    }

private fun createResolverContext(
    world: Assumptions,
    occurrence: OEROccurrence,
    objectKey: ObjectEngineResult.ObjectKey,
): ResolverContext {
    val resolver: FieldResolver = world.resolverRegistry.resolver(objectKey.field)
    val resolverOccurrenceId =
        ResolverOccurrenceId.at(
            occurrence.root,
            occurrence.coordinate(objectKey),
        )
    val fragments = resolver.instantiateFragments(resolverOccurrenceId)
    if (
        objectKey is ObjectEngineResult.GroundKey &&
        objectKey.arguments.argumentsContainErrorValue()
    ) {
        return ResolverContext(
            invocationRoot = occurrence.root,
            invocationPath = occurrence.coordinate(objectKey),
            resolverOccurrenceId = resolverOccurrenceId,
            resolver = resolver,
            variableDefinitions = fragments.queryFragment.variableDefinitions,
            fragments = fragments,
        )
    }
    return ResolverContext(
        invocationRoot = occurrence.root,
        invocationPath = occurrence.coordinate(objectKey),
        resolverOccurrenceId = resolverOccurrenceId,
        resolver = resolver,
        variableDefinitions = resolver.instantiatedVariableDefinitions(resolverOccurrenceId),
        fragments = fragments,
    )
}

// Returns true if the field is not present yet has a standard resolver, which means it needs standard resolution
private fun EngineObjectData.Sync.requiresStandardResolution(
    objectKey: ObjectEngineResult.ObjectKey,
): Boolean {
    if (!isPresent(objectKey.field.name)) return true

    require(objectKey.field.args.isEmpty()) {
        "Resolver output must not supply argument-bearing field " +
            "${schemaType.name}/${objectKey.field.name}"
    }
    return false
}
