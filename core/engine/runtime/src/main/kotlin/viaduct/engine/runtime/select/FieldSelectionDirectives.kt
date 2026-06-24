package viaduct.engine.runtime.select

import graphql.execution.ValuesResolver
import graphql.language.Field
import viaduct.engine.api.FieldDirectives

/** Adapts a selected GraphQL [Field] to the engine-owned [FieldDirectives] API. */
internal class FieldSelectionDirectives(
    private val field: Field,
    private val ctx: EngineSelectionSetContext,
) : FieldDirectives {
    /**
     * Checks directives from the GraphQL AST [Field], resolving argument values only
     * when a caller provides an argument predicate.
     */
    override fun hasDirective(
        name: String,
        args: ((Map<String, Any?>) -> Boolean)?,
    ): Boolean {
        val directives = field.directives.filter { it.name == name }
        if (directives.isEmpty()) return false
        if (args == null) return true

        val directiveDefinition = ctx.schema.schema.getDirective(name) ?: return false
        return directives.any { directive ->
            args(
                ValuesResolver.getArgumentValues(
                    ctx.schema.schema.codeRegistry,
                    directiveDefinition.arguments,
                    directive.arguments,
                    ctx.coercedVariables,
                    ctx.gjContext,
                    ctx.locale
                )
            )
        }
    }
}
