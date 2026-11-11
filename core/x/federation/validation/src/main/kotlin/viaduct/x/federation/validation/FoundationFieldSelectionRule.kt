package viaduct.x.federation.validation

import graphql.language.Field
import graphql.language.FragmentSpread
import graphql.language.InlineFragment
import graphql.language.OperationDefinition
import graphql.language.SelectionSet
import graphql.parser.InvalidSyntaxException
import graphql.parser.Parser
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.validation.SchemaLocation
import viaduct.graphql.schema.validation.ValidationContext
import viaduct.graphql.schema.validation.ValidationRule

class FoundationFieldSelectionRule : ValidationRule("viaduct.foundation.field-selections") {
    override fun visitTypeDef(
        ctx: ValidationContext,
        typeDef: ViaductSchema.TypeDef
    ) {
        if (typeDef !is ViaductSchema.OutputRecord) return
        typeDef.extensions.forEach { extension ->
            extension.appliedDirectives.filter { it.name == "key" }.forEach { directive ->
                validate(
                    ctx,
                    directive,
                    typeDef,
                    SchemaLocation.ofType(typeDef.name).withSourceLocation(extension.sourceLocation),
                )
            }
        }
    }

    override fun visitField(
        ctx: ValidationContext,
        field: ViaductSchema.Field
    ) {
        if (field.containingDef !is ViaductSchema.OutputRecord) return
        field.appliedDirectives.filter { it.name == "provides" }.forEach { directive ->
            val location = SchemaLocation.ofField(field.containingDef.name, field.name).withSourceLocation(field.sourceLocation)
            val parent = field.type.baseTypeDef as? ViaductSchema.OutputRecord
            if (parent == null) {
                ctx.reportError("PROVIDES_ON_NON_COMPOSITE_FIELD", "@provides requires an object or interface return type.", location)
            }
            validate(ctx, directive, parent, location, field.containingDef is ViaductSchema.Object)
        }
    }

    private fun validate(
        ctx: ValidationContext,
        directive: ViaductSchema.AppliedDirective<*>,
        parent: ViaductSchema.OutputRecord?,
        location: SchemaLocation,
        requiresExternal: Boolean = false,
    ) {
        val prefix = directive.name.uppercase()

        fun report(
            suffix: String,
            message: String
        ) = ctx.reportError("${prefix}_$suffix", message, location)

        val literal = directive.arguments["fields"] as? ViaductSchema.StringLiteral
        if (literal == null) {
            report("INVALID_FIELDS_TYPE", "@${directive.name}(fields:) must be a string.")
            return
        }
        val selections = try {
            val document = Parser().parseDocument("{\n${literal.value}\n}")
            (document.definitions.singleOrNull() as? OperationDefinition)?.selectionSet
        } catch (_: InvalidSyntaxException) {
            null
        }
        if (selections == null) {
            report("INVALID_SYNTAX", "@${directive.name}(fields:) must contain a nonempty GraphQL field selection set.")
            return
        }
        validateSelectionSyntax(selections, ::report)
        if (parent != null) validateReferences(ctx, selections, parent, directive.name == "key", requiresExternal, ::report)
    }

    private fun validateSelectionSyntax(
        selections: SelectionSet,
        report: (String, String) -> Unit
    ) {
        selections.selections.forEach { selection ->
            val directives = when (selection) {
                is Field -> selection.directives
                is InlineFragment -> selection.directives
                is FragmentSpread -> selection.directives
                else -> emptyList()
            }
            if (directives.isNotEmpty()) report("DIRECTIVE_IN_FIELDS_ARGUMENT", "Field selections must not apply directives.")
            when (selection) {
                is Field -> {
                    if (selection.arguments.isNotEmpty()) report("FIELDS_HAS_ARGUMENTS", "Selection '${selection.name}' must not supply arguments.")
                    selection.selectionSet?.let { validateSelectionSyntax(it, report) }
                }
                is InlineFragment -> validateSelectionSyntax(selection.selectionSet, report)
            }
        }
    }

    private fun validateReferences(
        ctx: ValidationContext,
        selections: SelectionSet,
        parent: ViaductSchema.CompositeTypeDef,
        isKey: Boolean,
        requiresExternal: Boolean,
        report: (String, String) -> Unit,
    ) {
        selections.selections.forEach { selection ->
            when (selection) {
                is Field -> validateField(ctx, selection, parent, isKey, requiresExternal, report)
                is InlineFragment -> {
                    val typeName = selection.typeCondition?.name
                    val condition = if (typeName == null) parent else ctx.schema.types[typeName] as? ViaductSchema.CompositeTypeDef
                    if (condition == null || (condition !== parent && condition.possibleObjectTypes.intersect(parent.possibleObjectTypes).isEmpty())) {
                        report("INVALID_FIELDS", "Type condition '$typeName' cannot apply to '${parent.name}'.")
                    } else {
                        validateReferences(ctx, selection.selectionSet, condition, isKey, requiresExternal, report)
                    }
                }
                is FragmentSpread -> report("INVALID_FIELDS", "Named fragment '${selection.name}' is not defined in a field selection set.")
            }
        }
    }

    private fun validateField(
        ctx: ValidationContext,
        selection: Field,
        parent: ViaductSchema.CompositeTypeDef,
        isKey: Boolean,
        requiresExternal: Boolean,
        report: (String, String) -> Unit,
    ) {
        if (selection.name == "__typename") {
            if (requiresExternal) {
                report("FIELDS_MISSING_EXTERNAL", "Provided field '${parent.name}.__typename' is implicit and cannot be marked @external.")
            }
            if (selection.selectionSet != null) {
                report("INVALID_FIELDS", "Field '${parent.name}.__typename' has scalar type String! and cannot have nested selections.")
            }
            return
        }
        val field = (parent as? ViaductSchema.OutputRecord)?.field(selection.name)
        if (field == null) {
            report("INVALID_FIELDS", "Field '${parent.name}.${selection.name}' does not exist.")
            return
        }
        val coordinate = "${parent.name}.${field.name}"
        val base = field.type.baseTypeDef
        if (isKey && (field.type.isList || base is ViaductSchema.Interface || base is ViaductSchema.Union)) {
            report("FIELDS_SELECT_INVALID_TYPE", "Key field '$coordinate' must not have a list, interface, or union type.")
        }
        if (field.args.any { !it.hasAppliedDirective("require") && (!isKey || (!it.type.isNullable && !it.hasDefault)) }) {
            report("FIELDS_HAS_ARGUMENTS", "Field '$coordinate' declares arguments that cannot be resolved by this field selection.")
        }
        if (requiresExternal && !field.hasAppliedDirective("external")) {
            report("FIELDS_MISSING_EXTERNAL", "Provided field '$coordinate' must be marked @external.")
        }
        val children = selection.selectionSet
        if (base is ViaductSchema.CompositeTypeDef) {
            if (children == null) {
                report("INVALID_FIELDS", "Composite field '$coordinate' requires a nested selection set.")
            } else {
                validateReferences(ctx, children, base, isKey, requiresExternal, report)
            }
        } else if (children != null) {
            report("INVALID_FIELDS", "Field '$coordinate' does not have a composite type for nested selections.")
        }
    }
}
