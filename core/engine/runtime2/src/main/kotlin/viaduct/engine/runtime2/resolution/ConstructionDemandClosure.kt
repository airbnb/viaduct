package viaduct.engine.runtime2.resolution

import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ObjectSelection
import viaduct.engine.runtime2.model.ObjectSelectionForest
import viaduct.engine.runtime2.model.PathComponent
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.RootFieldReferenceData
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.VariableInstanceId
import viaduct.engine.runtime2.model.concatenateSelectionForests
import viaduct.engine.runtime2.model.guardedBy
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.objectKey
import viaduct.engine.runtime2.model.outputValue
import viaduct.engine.runtime2.model.registry.Assumptions
import viaduct.engine.runtime2.model.registry.FieldCheckerResolver
import viaduct.engine.runtime2.model.registry.FieldValueResolver
import viaduct.engine.runtime2.model.registry.InstantiatedFieldPathDefinition
import viaduct.engine.runtime2.model.registry.ResolverFragments
import viaduct.engine.runtime2.model.registry.TypeCheckerResolver
import viaduct.engine.runtime2.model.registry.VariableInstanceDefinition
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.schemaType
import viaduct.engine.runtime2.model.selectionForestOf
import viaduct.engine.runtime2.model.usedVariables
import viaduct.engine.runtime2.resolution.framework.CycleTask
import viaduct.engine.runtime2.resolution.framework.Demand
import viaduct.engine.runtime2.resolution.framework.OEROccurrence
import viaduct.engine.runtime2.resolution.framework.OrchestrationConstructionDemand
import viaduct.engine.runtime2.resolution.framework.ResolverInputConstructionDemand
import viaduct.engine.runtime2.resolution.framework.argumentsContainErrorValue
import viaduct.engine.runtime2.resolution.framework.descendants
import viaduct.engine.runtime2.resolution.framework.fieldCheckerCycleTask
import viaduct.engine.runtime2.resolution.framework.fieldResolverCycleTask
import viaduct.engine.runtime2.resolution.framework.merge
import viaduct.engine.runtime2.resolution.framework.plus
import viaduct.engine.runtime2.resolution.framework.requiresStandardResolution
import viaduct.engine.runtime2.resolution.framework.typeCheckerCycleTask
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
    val constructionDemand: Demand<ObjectSelectionForest>,
    val fieldCheckerOccurrences: Map<ObjectEngineResult.ObjectKey, FieldCheckerOccurrence>,
    val typeCheckerOccurrence: TypeCheckerOccurrence? = null,
    val fieldResolverOccurrences: Map<ObjectEngineResult.ObjectKey, FieldResolverOccurrence>,
    val rootFieldReferenceOccurrences: Map<ObjectEngineResult.ObjectKey, RootFieldReferenceOccurrence>,
    val variableProviderReadsByResolverOccurrence: Map<ResolverOccurrenceId, List<VariableProviderReadOccurrence>>,
) {
    val closedValueSelections: ObjectSelectionForest = constructionDemand.values.merge(constructionDemand.checked.type)
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
internal fun EngineObjectData.Sync.closeOrchestrationConstructionDemand(
    world: Assumptions,
    objectOccurrence: OEROccurrence,
    queryOccurrence: OEROccurrence,
    initialDemand: OrchestrationConstructionDemand<SelectionForest>,
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

    // The same four forests as grounded closure, retaining symbolic argument identities.
    var pendingDemand = initialDemand.withLiftedParentDemand(world)
    val demandContributions = mutableListOf(pendingDemand)

    // Query fragments demand fields within the associated root. Schema validation prohibits
    // parent backedges to Query, so this root cannot acquire incoming type-check demand.
    val objectTypeChecker = objectOccurrence.typeCheckerContext(world)

    // Unlike grounded expanded-key sets, these also retain excluded/error reservations for
    // finalization. Only newly discovered satisfiable inclusions contribute fragment demand.
    val objectResolverContexts = linkedMapOf<ObjectEngineResult.ObjectKey, ResolverContext>()
    val queryResolverContexts = linkedMapOf<ObjectEngineResult.ObjectKey, ResolverContext>()
    val objectCheckerContexts = linkedMapOf<ObjectEngineResult.ObjectKey, CheckerContext>()
    val queryCheckerContexts = linkedMapOf<ObjectEngineResult.ObjectKey, CheckerContext>()
    val expansionState = SymbolicExpansionState()

    while (true) {
        val newObjectResolverKeyInclusions = pendingDemand.objectRooted.newResolverKeyInclusions(
            world = world,
            occurrence = objectOccurrence,
            resolverContexts = objectResolverContexts,
            expansionState = expansionState,
            requiresStandardResolution = ::requiresStandardResolution,
        )
        val newQueryResolverKeyInclusions = pendingDemand.queryRooted.newResolverKeyInclusions(
            world = world,
            occurrence = queryOccurrence,
            resolverContexts = queryResolverContexts,
            expansionState = expansionState,
            requiresStandardResolution = { true },
        )
        val newObjectCheckerKeyInclusions = pendingDemand.objectRooted.newCheckerKeyInclusions(
            world,
            objectOccurrence,
            objectCheckerContexts,
            expansionState,
        )
        val newQueryCheckerKeyInclusions = pendingDemand.queryRooted.newCheckerKeyInclusions(
            world,
            queryOccurrence,
            queryCheckerContexts,
            expansionState,
        )
        val newObjectTypeInclusion = objectTypeChecker.addInclusion(pendingDemand.objectRooted.typeCheckCondition)
        if (
            newObjectResolverKeyInclusions.isEmpty() &&
            newQueryResolverKeyInclusions.isEmpty() &&
            newObjectCheckerKeyInclusions.isEmpty() &&
            newQueryCheckerKeyInclusions.isEmpty() &&
            newObjectTypeInclusion == null
        ) {
            break
        }
        val queryType = queryOccurrence.target.type
        val objectResolverInputs = newObjectResolverKeyInclusions.resolverInputDemand(objectResolverContexts, objectOccurrence.target.type, queryType)
        val queryResolverInputs = newQueryResolverKeyInclusions.resolverInputDemand(queryResolverContexts, queryType, queryType)
        val queryInputSelections =
            objectResolverInputs.queryFragment +
                queryResolverInputs.objectFragment +
                queryResolverInputs.queryFragment
        val objectCheckerInputs = newObjectCheckerKeyInclusions.checkerInputDemand(objectCheckerContexts, objectOccurrence.target.type, queryType)
        val queryCheckerInputs = newQueryCheckerKeyInclusions.checkerInputDemand(queryCheckerContexts, queryType, queryType)
        val objectTypeInputs = objectTypeChecker.inputDemand(newObjectTypeInclusion, objectOccurrence.target.type, queryType)
        val objectInputs = Demand(
            checked = objectResolverInputs.objectFragment,
            unchecked = objectCheckerInputs.objectFragment + objectTypeInputs.objectFragment,
            typeCheckDemanded = false,
        )
        val queryInputs = Demand(
            checked = queryInputSelections,
            unchecked = objectCheckerInputs.queryFragment +
                queryCheckerInputs.objectFragment + queryCheckerInputs.queryFragment +
                objectTypeInputs.queryFragment,
            typeCheckDemanded = false,
        )
        pendingDemand = OrchestrationConstructionDemand(
            objectRooted = objectInputs,
            queryRooted = queryInputs,
        ).withLiftedParentDemand(world)
        demandContributions += pendingDemand
    }
    val accumulatedDemand = demandContributions.concatenateDemand()

    return ClosedConstructionDemandContext(
        objectRooted =
            finalizeClosedOERConstructionDemand(
                world = world,
                passiveSource = this,
                occurrence = objectOccurrence,
                accumulatedDemand = accumulatedDemand.objectRooted,
                resolverContexts = objectResolverContexts,
                checkerContexts = objectCheckerContexts,
                typeCheckerOccurrence = objectTypeChecker?.toOccurrence(objectOccurrence, queryOccurrence),
                requiresStandardResolution = ::requiresStandardResolution,
                objectProviderResult = objectOccurrence.target,
                queryProviderResult = queryOccurrence.target,
            ),
        queryRooted =
            finalizeClosedOERConstructionDemand(
                world = world,
                passiveSource = null,
                occurrence = queryOccurrence,
                accumulatedDemand = accumulatedDemand.queryRooted,
                resolverContexts = queryResolverContexts,
                checkerContexts = queryCheckerContexts,
                typeCheckerOccurrence = null,
                requiresStandardResolution = { true },
                objectProviderResult = queryOccurrence.target,
                queryProviderResult = queryOccurrence.target,
            ),
    )
}

/** Concatenates closure frontiers once instead of repeatedly copying the accumulated prefix. */
private fun List<OrchestrationConstructionDemand<SelectionForest>>.concatenateDemand(): OrchestrationConstructionDemand<SelectionForest> =
    OrchestrationConstructionDemand(
        objectRooted = map { it.objectRooted }.concatenateDemand(),
        queryRooted = map { it.queryRooted }.concatenateDemand(),
    )

private fun List<Demand<SelectionForest>>.concatenateDemand(): Demand<SelectionForest> =
    Demand(
        checked = map { it.checked }.concatenateSelectionForests(),
        unchecked = map { it.unchecked }.concatenateSelectionForests(),
        typeCheckDemanded = any { it.typeCheckDemanded },
        typeCheckCondition = InclusionCondition.anyOf(map { it.typeCheckCondition }),
    )

/** Corresponds to grounded groundWithLiftedParentDemand, applied only to new symbolic contributions. */
private fun OrchestrationConstructionDemand<SelectionForest>.withLiftedParentDemand(world: Assumptions): OrchestrationConstructionDemand<SelectionForest> =
    OrchestrationConstructionDemand(
        objectRooted = objectRooted + objectRooted.liftParentConstructionDemand(world),
        queryRooted = queryRooted + queryRooted.liftParentConstructionDemand(world),
    )

/** Discover expansion events separately from collecting their fixed fragment contributions. */
private fun Demand<SelectionForest>.newResolverKeyInclusions(
    world: Assumptions,
    occurrence: OEROccurrence,
    resolverContexts: MutableMap<ObjectEngineResult.ObjectKey, ResolverContext>,
    expansionState: SymbolicExpansionState,
    requiresStandardResolution: (ObjectEngineResult.ObjectKey) -> Boolean,
): List<Pair<ObjectEngineResult.ObjectKey, InclusionCondition>> =
    values.applicableKeyConditions(occurrence.target.type).mapNotNull { (key, inclusion) ->
        if (key.field !in world.resolverRegistry || !requiresStandardResolution(key)) {
            return@mapNotNull null
        }
        // Retain one description even when this occurrence is excluded or has argument errors.
        val resolverContext = resolverContexts.getOrPut(key) { createResolverContext(world, occurrence, key) }
        if (key is ObjectEngineResult.GroundKey && key.arguments.argumentsContainErrorValue()) {
            return@mapNotNull null
        }
        if (inclusion === InclusionCondition.Never || !resolverContext.accumulatedKeyInclusions.add(inclusion)) {
            return@mapNotNull null
        }
        if (resolverContext.accumulatedKeyInclusions.size == 1) {
            // A reserved but statically excluded occurrence cannot extend a fragment cycle.
            expansionState.register(occurrence.root, key, checker = false, resolverContext.variableDefinitions)
        }
        key to inclusion
    }

private fun Demand<SelectionForest>.newCheckerKeyInclusions(
    world: Assumptions,
    occurrence: OEROccurrence,
    checkerContexts: MutableMap<ObjectEngineResult.ObjectKey, CheckerContext>,
    expansionState: SymbolicExpansionState,
): List<Pair<ObjectEngineResult.ObjectKey, InclusionCondition>> =
    checked.applicableKeyConditions(occurrence.target.type).mapNotNull { (key, inclusion) ->
        if (key is ObjectEngineResult.ParentKey) return@mapNotNull null
        val checker = world.resolverRegistry.fieldChecker(key.field) ?: return@mapNotNull null
        if (inclusion === InclusionCondition.Never) return@mapNotNull null
        val checkerContext = checkerContexts.getOrPut(key) {
            CheckerContext(checker, checker.instantiateFragmentsAt(occurrence.root, occurrence.coordinate(key))).also { context ->
                expansionState.register(
                    occurrence.root,
                    key,
                    checker = true,
                    context.fragments.objectFragment.variableDefinitions + context.fragments.queryFragment.variableDefinitions,
                )
            }
        }
        if (key is ObjectEngineResult.GroundKey && key.arguments.argumentsContainErrorValue()) return@mapNotNull null
        (key to inclusion).takeIf { checkerContext.accumulatedKeyInclusions.add(inclusion) }
    }

/** Preserves the additive occurrence boundary that merge would collapse into a Boolean disjunction. */
private fun SelectionForest.applicableKeyConditions(type: ViaductSchema.Object): List<Pair<ObjectEngineResult.ObjectKey, InclusionCondition>> =
    buildList {
        this@applicableKeyConditions.forEach { selection ->
            if (type in selection.possibleTypes) {
                add(selection.objectKey(type) to selection.inclusionCondition)
            }
        }
    }

private fun List<Pair<ObjectEngineResult.ObjectKey, InclusionCondition>>.resolverInputDemand(
    resolverContexts: Map<ObjectEngineResult.ObjectKey, ResolverContext>,
    objectType: ViaductSchema.Object,
    queryType: ViaductSchema.Object,
): ResolverInputConstructionDemand {
    var objectFragment: SelectionForest = selectionForestOf()
    var queryFragment: SelectionForest = selectionForestOf()
    forEach { (key, inclusion) ->
        val fragments = resolverContexts.getValue(key).fragments
        objectFragment += fragments.objectFragment.constructionSelections.merge(objectType).guardedBy(inclusion)
        queryFragment += fragments.queryFragment.constructionSelections.merge(queryType).guardedBy(inclusion)
    }
    return ResolverInputConstructionDemand(objectFragment, queryFragment)
}

private fun List<Pair<ObjectEngineResult.ObjectKey, InclusionCondition>>.checkerInputDemand(
    checkerContexts: Map<ObjectEngineResult.ObjectKey, CheckerContext>,
    objectType: ViaductSchema.Object,
    queryType: ViaductSchema.Object,
): ResolverInputConstructionDemand {
    var objectFragment: SelectionForest = selectionForestOf()
    var queryFragment: SelectionForest = selectionForestOf()
    forEach { (key, inclusion) ->
        val fragments = checkerContexts.getValue(key).fragments
        objectFragment += fragments.objectFragment.constructionSelections.merge(objectType).guardedBy(inclusion)
        queryFragment += fragments.queryFragment.constructionSelections.merge(queryType).guardedBy(inclusion)
    }
    return ResolverInputConstructionDemand(objectFragment, queryFragment)
}

/**
 * Finalizes demand for task preparation. Symbolic closure retains the occurrences and provider
 * descriptions it discovered; this step neither expands demand nor installs tasks or promises.
 */
private fun finalizeClosedOERConstructionDemand(
    world: Assumptions,
    passiveSource: EngineObjectData.Sync?,
    occurrence: OEROccurrence,
    accumulatedDemand: Demand<SelectionForest>,
    checkerContexts: Map<ObjectEngineResult.ObjectKey, CheckerContext>,
    typeCheckerOccurrence: TypeCheckerOccurrence?,
    resolverContexts: Map<ObjectEngineResult.ObjectKey, ResolverContext>,
    requiresStandardResolution: (ObjectEngineResult.ObjectKey) -> Boolean,
    objectProviderResult: ObjectEngineResult,
    queryProviderResult: ObjectEngineResult,
): ClosedOERConstructionDemandContext {
    val type = passiveSource?.schemaType ?: occurrence.target.type
    val constructionDemand = accumulatedDemand.merge(type)
    val closedValueSelections = constructionDemand.values.merge(type)
    val requiredResolverSelections =
        closedValueSelections
            .byKey()
            .filter { (objectKey, _) ->
                objectKey.field in world.resolverRegistry && requiresStandardResolution(objectKey)
            }
    check(requiredResolverSelections.keys == resolverContexts.keys) {
        "Resolution closed demand and required resolvers are misaligned"
    }
    val fieldResolverOccurrences =
        resolverContexts.mapValues { (objectKey, resolverContext) ->
            resolverContext.toFieldResolverOccurrence(
                selection = closedValueSelections.byKey().getValue(objectKey),
                constructionDemand = constructionDemand.descendants(objectKey),
            )
        }
    val referenceOccurrences =
        passiveSource?.discoverRootFieldReferences(world, occurrence, closedValueSelections, constructionDemand).orEmpty()
    check(fieldResolverOccurrences.keys.intersect(referenceOccurrences.keys).isEmpty()) {
        "Resolution classified one field as both an ordinary resolver and a root reference"
    }
    return ClosedOERConstructionDemandContext(
        constructionDemand = constructionDemand,
        typeCheckerOccurrence = typeCheckerOccurrence,
        fieldCheckerOccurrences = checkerContexts.mapValues { (key, context) ->
            context.toFieldCheckerOccurrence(
                selection = constructionDemand.checked.byKey().getValue(key),
                objectProviderResult = objectProviderResult,
                queryProviderResult = queryProviderResult,
                reader = occurrence.fieldCheckerCycleTask(key),
            )
        },
        fieldResolverOccurrences = fieldResolverOccurrences,
        rootFieldReferenceOccurrences = referenceOccurrences,
        variableProviderReadsByResolverOccurrence = fieldResolverOccurrences.values.associate { fieldResolverOccurrence ->
            val selection = fieldResolverOccurrence.selection
            val key = selection.key
            val providerReads =
                if (key is ObjectEngineResult.GroundKey && key.arguments.argumentsContainErrorValue()) {
                    emptyList()
                } else {
                    fieldResolverOccurrence.fragments.variableProviderReads(
                        objectProviderResult,
                        queryProviderResult,
                        occurrence.fieldResolverCycleTask(key),
                        selection.inclusionCondition,
                    )
                }
            fieldResolverOccurrence.resolverOccurrenceId to providerReads
        },
    )
}

/** Instructions for future provider reads; no binding is declared or read during construction. */
private fun ResolverFragments.variableProviderReads(
    objectProviderResult: ObjectEngineResult,
    queryProviderResult: ObjectEngineResult,
    reader: CycleTask,
    inclusionCondition: InclusionCondition,
): List<VariableProviderReadOccurrence> =
    objectFragment.pathVariableDefinitions.map { definition ->
        VariableProviderReadOccurrence(objectProviderResult, definition, reader, inclusionCondition)
    } + queryFragment.pathVariableDefinitions.map { definition ->
        VariableProviderReadOccurrence(queryProviderResult, definition, reader, inclusionCondition)
    }

// Closure inputs and bookkeeping for one required standard resolver occurrence.
private data class ResolverContext(
    val invocationRoot: ObjectEngineResult,
    val invocationPath: List<PathComponent>,
    val resolverOccurrenceId: ResolverOccurrenceId,
    val resolver: FieldValueResolver,
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
            require(consumerArguments is viaduct.engine.runtime2.model.Arguments.Resolved && consumerArguments.fieldValues.isEmpty()) {
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
                "Resolution discovered a root-field reference twice: $objectKey"
            }
        }
    }

private fun createResolverContext(
    world: Assumptions,
    occurrence: OEROccurrence,
    objectKey: ObjectEngineResult.ObjectKey,
): ResolverContext {
    val resolver: FieldValueResolver = world.resolverRegistry.resolver(objectKey.field)
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

/** One symbolic checker occurrence; its named pairs share the existing binding domain. */
internal class FieldCheckerOccurrence(
    val selection: ObjectSelection,
    val checker: FieldCheckerResolver,
    val fragments: ResolverFragments,
    val providerReads: List<VariableProviderReadOccurrence>,
) {
    val variableDefinitions = (fragments.objectFragment.variableDefinitions + fragments.queryFragment.variableDefinitions)
        .distinctBy { it.variable }
}

private class CheckerContext(
    val checker: FieldCheckerResolver,
    val fragments: ResolverFragments
) {
    val accumulatedKeyInclusions = linkedSetOf<InclusionCondition>()

    fun toFieldCheckerOccurrence(
        selection: ObjectSelection,
        objectProviderResult: ObjectEngineResult,
        queryProviderResult: ObjectEngineResult,
        reader: CycleTask,
    ): FieldCheckerOccurrence =
        FieldCheckerOccurrence(
            selection,
            checker,
            fragments,
            fragments.variableProviderReads(objectProviderResult, queryProviderResult, reader, selection.inclusionCondition),
        )
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

/** An OER-owned checker context has no field key or field-argument activation dependency. */
internal class TypeCheckerOccurrence(
    val checker: TypeCheckerResolver,
    val fragments: ResolverFragments,
    val inclusionCondition: InclusionCondition,
    val providerReads: List<VariableProviderReadOccurrence>,
) {
    val variableDefinitions = (fragments.objectFragment.variableDefinitions + fragments.queryFragment.variableDefinitions)
        .distinctBy { it.variable }
}

private class TypeCheckerContext(
    val checker: TypeCheckerResolver,
    val fragments: ResolverFragments,
) {
    var inclusionCondition: InclusionCondition = InclusionCondition.Never

    fun toOccurrence(
        occurrence: OEROccurrence,
        queryOccurrence: OEROccurrence
    ): TypeCheckerOccurrence? {
        if (inclusionCondition === InclusionCondition.Never) return null
        return TypeCheckerOccurrence(
            checker,
            fragments,
            inclusionCondition,
            fragments.variableProviderReads(occurrence.target, queryOccurrence.target, occurrence.typeCheckerCycleTask(), inclusionCondition),
        )
    }
}

private fun OEROccurrence.typeCheckerContext(world: Assumptions): TypeCheckerContext? =
    world.resolverRegistry.typeChecker(target.type)?.let { checker ->
        TypeCheckerContext(checker, checker.instantiateFragmentsAt(root, path))
    }

private fun TypeCheckerContext?.addInclusion(condition: InclusionCondition): InclusionCondition? {
    if (this == null || condition === InclusionCondition.Never) return null
    val combined = inclusionCondition.or(condition)
    if (combined == inclusionCondition) return null
    inclusionCondition = combined
    return condition
}

private fun TypeCheckerContext?.inputDemand(
    inclusion: InclusionCondition?,
    objectType: ViaductSchema.Object,
    queryType: ViaductSchema.Object,
): ResolverInputConstructionDemand {
    return ResolverInputConstructionDemand(
        if (this == null || inclusion == null) selectionForestOf() else fragments.objectFragment.constructionSelections.merge(objectType).guardedBy(inclusion),
        if (this == null || inclusion == null) selectionForestOf() else fragments.queryFragment.constructionSelections.merge(queryType).guardedBy(inclusion),
    )
}
