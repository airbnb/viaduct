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
import semantics.shared.CycleTask
import semantics.shared.fieldResolverCycleTask
import viaduct.graphql.schema.ViaductSchema
import model.Arguments
import model.VariableInstanceId
import model.registry.FieldChecker
import model.usedVariables
import semantics.shared.Demand
import semantics.shared.fieldCheckerCycleTask
import semantics.shared.merge
import semantics.shared.plus

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
    val constructionDemand: Demand<ObjectSelectionForest>,
    val fieldCheckerOccurrences: Map<ObjectEngineResult.ObjectKey, FieldCheckerOccurrence>,
    val fieldResolverOccurrences: Map<ObjectEngineResult.ObjectKey, FieldResolverOccurrence>,
    val rootFieldReferenceOccurrences: Map<ObjectEngineResult.ObjectKey, RootFieldReferenceOccurrence>,
    val variableProviderReadsByResolverOccurrence: Map<ResolverOccurrenceId, List<VariableProviderReadOccurrence>>,
) {
    val demand: ObjectSelectionForest = constructionDemand.values.merge(constructionDemand.checked.type)
}

/**
 * One planned provider-path read that produces an instantiated variable binding.
 * The definition identifies the provider path and destination variable; the condition controls
 * execution, and [reader] identifies the consumer for cycle checking. The containing
 * object or Query result supplies the root from which the provider path is read.
 */
internal class VariableProviderReadOccurrence(
    val providerResult: ObjectEngineResult,
    val definition: InstantiatedFieldPathDefinition,
    val reader: CycleTask,
    val inclusionCondition: InclusionCondition,
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
): ClosedConstructionDemandContext = closeOrchestratorConstructionDemand(world, objectOccurrence, queryOccurrence, Demand.checked(initialDemand))

internal fun EngineObjectData.Sync.closeOrchestratorConstructionDemand(
    world: Assumptions,
    objectOccurrence: OEROccurrence,
    queryOccurrence: OEROccurrence,
    initialDemand: Demand<SelectionForest>,
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
    var objectDemand: Demand<SelectionForest> =
        initialDemand + initialDemand.liftParentConstructionDemand(world)
    var queryDemand: Demand<SelectionForest> = Demand.EMPTY

    // These eventually contain the concrete top-level keys handled by each OER's
    // standard-resolution machinery.
    val objectResolvers = linkedMapOf<ObjectEngineResult.ObjectKey, ResolverContext>()
    val queryResolvers = linkedMapOf<ObjectEngineResult.ObjectKey, ResolverContext>()
    val expansionState = SymbolicExpansionState()
    val objectCheckers = linkedMapOf<ObjectEngineResult.ObjectKey, CheckerContext>()
    val queryCheckers = linkedMapOf<ObjectEngineResult.ObjectKey, CheckerContext>()

    var demandNotClosed: Boolean
    do {
        // One joint step discovers newly activated resolvers on both sides before routing their
        // fragments back into the object or shared Query demand.
        val newObjectResolverInputs =
            newResolverInputDemand(
                world = world,
                type = schemaType,
                occurrence = objectOccurrence,
                accumulatedDemand = objectDemand.values,
                requiredResolvers = objectResolvers,
                expansionState = expansionState,
                requiresStandardResolution = ::requiresStandardResolution,
            )
        val newQueryResolverInputs =
            newResolverInputDemand(
                world = world,
                type = queryOccurrence.target.type,
                occurrence = queryOccurrence,
                accumulatedDemand = queryDemand.values,
                requiredResolvers = queryResolvers,
                expansionState = expansionState,
                requiresStandardResolution = { true },
            )
        val newObjectCheckerInputs = newCheckerInputDemand(
            world,
            objectOccurrence,
            objectDemand.checked,
            objectCheckers,
            expansionState,
        )
        val newQueryCheckerInputs = newCheckerInputDemand(
            world,
            queryOccurrence,
            queryDemand.checked,
            queryCheckers,
            expansionState,
        )
        demandNotClosed = newObjectResolverInputs != null ||
            newQueryResolverInputs != null ||
            newObjectCheckerInputs != null ||
            newQueryCheckerInputs != null
        if (demandNotClosed) {
            val objectResolverInputs =
                newObjectResolverInputs ?: ResolverInputConstructionDemand.EMPTY
            val queryResolverInputs =
                newQueryResolverInputs ?: ResolverInputConstructionDemand.EMPTY
            val queryInputSelections =
                objectResolverInputs.queryFragment +
                    queryResolverInputs.objectFragment +
                    queryResolverInputs.queryFragment
            val objectCheckerInputs = newObjectCheckerInputs ?: ResolverInputConstructionDemand.EMPTY
            val queryCheckerInputs = newQueryCheckerInputs ?: ResolverInputConstructionDemand.EMPTY
            val objectInputs = Demand(objectResolverInputs.objectFragment, objectCheckerInputs.objectFragment)
            val queryInputs = Demand(
                queryInputSelections,
                objectCheckerInputs.queryFragment + queryCheckerInputs.objectFragment + queryCheckerInputs.queryFragment,
            )
            objectDemand += objectInputs + objectInputs.liftParentConstructionDemand(world)
            queryDemand += queryInputs + queryInputs.liftParentConstructionDemand(world)
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
                requiredCheckers = objectCheckers,
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
                requiredCheckers = queryCheckers,
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
    expansionState: SymbolicExpansionState,
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
        }.forEach { (objectKey, resolverSelection) ->
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
            if (newKeyInclusions.isNotEmpty()) {
                // A reserved but statically excluded occurrence cannot extend a fragment cycle.
                expansionState.register(occurrence.root, objectKey, checker = false, resolverContext.variableDefinitions)
            }
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
    accumulatedDemand: Demand<SelectionForest>,
    requiredCheckers: Map<ObjectEngineResult.ObjectKey, CheckerContext>,
    requiredResolvers: Map<ObjectEngineResult.ObjectKey, ResolverContext>,
    requiresStandardResolution: (ObjectEngineResult.ObjectKey) -> Boolean,
    objectProviderResult: ObjectEngineResult,
    queryProviderResult: ObjectEngineResult,
): ClosedOERConstructionDemandContext {
    val type = passiveSource?.schemaType ?: occurrence.target.type
    val constructionDemand = accumulatedDemand.merge(type)
    val closedDemand = constructionDemand.values.merge(type)
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
                constructionDemand = constructionDemand.descendants(objectKey),
            )
        }
    val referenceOccurrences =
        passiveSource?.discoverRootFieldReferences(world, occurrence, closedDemand, constructionDemand).orEmpty()
    check(fieldResolverOccurrences.keys.intersect(referenceOccurrences.keys).isEmpty()) {
        "Resolver26 classified one field as both an ordinary resolver and a root reference"
    }
    return ClosedOERConstructionDemandContext(
        constructionDemand = constructionDemand,
        fieldCheckerOccurrences = requiredCheckers.mapValues { (key, context) ->
            val selection = constructionDemand.checked.byKey().getValue(key)
            FieldCheckerOccurrence(
                selection,
                context.checker,
                context.fragments,
                context.fragments.objectFragment.pathVariableDefinitions.map { definition ->
                    VariableProviderReadOccurrence(objectProviderResult, definition, occurrence.fieldCheckerCycleTask(key), selection.inclusionCondition)
                } + context.fragments.queryFragment.pathVariableDefinitions.map { definition ->
                    VariableProviderReadOccurrence(queryProviderResult, definition, occurrence.fieldCheckerCycleTask(key), selection.inclusionCondition)
                },
            )
        },
        fieldResolverOccurrences = fieldResolverOccurrences,
        rootFieldReferenceOccurrences = referenceOccurrences,
        variableProviderReadsByResolverOccurrence =
            requiredResolvers
                .map { (objectKey, resolverContext) ->
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
                                reader = occurrence.fieldResolverCycleTask(objectKey),
                                inclusionCondition =
                                    closedDemand
                                        .byKey()
                                        .getValue(objectKey)
                                        .inclusionCondition,
                            )
                        } + resolverContext.fragments.queryFragment.pathVariableDefinitions.map { definition ->
                            VariableProviderReadOccurrence(
                                providerResult = queryProviderResult,
                                definition = definition,
                                reader = occurrence.fieldResolverCycleTask(objectKey),
                                inclusionCondition =
                                    closedDemand
                                        .byKey()
                                        .getValue(objectKey)
                                        .inclusionCondition,
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
        constructionDemand: Demand<SelectionForest>,
    ): FieldResolverOccurrence =
        FieldResolverOccurrence(
            selection = selection,
            invocationRoot = invocationRoot,
            invocationPath = invocationPath,
            resolverOccurrenceId = resolverOccurrenceId,
            resolver = resolver,
            variableDefinitions = variableDefinitions,
            fragments = fragments,
            publicationConstructionDemand = constructionDemand,
        )
}

private fun EngineObjectData.Sync.discoverRootFieldReferences(
    world: Assumptions,
    occurrence: OEROccurrence,
    demand: ObjectSelectionForest,
    constructionDemand: Demand<ObjectSelectionForest>,
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
                        publicationConstructionDemand = constructionDemand.descendants(objectKey),
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

/** Descendant provenance travels through the value publication, including lists and references. */
internal fun Demand<ObjectSelectionForest>.descendants(key: ObjectEngineResult.ObjectKey): Demand<SelectionForest> = Demand(checked.byKey()[key]?.subselections ?: selectionForestOf(), unchecked.byKey()[key]?.subselections ?: selectionForestOf())

/** One symbolic checker occurrence; its named pairs share the existing binding domain. */
internal class FieldCheckerOccurrence(
    val selection: ObjectSelection,
    val checker: FieldChecker,
    val fragments: ResolverFragments,
    val providerReads: List<VariableProviderReadOccurrence>,
) {
    val variableDefinitions = (fragments.objectFragment.variableDefinitions + fragments.queryFragment.variableDefinitions)
        .distinctBy { it.variable }
}

private class CheckerContext(
    val checker: FieldChecker,
    val fragments: ResolverFragments
) {
    val accumulatedKeyInclusions = linkedSetOf<InclusionCondition>()
}

private fun newCheckerInputDemand(
    world: Assumptions,
    occurrence: OEROccurrence,
    checked: SelectionForest,
    requiredCheckers: MutableMap<ObjectEngineResult.ObjectKey, CheckerContext>,
    expansionState: SymbolicExpansionState,
): ResolverInputConstructionDemand? {
    var objectFragment: SelectionForest = selectionForestOf()
    var queryFragment: SelectionForest = selectionForestOf()
    var expanded = false
    checked.merge(occurrence.target.type).byKey().forEach { (key, selection) ->
        if (key is ObjectEngineResult.ParentKey) return@forEach
        val checker = world.resolverRegistry.fieldChecker(key.field) ?: return@forEach
        if (selection.inclusionCondition === InclusionCondition.Never) return@forEach
        val context = requiredCheckers.getOrPut(key) {
            CheckerContext(checker, checker.instantiateFragmentsAt(occurrence.root, occurrence.coordinate(key))).also { context ->
                expansionState.register(occurrence.root, key, checker = true, context.fragments.objectFragment.variableDefinitions + context.fragments.queryFragment.variableDefinitions)
            }
        }
        if (key is ObjectEngineResult.GroundKey && key.arguments.argumentsContainErrorValue()) return@forEach
        selection.inclusionCondition
            .satisfiableAlternatives()
            .filter(context.accumulatedKeyInclusions::add)
            .forEach { inclusion ->
                expanded = true
                objectFragment += context.fragments.objectFragment.constructionSelections
                    .guardedBy(inclusion)
                queryFragment += context.fragments.queryFragment.constructionSelections
                    .guardedBy(inclusion)
            }
    }
    return ResolverInputConstructionDemand(objectFragment, queryFragment).takeIf { expanded }
}

/**
 * Detects unbounded symbolic expansion before it can monopolize synchronous closure. Revisiting
 * an existing key is finite; creating a new key through variables owned by the same root, field,
 * and executor kind again would instantiate the same fragment cycle indefinitely.
 */
private class SymbolicExpansionState {
    private data class Site(
        val root: ObjectEngineResult,
        val field: ViaductSchema.ObjectField,
        val checker: Boolean
    )

    private val ancestors = mutableMapOf<VariableInstanceId, Set<Site>>()

    fun register(
        root: ObjectEngineResult,
        key: ObjectEngineResult.ObjectKey,
        checker: Boolean,
        variables: List<VariableInstanceDefinition>
    ) {
        val site = Site(root, key.field, checker)
        val inherited = key.arguments.usedVariables().flatMapTo(linkedSetOf()) { variable ->
            ancestors[variable.instanceId].orEmpty()
        }
        require(site !in inherited) { "Unbounded symbolic checker/resolver demand at ${key.field.containingDef.name}/${key.field.name}" }
        val lineage = inherited + site
        variables.forEach { definition -> ancestors[requireNotNull(definition.variable.instanceId)] = lineage }
    }
}
