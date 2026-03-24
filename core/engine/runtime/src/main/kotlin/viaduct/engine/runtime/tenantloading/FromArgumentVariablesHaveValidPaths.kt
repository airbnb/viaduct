package viaduct.engine.runtime.tenantloading

import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLTypeUtil
import viaduct.engine.api.Coordinate
import viaduct.engine.api.FromArgument
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.Validated
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.gj
import viaduct.engine.runtime.validation.Validator
import viaduct.graphql.utils.collectVariableUsages

/**
 * Validates that all variable references from FromArgumentVariable instances
 * reference valid field arguments in the GraphQL schema, and that the types are compatible.
 * This includes null vs nullable, nested paths, default argument values, and OneOf fields.
 */
class FromArgumentVariablesHaveValidPaths(
    private val schema: ViaductSchema
) : Validator<RequiredSelectionsValidationCtx> {
    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun validate(ctx: RequiredSelectionsValidationCtx) {
        if (ctx.fieldName == null) {
            // This is an RSS for a type, which does not have arguments
            return
        }
        ctx.requiredSelectionSetRegistry
            .getRequiredSelectionSetsForField(ctx.typeName, ctx.fieldName, true)
            .forEach { selectionSet ->
                validateFromArgumentVariables(ctx.typeName to ctx.fieldName, selectionSet)
            }
    }

    private fun validateFromArgumentVariables(
        coordinate: Coordinate,
        rss: RequiredSelectionSet
    ) {
        val fieldDef = schema.schema.getFieldDefinition(coordinate.gj)
            ?: return // Field not found in schema - let other validators handle this

        extractFromArgumentVariables(rss.variablesResolvers).forEach { variable ->
            validateFromArgumentVariable(coordinate, variable, fieldDef, rss)
        }
    }

    /**
     * Extracts all [FromArgument] instances from a list of [VariablesResolver]s.
     */
    private fun extractFromArgumentVariables(resolvers: List<VariablesResolver>): List<FromArgument> {
        return resolvers.mapNotNull { resolver ->
            resolver as? FromArgument
                ?: if (resolver is Validated && resolver.delegate is FromArgument) {
                    resolver.delegate as FromArgument
                } else {
                    null
                }
        }
    }

    private fun validateFromArgumentVariable(
        coordinate: Coordinate,
        variable: FromArgument,
        fieldDef: graphql.schema.GraphQLFieldDefinition,
        rss: RequiredSelectionSet
    ) {
        if (variable.path.isEmpty()) {
            throw InvalidVariableException(
                coordinate,
                variable.name,
                "Path cannot be empty for FromArgument variable '${variable.name}'."
            )
        }

        // Validate first segment against field arguments
        val firstPathSegment = variable.path.first()
        val argument = fieldDef.arguments.find { it.name == firstPathSegment }
        if (argument == null) {
            throw InvalidVariableException(
                coordinate,
                variable.name,
                "Argument '$firstPathSegment' does not exist."
            )
        }

        // Validate path structure and get resolved type
        val sourceType = Type(argument.type).let {
            var initialType = it
            if (GraphQLTypeUtil.isNullable(argument.type)) {
                initialType += Type.Property.NullableTraversalPath
            }

            buildType(variable.path.drop(1), listOf(firstPathSegment), initialType, coordinate, variable, fieldDef)
        }

        // Validate type compatibility for each usage
        val variableUsages = try {
            rss.selections.selections.collectVariableUsages(schema.schema, variable.name, rss.selections.typeName, rss.selections.fragmentMap)
        } catch (e: Exception) {
            throw IllegalStateException(
                "Variable usage collection failed for RSS with attribution ${rss.attribution}",
                e
            )
        }
        variableUsages.forEach { usage ->
            val locationType = Type(usage)
            if (!areTypesCompatible(locationType, sourceType)) {
                val errorMessage =
                    "Type mismatch: variable '${variable.name}' resolves to type '${GraphQLTypeUtil.simplePrint(sourceType.type)}' " +
                        "but is used in ${usage.contextString} expecting type '${GraphQLTypeUtil.simplePrint(usage.type)}'"

                throw InvalidVariableException(
                    coordinate,
                    variable.name,
                    errorMessage
                )
            }
        }
    }

    /**
     * Validates remaining path segments against a GraphQL type (for nested object traversal).
     * Returns the final type after path traversal.
     */
    private fun buildType(
        remainingPath: List<String>,
        currentPath: List<String>,
        currentType: Type,
        coordinate: Coordinate,
        variable: FromArgument,
        fieldDef: GraphQLFieldDefinition
    ): Type {
        if (remainingPath.isEmpty()) {
            return currentType
        }

        if (currentType.type.isListish) {
            throw InvalidVariableException(
                coordinate,
                variable.name,
                "Cannot traverse through list type at path segment '${currentPath.joinToString(".")}'. " +
                    "Path traversal through lists is not supported by InputValueReader."
            )
        }

        val fieldName = remainingPath.first()
        val unwrappedType = GraphQLTypeUtil.unwrapAll(currentType.type)

        // Only input object types can have nested fields
        if (unwrappedType !is GraphQLInputObjectType) {
            throw InvalidVariableException(
                coordinate,
                variable.name,
                "Cannot traverse to field '$fieldName' from non-object type '${unwrappedType.name}' " +
                    "at path '${currentPath.joinToString(".")}'"
            )
        }

        val field = unwrappedType.getFieldDefinition(fieldName)
        if (field == null) {
            throw InvalidVariableException(
                coordinate,
                variable.name,
                "Field '$fieldName' does not exist in input type '${unwrappedType.name}' " +
                    "at path '${currentPath.joinToString(".")}'. "
            )
        }

        val nextType = currentType.copy(field.type).let {
            if (GraphQLTypeUtil.isNullable(field.type)) {
                it + Type.Property.NullableTraversalPath
            } else {
                it
            }
        }
        // Continue recursively with the field's type
        return buildType(
            remainingPath.drop(1),
            currentPath + fieldName,
            nextType,
            coordinate,
            variable,
            fieldDef
        )
    }
}
