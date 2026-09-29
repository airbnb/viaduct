package model.registry

import model.ObjectEngineResult
import viaduct.engine.api.EngineObjectData
import viaduct.graphql.schema.ViaductSchema

/**
 * The externally supplied resolver slots and resolver-relative variable definitions fixed for one
 * reasoning world.
 *
 * A canonical object field is an actual resolver coordinate exactly when [contains] returns true.
 * The registry satisfies canonical schema ownership, special-field exclusions, query coverage,
 * resolver-local variable-template names, and acyclicity across object fields and
 * variable-template values. Acyclicity is intentionally checked over a conservative
 * coordinate-level possibility relation derived from fixed open fragment shapes. The relation
 * may therefore contain an edge whose exact occurrence is inactive because of a runtime type guard
 * or erroneous argument tuple, and the registry may reject a world whose exact active occurrences
 * would be acyclic.
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

    /**
     * The registered fields that may be directly demanded by [field].
     *
     * This conservative coordinate relation is not specialized to one exact argument tuple or
     * runtime type assignment.
     */
    fun mayDemandFrom(field: ViaductSchema.ObjectField): Set<ViaductSchema.ObjectField>
}

/** Indicates that no field resolver is defined at a valid schema coordinate. */
class MissingResolverException(
    val typeName: String,
    val fieldName: String,
) : NoSuchElementException("Missing field resolver: $typeName/$fieldName")
