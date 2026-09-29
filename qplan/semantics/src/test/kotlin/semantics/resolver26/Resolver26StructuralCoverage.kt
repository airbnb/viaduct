@file:Suppress("MatchingDeclarationName")

package semantics.resolver26

import model.Arguments
import model.ListEngineResult
import model.ObjectEngineResult
import model.PathComponent
import semantics.arbitrary.ArbitraryRegistry
import semantics.arbitrary.FieldCoordinate
import semantics.arbitrary.ResolutionWitness
import semantics.arbitrary.ResolverApplicationRecord
import semantics.contract.RegisteredResolverOccurrence

// Names result-visible structures that distinguish Resolver26's symbolic execution cases.
internal enum class Resolver26StructuralSignature {
    SYMBOLIC_RESOLVER_INSTANCE,
    LIST_SYMBOLIC_RESOLVER_INSTANCE,
    EQUAL_SYMBOLIC_ARGUMENTS,
    OBJECT_PATH_VARIABLE_OWNER,
    PROVIDER_VARIABLE_OWNER,
    NESTED_VARIABLE_USE,
    PASSIVE_DESCENDANT_VARIABLE_USE,
    MIXED_BINDING_SOURCES,
    NESTED_PROVIDER_PATH,
    ABSTRACT_PROVIDER_PATH,
    NULL_PROVIDER_INTERMEDIATE,
    ERROR_PROVIDER_INTERMEDIATE,
    MULTIPLE_OBJECT_PATH_OWNERS,
    OBJECT_PATH_OWNER_DEPENDENCY,
    GREAT_GRANDPARENT_PARENT_DEMAND,
}

// Classifies one completed case without consulting Resolver26's scheduler or private runtime state.
internal fun resolver26StructuralSignatures(
    occurrences: List<RegisteredResolverOccurrence>,
    witness: ResolutionWitness,
    registry: ArbitraryRegistry,
): Set<Resolver26StructuralSignature> {
    val signatures: MutableSet<Resolver26StructuralSignature> = linkedSetOf()
    val symbolicOccurrences: List<RegisteredResolverOccurrence> =
        occurrences.filter { occurrence ->
            occurrence.objectKey() !is ObjectEngineResult.GroundKey
        }
    val activeSourceFields: Set<FieldCoordinate> =
        witness.applications
            .mapTo(linkedSetOf()) { application ->
                registry.sourceResolverCoordinate(application.key.field)
            }
    val activeObjectPathOwners: Set<FieldCoordinate> =
        activeSourceFields.intersect(registry.fromObjectFieldVariableOwnerFields)

    if (symbolicOccurrences.isNotEmpty()) {
        signatures += Resolver26StructuralSignature.SYMBOLIC_RESOLVER_INSTANCE
    }
    if (
        symbolicOccurrences.any { occurrence ->
            occurrence.occurrencePath.any { component ->
                component is ListEngineResult.Index
            }
        }
    ) {
        signatures += Resolver26StructuralSignature.LIST_SYMBOLIC_RESOLVER_INSTANCE
    }
    if (
        symbolicOccurrences
            .groupBy { occurrence ->
                VisibleResolverOccurrence(
                    containingObjectPath = occurrence.occurrencePath.dropLast(1),
                    field = occurrence.applicationKey.field,
                    arguments = occurrence.applicationKey.arguments,
                )
            }.values
            .any { equalVisibleOccurrences ->
                equalVisibleOccurrences.size > 1
            }
    ) {
        signatures += Resolver26StructuralSignature.EQUAL_SYMBOLIC_ARGUMENTS
    }
    if (activeObjectPathOwners.isNotEmpty()) {
        signatures += Resolver26StructuralSignature.OBJECT_PATH_VARIABLE_OWNER
    }
    if (activeSourceFields.any { sourceField -> sourceField in registry.fromProviderVariableOwnerFields }) {
        signatures += Resolver26StructuralSignature.PROVIDER_VARIABLE_OWNER
    }
    if (
        activeSourceFields.any { sourceField ->
            sourceField in registry.nestedFromObjectFieldVariableUseOwnerFields
        }
    ) {
        signatures += Resolver26StructuralSignature.NESTED_VARIABLE_USE
    }
    if (
        activeSourceFields.any { sourceField ->
            sourceField in registry.passiveTopLevelFromObjectFieldVariableUseOwnerFields
        }
    ) {
        signatures += Resolver26StructuralSignature.PASSIVE_DESCENDANT_VARIABLE_USE
    }
    if (
        witness.applications.any(registry::applicationUsesFromArgumentVariable) &&
        witness.applications.any(registry::applicationUsesFromObjectFieldVariable)
    ) {
        signatures += Resolver26StructuralSignature.MIXED_BINDING_SOURCES
    }
    if (
        activeSourceFields.any { sourceField ->
            sourceField in registry.nestedFromObjectFieldVariableOwnerFields
        }
    ) {
        signatures += Resolver26StructuralSignature.NESTED_PROVIDER_PATH
    }
    if (
        activeSourceFields.any { sourceField ->
            sourceField in registry.abstractFromObjectFieldVariableOwnerFields
        }
    ) {
        signatures += Resolver26StructuralSignature.ABSTRACT_PROVIDER_PATH
    }
    if (
        activeSourceFields.any { sourceField ->
            sourceField in registry.nullIntermediateFromObjectFieldVariableOwnerFields
        }
    ) {
        signatures += Resolver26StructuralSignature.NULL_PROVIDER_INTERMEDIATE
    }
    if (
        activeSourceFields.any { sourceField ->
            sourceField in registry.errorIntermediateFromObjectFieldVariableOwnerFields
        }
    ) {
        signatures += Resolver26StructuralSignature.ERROR_PROVIDER_INTERMEDIATE
    }
    if (activeObjectPathOwners.size > 1) {
        signatures += Resolver26StructuralSignature.MULTIPLE_OBJECT_PATH_OWNERS
    }
    if (
        registry.fromObjectFieldVariableOwnerDependencies.any { (reader, author) ->
            reader in activeSourceFields && author in activeSourceFields
        }
    ) {
        signatures += Resolver26StructuralSignature.OBJECT_PATH_OWNER_DEPENDENCY
    }
    if (registry.features.maximumParentSelectionDepth >= 3) {
        signatures += Resolver26StructuralSignature.GREAT_GRANDPARENT_PARENT_DEMAND
    }
    return signatures
}

// Describes the visible identity shared by symbolic occurrences before variable-instance comparison.
private data class VisibleResolverOccurrence(
    val containingObjectPath: List<PathComponent>,
    val field: FieldCoordinate,
    val arguments: Arguments.Resolved,
)

// Returns the exact stored key at this registered resolver occurrence.
private fun RegisteredResolverOccurrence.objectKey(): ObjectEngineResult.ObjectKey = occurrencePath.last() as ObjectEngineResult.ObjectKey

// Reports whether this recorded application belongs to a FromArgument variable owner.
private fun ArbitraryRegistry.applicationUsesFromArgumentVariable(application: ResolverApplicationRecord): Boolean = sourceResolverCoordinate(application.key.field) in fromArgumentVariableOwnerFields

// Reports whether this recorded application belongs to a FromObjectField variable owner.
private fun ArbitraryRegistry.applicationUsesFromObjectFieldVariable(application: ResolverApplicationRecord): Boolean =
    sourceResolverCoordinate(application.key.field) in fromObjectFieldVariableOwnerFields
