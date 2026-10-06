package viaduct.engine.runtime2.resolvers

import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.PathComponent
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.RootFieldReferenceData
import viaduct.engine.runtime2.model.engineObjectDataOf
import viaduct.engine.runtime2.model.registry.FieldValueResolver
import viaduct.engine.runtime2.model.registry.ResolverFragments
import viaduct.engine.runtime2.model.registry.VariableDefinition
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

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
 * The maintained pre-Resolution algorithms support only `FromArgument` variables. Reference
 * targets inherit that boundary instead of acquiring Resolution's runtime binding protocols.
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
            "uses a variable source unsupported before Resolution"
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
