package viaduct.engine.runtime2.resolution

import java.util.concurrent.ConcurrentHashMap
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ObjectSelectionForest
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.containsParentKeys
import viaduct.engine.runtime2.model.registry.Assumptions
import viaduct.engine.runtime2.model.registry.FieldCheckerResolver
import viaduct.engine.runtime2.model.registry.ResolverRegistry
import viaduct.engine.runtime2.model.registry.TypeCheckerResolver
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
 * argument-free fields in the source, and the same type-checker applicability. Templates are
 * created only when no resolver, field checker, or type checker expanded on either side owns
 * variables and no parent key is demanded. The retained forests then mention no identity of the
 * occurrence that computed them, so a hit rebuilds only the occurrence-specific resolver, checker,
 * and type-checker contexts and then the root-field-reference scan.
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

/** One closed side without occurrence identities: normalized forests and the keys that need contexts. */
internal class ClosedSideTemplate(
    val constructionDemand: Demand<ObjectSelectionForest>,
    val closedValueSelections: ObjectSelectionForest,
    val resolverKeys: List<ObjectEngineResult.ObjectKey>,
    val checkers: List<Pair<ObjectEngineResult.ObjectKey, FieldCheckerResolver>>,
    val typeChecker: TypeCheckerResolver?,
    val typeCheckerCondition: InclusionCondition,
)

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
            objectSide = objectSide,
            querySide = querySide,
        )
    owner.addForestPlanEntry(template)
    return closed
}

private fun ClosedOERConstructionDemandContext.toSideTemplate(): ClosedSideTemplate? {
    if (fieldResolverOccurrences.values.any { it.variableDefinitions.isNotEmpty() }) return null
    if (fieldCheckerOccurrences.values.any { it.variableDefinitions.isNotEmpty() }) return null
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
): ClosedConstructionDemandContext =
    ClosedConstructionDemandContext(
        objectRooted =
            objectSide.instantiate(
                world = world,
                passiveSource = source,
                occurrence = objectOccurrence,
                queryOccurrence = queryOccurrence,
                objectProviderResult = objectOccurrence.target,
            ),
        queryRooted =
            querySide.instantiate(
                world = world,
                passiveSource = null,
                occurrence = queryOccurrence,
                queryOccurrence = queryOccurrence,
                objectProviderResult = queryOccurrence.target,
            ),
    )

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
