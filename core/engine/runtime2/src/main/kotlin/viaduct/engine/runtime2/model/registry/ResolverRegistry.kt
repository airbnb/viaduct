package viaduct.engine.runtime2.model.registry

import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ObjectSelectionForest
import viaduct.graphql.schema.ViaductSchema

/**
 * The externally supplied resolver slots and resolver-relative variable definitions fixed for one
 * reasoning world.
 *
 * A canonical object field is an actual resolver coordinate exactly when [contains] returns true.
 * Registration producers and field lookup callers supply definitions from the same canonical lowered schema.
 * The registry satisfies canonical schema ownership, special-field exclusions, query coverage,
 * resolver-local variable-template names, and acyclicity across object fields and
 * variable-template values. Acyclicity is intentionally checked over a conservative
 * coordinate-level possibility relation derived from fixed open fragment shapes. The relation
 * may therefore contain an edge whose exact occurrence is inactive because of an inclusion condition,
 * runtime type guard, or erroneous argument tuple. Even statically excluded selections contribute
 * dependencies, and the registry may reject a world whose exact active occurrences would be acyclic.
 *
 * Every variable is defined from one argument of its resolver field, from one nonempty canonical
 * [ObjectEngineResult.Key] path relative to that field's containing object, or by its resolver's
 * one-shot [FieldValueResolver.variablesProvider]. Object-field paths are structurally contained by the
 * defining field resolver's fixed [FieldValueResolver.objectFragment] envelope.
 * Variables referenced by a field resolver's object fragment, query fragment, or one of its
 * object-field paths belong to that same field. An object-field path must terminate at an
 * input-compatible value whose effective nullability and list shape can be coerced at every
 * argument position consuming the variable.
 *
 * ### Invariant: resolver-registry-depth-first-variable-stratification
 *
 * For every concrete object type, form one graph whose vertices are its canonical object fields,
 * interpreted as argument-insensitive structural branches. The graph contains each ordinary
 * resolver-input edge from a required sibling branch to its consuming resolver branch. For each
 * object-field variable, its production branches are the provider's root branch and every
 * transitive branch prerequisite of that root; every production branch has an edge to each branch
 * of the defining resolver's fixed object-fragment envelope whose subtree contains a use of that
 * variable. Argument-defined variables add no branch edge because their values are resolver
 * inputs. The least graph closed under the object-field variable edges is acyclic. Consequently,
 * one topological branch order binds every object-field variable used in a branch before resolution
 * enters that branch.
 */
interface ResolverRegistry {
    /** Creates the root Query input. Every Query field is supplied by a registered resolver. */
    fun createRootQueryInput(): EngineObjectData.Sync

    operator fun contains(field: ViaductSchema.ObjectField): Boolean

    /** Defined only when [field] is registered. */
    fun resolver(field: ViaductSchema.ObjectField): FieldValueResolver

    /** Returns the checker for [field], or null when no field checker applies. */
    fun fieldChecker(field: ViaductSchema.ObjectField): FieldCheckerResolver? = null

    /** Returns the checker for [type], or null when no type checker applies. */
    fun typeChecker(type: ViaductSchema.Object): TypeCheckerResolver? = null

    /** Precomputed construction inputs, or null when this registry does not supply templates. */
    fun precomputedConstructionDemand(field: ViaductSchema.ObjectField): ConstructionDemandTemplate? = null

    /** Registry-owned summaries for fixed checker inputs. */
    fun checkerParentDemand(checker: CheckerResolverBase<*>): CheckerParentConstructionDemand = error("This registry does not supply checker parent summaries")

    /** Requested-field transitions that can activate fixed parent requirements on this type. */
    fun parentSelectionDemand(type: ViaductSchema.Object): Map<String, ParentSelectionDemand> = emptyMap()

    /**
     * The registered fields that may be directly demanded by [field].
     *
     * This conservative coordinate relation is not specialized to one exact argument tuple or
     * runtime type assignment.
     */
    fun mayDemandFrom(field: ViaductSchema.ObjectField): Set<ViaductSchema.ObjectField>
}

/** Direct inputs and dependency links preserve source-sensitive activation boundaries. */
class ConstructionDemandTemplate internal constructor(
    val objectDemand: FragmentConstructionDemandTemplate,
    val queryDemand: FragmentConstructionDemandTemplate,
    val queryRootDemand: FragmentConstructionDemandTemplate?,
    val hasVariables: Boolean,
)

/**
 * [reusableDemand] can be shared directly across occurrences.
 * [templatedDemand] needs occurrence-specific processing, including variable instantiation and inclusion-condition handling.
 */
class FragmentConstructionDemandTemplate internal constructor(
    val reusableDemand: ObjectSelectionForest,
    internal val reusableParentDemand: ParentConstructionDemandTemplate,
    val reusableCheckerKeys: Set<ObjectEngineResult.GroundKey>,
    val templatedDemand: ObjectSelectionForest,
    internal val templatedParentDemand: ParentConstructionDemandTemplate,
    val dependencies: Map<ObjectEngineResult.GroundKey, ConstructionDemandTemplate>,
)

/** Checker inputs retain raw provenance; active values resume ordinary resolver templates. */
internal class CheckerConstructionDemandTemplate(
    val staticObjectDemand: ObjectSelectionForest,
    val dynamicObjectDemand: ObjectSelectionForest,
    val staticQueryDemand: ObjectSelectionForest,
    val dynamicQueryDemand: ObjectSelectionForest,
)

/** Parent summaries depend on the registry even when the checker instance is shared. */
class CheckerParentConstructionDemand internal constructor() {
    internal val objectParentDemand = ParentConstructionDemandTemplate()
    internal val queryParentDemand = ParentConstructionDemandTemplate()
    internal val staticObjectParentDemand = ParentConstructionDemandTemplate()
    internal val dynamicObjectParentDemand = ParentConstructionDemandTemplate()
    internal val staticQueryParentDemand = ParentConstructionDemandTemplate()
    internal val dynamicQueryParentDemand = ParentConstructionDemandTemplate()
}

/** Indicates that no field resolver is defined at a valid schema coordinate. */
class MissingResolverException(
    val typeName: String,
    val fieldName: String,
) : NoSuchElementException("Missing field resolver: $typeName/$fieldName")
