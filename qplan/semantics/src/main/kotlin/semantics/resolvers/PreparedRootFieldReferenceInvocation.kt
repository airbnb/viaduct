package semantics.resolvers

import model.ObjectEngineResult
import model.PathComponent
import model.ResolverOccurrenceId
import model.RootFieldReferenceData
import model.engineObjectDataOf
import model.registry.FieldValueResolver
import model.registry.ResolverFragments
import model.registry.VariableDefinition
import model.requireQueryTypeDef
import semantics.shared.SharedOperationContext

/** One independently rooted invocation prepared from a symbolic root-field reference. */
internal data class PreparedRootFieldReferenceInvocation(
    val invocationRoot: ObjectEngineResult,
    val invocationPath: List<PathComponent>,
    val invocationKey: ObjectEngineResult.GroundKey,
    val resolver: FieldValueResolver,
    val fragments: ResolverFragments,
)

/**
 * Creates the empty Query-rooted identity and binds the grounded arguments for one reference hop.
 *
 * The maintained pre-Resolver26 algorithms support only `FromArgument` variables. Reference
 * targets inherit that boundary instead of acquiring Resolver26's runtime binding protocols.
 */
internal fun RootFieldReferenceData.prepareRootFieldReferenceInvocation(operation: SharedOperationContext<*>): PreparedRootFieldReferenceInvocation {
    require(targetField in operation.world.resolverRegistry) {
        "Root-field-reference target has no registered resolver: " +
            "${targetField.containingDef.name}/${targetField.name}"
    }
    val invocationRoot = ObjectEngineResult.of(operation.world.schema.requireQueryTypeDef())
    val prefixKeys =
        path.dropLast(1).map { field ->
            ObjectEngineResult.GroundKey.of(field, emptyMap())
        }
    val invocationKey = ObjectEngineResult.GroundKey.of(targetField, arguments)
    val invocationPath: List<PathComponent> = prefixKeys + invocationKey
    val resolverOccurrenceId = ResolverOccurrenceId.at(invocationRoot, invocationPath)
    val resolver = operation.world.resolverRegistry.resolver(targetField)
    val fragments = resolver.instantiateFragments(resolverOccurrenceId)
    require(fragments.objectFragment.constructionSelections.isEmpty()) {
        "Root-field-reference target ${targetField.containingDef.name}/${targetField.name} " +
            "must not declare an object fragment"
    }
    require(resolver.variables.values.all { definition -> definition is VariableDefinition.FromArgument }) {
        "Root-field-reference target ${targetField.containingDef.name}/${targetField.name} " +
            "uses a variable source unsupported before Resolver26"
    }
    setOf(invocationKey).bindFromArguments(operation, invocationRoot, prefixKeys)
    return PreparedRootFieldReferenceInvocation(
        invocationRoot = invocationRoot,
        invocationPath = invocationPath,
        invocationKey = invocationKey,
        resolver = resolver,
        fragments = fragments,
    )
}

/** Empty resolver object input required by every root-field-reference target. */
internal fun PreparedRootFieldReferenceInvocation.emptyObjectInput() = engineObjectDataOf(invocationKey.field.containingDef)
