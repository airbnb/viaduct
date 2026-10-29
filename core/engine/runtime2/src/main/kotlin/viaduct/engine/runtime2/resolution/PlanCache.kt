package viaduct.engine.runtime2.resolution

import java.util.concurrent.ConcurrentHashMap
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ObjectSelectionForest
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.containsParentKeys
import viaduct.engine.runtime2.model.registry.Assumptions
import viaduct.engine.runtime2.model.registry.FieldCheckerResolver
import viaduct.engine.runtime2.model.registry.ResolverRegistry
import viaduct.engine.runtime2.model.registry.TypeCheckerResolver
import viaduct.engine.runtime2.model.registry.VariableDefinition
import viaduct.engine.runtime2.model.renameVariableInstances
import viaduct.engine.runtime2.resolution.framework.Demand
import viaduct.engine.runtime2.resolution.framework.ForestPlanEntry
import viaduct.engine.runtime2.resolution.framework.OEROccurrence
import viaduct.engine.runtime2.resolution.framework.OrchestrationConstructionDemand
import viaduct.engine.runtime2.resolution.framework.addForestPlanEntry
import viaduct.engine.runtime2.resolution.framework.findForestPlanEntry
import viaduct.engine.runtime2.resolution.framework.forestPlanMemoOwner
import viaduct.graphql.schema.ViaductSchema

/*
 * Resolution's plan cache memoizes pure demand analyses on the selection-forest instances they
 * consume. Selection equality is undefined, so every key starts from forest identity: an operation
 * plan cached per GraphQL document keeps its decoded forest alive, every analysis derived from it
 * returns shared forests, and the next request or list element reaches the same instances again.
 * Analyses attached to per-request forests die with those forests.
 *
 * Each memo records the remaining inputs that its computation reads. A closure template is reused
 * only for the same registry and closure algorithm, the same unchecked or checked partner forest,
 * equal type-check provenance, the same concrete type, the same presence of the type's registered
 * argument-free fields in the source, and the same type-checker applicability. Closure uses the
 * occurrence only to name what it instantiates: resolver, checker, and type-checker contexts, and
 * the variable instances those contexts own. A hit therefore recreates the contexts at the new
 * occurrence and renames the template's owned variable instances to that occurrence; selections
 * that mention no owned instance, including every variable-free subtree, are shared verbatim.
 *
 * Templates admit FromArgument variables, whose bindings follow from the owning key alone, in
 * keys and in `@skip`/`@include` guards. Provider-read, provider, and type-checker variables, and
 * demanded parent keys, keep closure per occurrence. Templates record owned instances against a
 * placeholder occurrence rather than the computing one, so they retain no request's result tree.
 */

// Closure templates

internal class ClosureTemplate(
    val registry: ResolverRegistry,
    val precomputed: Boolean,
    val partner: SelectionForest,
    val typeCheckDemanded: Boolean,
    val typeCheckCondition: InclusionCondition,
    val type: ViaductSchema.Object,
    val presence: Long,
    val typeCheckerApplicable: Boolean,
    val objectSide: ClosedSideTemplate,
    val querySide: ClosedSideTemplate,
    /** The placeholder that owns the template's variable instances, or null when it retains none. */
    val variableBase: TemplateVariableBase?,
) : ForestPlanEntry {
    fun matches(
        world: Assumptions,
        partner: SelectionForest,
        demand: Demand<SelectionForest>,
        type: ViaductSchema.Object,
        presence: Long,
        typeCheckerApplicable: Boolean,
    ): Boolean =
        registry === world.resolverRegistry &&
            precomputed == world.usePrecomputedConstructionDemand &&
            this.partner === partner &&
            typeCheckDemanded == demand.typeCheckDemanded &&
            this.type === type &&
            this.presence == presence &&
            this.typeCheckerApplicable == typeCheckerApplicable &&
            typeCheckCondition == demand.typeCheckCondition
}

/** One closed side: normalized forests and the keys whose contexts each occurrence recreates. */
internal class ClosedSideTemplate(
    val constructionDemand: Demand<ObjectSelectionForest>,
    val closedValueSelections: ObjectSelectionForest,
    val resolverKeys: List<ObjectEngineResult.ObjectKey>,
    val checkers: List<Pair<ObjectEngineResult.ObjectKey, FieldCheckerResolver>>,
    val typeChecker: TypeCheckerResolver?,
    val typeCheckerCondition: InclusionCondition,
) {
    /** Keys whose resolver or checker contexts may own variable instances. */
    val ownerKeys: List<ObjectEngineResult.ObjectKey>
        get() = resolverKeys + checkers.map { it.first }

    fun renamed(renaming: OwnedVariableRenaming): ClosedSideTemplate =
        ClosedSideTemplate(
            constructionDemand =
                Demand(
                    checked = constructionDemand.checked.renameVariableInstances(renaming::variable, renaming::key),
                    unchecked = constructionDemand.unchecked.renameVariableInstances(renaming::variable, renaming::key),
                    typeCheckDemanded = constructionDemand.typeCheckDemanded,
                    typeCheckCondition = renaming.condition(constructionDemand.typeCheckCondition),
                ),
            closedValueSelections = closedValueSelections.renameVariableInstances(renaming::variable, renaming::key),
            resolverKeys = resolverKeys.map(renaming::objectKey),
            checkers = checkers.map { (key, checker) -> renaming.objectKey(key) to checker },
            typeChecker = typeChecker,
            typeCheckerCondition = renaming.condition(typeCheckerCondition),
        )
}

/** Placeholder occurrences that own a template's variable instances between instantiations. */
internal class TemplateVariableBase(
    val objectOccurrence: OEROccurrence,
    val queryRoot: ObjectEngineResult,
)

/**
 * Maps the variable instances owned by one object occurrence's resolvers, field checkers, and type
 * checker, and by its associated Query root's resolvers, to the corresponding owners at another
 * occurrence. Instances owned elsewhere, such as an ancestor's, are unchanged. Owner keys may
 * themselves contain owned instances, so keys are renamed recursively and memoized.
 */
internal class OwnedVariableRenaming(
    from: OEROccurrence,
    fromQueryRoot: ObjectEngineResult,
    private val to: OEROccurrence,
    private val toQueryRoot: ObjectEngineResult,
    objectOwnerKeys: Collection<ObjectEngineResult.ObjectKey>,
    queryOwnerKeys: Collection<ObjectEngineResult.ObjectKey>,
) {
    private sealed interface Owner

    private class ObjectFieldOwner(val key: ObjectEngineResult.ObjectKey) : Owner

    private object ObjectTypeOwner : Owner

    private class QueryFieldOwner(val key: ObjectEngineResult.ObjectKey) : Owner

    private val owners: Map<ResolverOccurrenceId, Owner> =
        buildMap {
            objectOwnerKeys.forEach { key -> put(ResolverOccurrenceId.at(from.root, from.coordinate(key)), ObjectFieldOwner(key)) }
            put(ResolverOccurrenceId.at(from.root, from.path), ObjectTypeOwner)
            queryOwnerKeys.forEach { key -> put(ResolverOccurrenceId.at(fromQueryRoot, listOf(key)), QueryFieldOwner(key)) }
        }
    private val renamedOwners = HashMap<ResolverOccurrenceId, ResolverOccurrenceId>()
    private val renamedKeys = HashMap<ObjectEngineResult.Key, ObjectEngineResult.Key>()

    /** Whether any owned instance was encountered. */
    var renamedAny: Boolean = false
        private set

    fun variable(variable: Arguments.Variable): Arguments.Variable {
        val ownerId = variable.instanceId?.resolverOccurrenceId ?: return variable
        val owner = owners[ownerId] ?: return variable
        renamedAny = true
        val renamedOwner =
            renamedOwners[ownerId] ?: when (owner) {
                is ObjectFieldOwner -> ResolverOccurrenceId.at(to.root, to.coordinate(objectKey(owner.key)))
                ObjectTypeOwner -> ResolverOccurrenceId.at(to.root, to.path)
                is QueryFieldOwner -> ResolverOccurrenceId.at(toQueryRoot, listOf(objectKey(owner.key)))
            }.also { renamedOwners[ownerId] = it }
        return Arguments.Variable.of(variable.target, variable.variableName).instantiate(renamedOwner)
    }

    fun key(key: ObjectEngineResult.Key): ObjectEngineResult.Key = renamedKeys[key] ?: key.renameVariableInstances(::variable).also { renamedKeys[key] = it }

    fun objectKey(key: ObjectEngineResult.ObjectKey): ObjectEngineResult.ObjectKey = key(key) as ObjectEngineResult.ObjectKey

    fun condition(condition: InclusionCondition): InclusionCondition {
        val renamed = condition.mapVariables { variable -> if (variable.isTemplate) variable else variable(variable) }
        return if (renamed == condition) condition else renamed
    }
}

/**
 * Closes one orchestration's demand, reusing a template recorded for the same incoming forest.
 * [close] runs the configured closure algorithm on a miss or for an ineligible input.
 */
internal fun EngineObjectData.Sync.closeConstructionDemandWithPlanCache(
    world: Assumptions,
    objectOccurrence: OEROccurrence,
    queryOccurrence: OEROccurrence,
    initialDemand: OrchestrationConstructionDemand<SelectionForest>,
    close: () -> ClosedConstructionDemandContext,
): ClosedConstructionDemandContext {
    val demand = initialDemand.objectRooted
    if (initialDemand.queryRooted !== Demand.EMPTY) return close()
    val (owner, partner) = demand.forestPlanMemoOwner() ?: return close()
    val type = objectOccurrence.target.type
    val presence = presenceSignature(world.resolverRegistry, type) ?: return close()
    val typeCheckerApplicable =
        world.resolverRegistry.typeChecker(type)?.isApplicable(objectOccurrence.root, objectOccurrence.path) == true
    owner
        .findForestPlanEntry<ClosureTemplate> { it.matches(world, partner, demand, type, presence, typeCheckerApplicable) }
        ?.let { template -> return template.instantiate(world, this, objectOccurrence, queryOccurrence) }
    val closed = close()
    val objectSide = closed.objectRooted.toSideTemplate() ?: return closed
    val querySide = closed.queryRooted.toSideTemplate() ?: return closed
    val placeholder = ObjectEngineResult.of(type)
    val base = TemplateVariableBase(OEROccurrence(placeholder, emptyList(), placeholder), ObjectEngineResult.of(queryOccurrence.target.type))
    val toBase =
        OwnedVariableRenaming(
            from = objectOccurrence,
            fromQueryRoot = queryOccurrence.root,
            to = base.objectOccurrence,
            toQueryRoot = base.queryRoot,
            objectOwnerKeys = objectSide.ownerKeys,
            queryOwnerKeys = querySide.ownerKeys,
        )
    val baseObjectSide = objectSide.renamed(toBase)
    val baseQuerySide = querySide.renamed(toBase)
    val template =
        ClosureTemplate(
            registry = world.resolverRegistry,
            precomputed = world.usePrecomputedConstructionDemand,
            partner = partner,
            typeCheckDemanded = demand.typeCheckDemanded,
            typeCheckCondition = demand.typeCheckCondition,
            type = type,
            presence = presence,
            typeCheckerApplicable = typeCheckerApplicable,
            objectSide = if (toBase.renamedAny) baseObjectSide else objectSide,
            querySide = if (toBase.renamedAny) baseQuerySide else querySide,
            variableBase = base.takeIf { toBase.renamedAny },
        )
    owner.addForestPlanEntry(template)
    return closed
}

private fun ClosedOERConstructionDemandContext.toSideTemplate(): ClosedSideTemplate? {
    if (fieldResolverOccurrences.values.any { occurrence -> occurrence.variableDefinitions.any { it.definition !is VariableDefinition.FromArgument } }) return null
    if (fieldCheckerOccurrences.values.any { occurrence -> occurrence.variableDefinitions.any { it.definition !is VariableDefinition.FromArgument } }) return null
    val typeCheckerOccurrence = typeCheckerOccurrence
    if (typeCheckerOccurrence != null && typeCheckerOccurrence.variableDefinitions.isNotEmpty()) return null
    if (variableProviderReadsByResolverOccurrence.values.any { it.isNotEmpty() }) return null
    if (constructionDemand.values.containsParentKeys()) return null
    return ClosedSideTemplate(
        constructionDemand = constructionDemand,
        closedValueSelections = closedValueSelections,
        resolverKeys = fieldResolverOccurrences.keys.toList(),
        checkers = fieldCheckerOccurrences.map { (key, occurrence) -> key to occurrence.checker },
        typeChecker = typeCheckerOccurrence?.checker,
        typeCheckerCondition = typeCheckerOccurrence?.inclusionCondition ?: InclusionCondition.Never,
    )
}

private fun ClosureTemplate.instantiate(
    world: Assumptions,
    source: EngineObjectData.Sync,
    objectOccurrence: OEROccurrence,
    queryOccurrence: OEROccurrence,
): ClosedConstructionDemandContext {
    val renaming =
        variableBase?.let { base ->
            OwnedVariableRenaming(
                from = base.objectOccurrence,
                fromQueryRoot = base.queryRoot,
                to = objectOccurrence,
                toQueryRoot = queryOccurrence.root,
                objectOwnerKeys = objectSide.ownerKeys,
                queryOwnerKeys = querySide.ownerKeys,
            )
        }
    return ClosedConstructionDemandContext(
        objectRooted =
            (renaming?.let(objectSide::renamed) ?: objectSide).instantiate(
                world = world,
                passiveSource = source,
                occurrence = objectOccurrence,
                queryOccurrence = queryOccurrence,
                objectProviderResult = objectOccurrence.target,
            ),
        queryRooted =
            (renaming?.let(querySide::renamed) ?: querySide).instantiate(
                world = world,
                passiveSource = null,
                occurrence = queryOccurrence,
                queryOccurrence = queryOccurrence,
                objectProviderResult = queryOccurrence.target,
            ),
    )
}

private fun ClosedSideTemplate.instantiate(
    world: Assumptions,
    passiveSource: EngineObjectData.Sync?,
    occurrence: OEROccurrence,
    queryOccurrence: OEROccurrence,
    objectProviderResult: ObjectEngineResult,
): ClosedOERConstructionDemandContext {
    val resolverContexts = LinkedHashMap<ObjectEngineResult.ObjectKey, ResolverContext>(resolverKeys.size * 2)
    resolverKeys.forEach { key -> resolverContexts[key] = createResolverContext(world, occurrence, key) }
    val checkerContexts = LinkedHashMap<ObjectEngineResult.ObjectKey, CheckerContext>(checkers.size * 2)
    checkers.forEach { (key, checker) ->
        checkerContexts[key] = CheckerContext(checker, checker.instantiateFragmentsAt(occurrence.root, occurrence.coordinate(key)))
    }
    val typeCheckerOccurrence =
        typeChecker?.let { checker ->
            TypeCheckerContext(checker, checker.instantiateFragmentsAt(occurrence.root, occurrence.path))
                .also { context -> context.inclusionCondition = typeCheckerCondition }
                .toOccurrence(occurrence, queryOccurrence)
        }
    return closedOERConstructionDemandOf(
        world = world,
        passiveSource = passiveSource,
        occurrence = occurrence,
        constructionDemand = constructionDemand,
        closedValueSelections = closedValueSelections,
        checkerContexts = checkerContexts,
        typeCheckerOccurrence = typeCheckerOccurrence,
        resolverContexts = resolverContexts,
        objectProviderResult = objectProviderResult,
        queryProviderResult = queryOccurrence.target,
    )
}

// Source presence

/** The registered fields whose presence in a source can change closure for one concrete type. */
internal class PresenceSensitiveFields(
    val argumentFree: List<ViaductSchema.ObjectField>,
    val argumentBearing: List<ViaductSchema.ObjectField>,
)

/** Implemented only by prepared registries; decorating a registry must not inherit its cache. */
internal interface PresenceSensitiveFieldsOwner {
    val presenceSensitiveFieldsCache: PresenceSensitiveFieldsCache
}

/** Registry-lifetime memoization of each concrete type's presence-sensitive registered fields. */
internal class PresenceSensitiveFieldsCache {
    private val fields = ConcurrentHashMap<ViaductSchema.Object, PresenceSensitiveFields>()

    fun get(
        registry: ResolverRegistry,
        type: ViaductSchema.Object,
    ): PresenceSensitiveFields = fields.computeIfAbsent(type) { registry.computePresenceSensitiveFields(it) }
}

private fun ResolverRegistry.computePresenceSensitiveFields(type: ViaductSchema.Object): PresenceSensitiveFields {
    val registered = type.fields.filterIsInstance<ViaductSchema.ObjectField>().filter { it in this }
    return PresenceSensitiveFields(
        argumentFree = registered.filter { it.args.isEmpty() },
        argumentBearing = registered.filter { it.args.isNotEmpty() },
    )
}

/**
 * Encodes which registered argument-free fields of [type] this source supplies, or returns null
 * when the signature cannot be encoded or the source supplies a registered argument-bearing field.
 * The uncached closure then reports that invalid output exactly as before.
 */
private fun EngineObjectData.Sync.presenceSignature(
    registry: ResolverRegistry,
    type: ViaductSchema.Object,
): Long? {
    val fields =
        (registry as? PresenceSensitiveFieldsOwner)?.presenceSensitiveFieldsCache?.get(registry, type)
            ?: registry.computePresenceSensitiveFields(type)
    if (fields.argumentFree.size > Long.SIZE_BITS) return null
    if (fields.argumentBearing.any { isPresent(it.name) }) return null
    var signature = 0L
    fields.argumentFree.forEachIndexed { index, field ->
        if (isPresent(field.name)) signature = signature or (1L shl index)
    }
    return signature
}

// Successor demand

internal class SuccessorDemandEntry(
    val registry: ResolverRegistry,
    val partner: SelectionForest,
    val typeCheckDemanded: Boolean,
    val typeCheckCondition: InclusionCondition,
    val possibleRootTypes: Set<ViaductSchema.Object>,
    val successorDemand: SelectionForest,
) : ForestPlanEntry

/** [successorDemandFromConstructionDemand], memoized on the construction demand's forest instance. */
internal fun Demand<SelectionForest>.successorDemandWithPlanCache(
    world: Assumptions,
    possibleRootTypes: Set<ViaductSchema.Object>,
): SelectionForest {
    val (owner, partner) = forestPlanMemoOwner() ?: return successorDemandFromConstructionDemand(world, possibleRootTypes)
    owner
        .findForestPlanEntry<SuccessorDemandEntry> { entry ->
            entry.registry === world.resolverRegistry &&
                entry.partner === partner &&
                entry.typeCheckDemanded == typeCheckDemanded &&
                entry.typeCheckCondition == typeCheckCondition &&
                entry.possibleRootTypes == possibleRootTypes
        }?.let { return it.successorDemand }
    val successorDemand = successorDemandFromConstructionDemand(world, possibleRootTypes)
    val entry = SuccessorDemandEntry(world.resolverRegistry, partner, typeCheckDemanded, typeCheckCondition, possibleRootTypes, successorDemand)
    owner.addForestPlanEntry(entry)
    return successorDemand
}
