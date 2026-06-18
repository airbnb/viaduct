package viaduct.tenant.codegen.cli

import graphql.language.FragmentDefinition
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLTypeUtil
import graphql.validation.QueryComplexityLimits
import graphql.validation.ValidationErrorType
import graphql.validation.Validator
import java.util.Locale
import viaduct.engine.api.parse.DocumentParser
import viaduct.graphql.utils.DefaultSchemaFactory
import viaduct.graphql.utils.SelectionsParserUtils
import viaduct.tenant.codegen.ksp.ResolverParams

/**
 * Validates a resolver's required selection sets against the tenant's compilation schema. Runs at
 * assembly scope (successor to the per-leaf KSP `ValidateResolverFragments`), where the schema is
 * loaded once per tenant and cross-leaf `@GraphQLFragment` spreads are already resolved, so it is
 * the authoritative GraphQL schema-validation pass for required selection sets.
 *
 * Callers supply [normalizedSelections] (entry selections, shorthand wrapped) for the type-shape
 * check and [expandedSelections] (with cross-leaf fragments appended) for the schema check.
 */
internal class RequiredSelectionSetValidator(
    private val schema: GraphQLSchema,
) {
    private val validator = Validator()

    fun validate(
        normalizedSelections: String,
        expandedSelections: String,
        typeName: String,
        isQuery: Boolean,
        field: ResolverParams.Field,
        errors: MutableList<String>,
    ) {
        validateFragmentType(normalizedSelections, typeName, isQuery, field, errors)
        validateAgainstSchema(expandedSelections, field, errors)
    }

    private fun validateFragmentType(
        normalizedSelections: String,
        typeName: String,
        isQuery: Boolean,
        field: ResolverParams.Field,
        errors: MutableList<String>,
    ) {
        val fragmentTypeName = try {
            val fragments = DocumentParser.parse(normalizedSelections).getDefinitionsOfType(FragmentDefinition::class.java)
            SelectionsParserUtils.findEntryPointFragment(fragments).typeCondition.name
        } catch (e: Exception) {
            errors.add("Unable to extract the fragment type name for ${field.implFqn} (${field.typeName}.${field.fieldName}): ${e.message}")
            return
        }

        if (isQuery) {
            val queryTypeName = schema.queryType.name
            if (fragmentTypeName != queryTypeName) {
                errors.add(
                    "queryValueFragment for ${field.implFqn} must be on the root query type ($queryTypeName), but found type $fragmentTypeName",
                )
            }
        } else {
            if (isMutationExecutionParentType(typeName)) {
                errors.add("Mutation resolver ${field.implFqn} should not set objectValueFragment.")
            } else if (fragmentTypeName != typeName) {
                errors.add(
                    "objectValueFragment for ${field.implFqn} must be on the parent type ($typeName), but found type $fragmentTypeName",
                )
            }
        }
    }

    /**
     * True if [typeName] is the mutation root or a `@namespaceType` object reachable from it — i.e. a
     * type whose fields execute as mutations, which must not declare an objectValueFragment.
     */
    private fun isMutationExecutionParentType(typeName: String): Boolean {
        val mutationType = schema.mutationType ?: return false
        if (typeName == mutationType.name) return true

        val visited = mutableSetOf<String>()

        fun walkNamespaceFields(parent: GraphQLObjectType): Boolean {
            for (field in parent.fieldDefinitions) {
                val baseType = GraphQLTypeUtil.unwrapAll(field.type)
                if (
                    baseType is GraphQLObjectType &&
                    baseType.hasAppliedDirective(DefaultSchemaFactory.DefaultDirective.NAMESPACE_TYPE.directiveName) &&
                    visited.add(baseType.name)
                ) {
                    if (baseType.name == typeName || walkNamespaceFields(baseType)) {
                        return true
                    }
                }
            }
            return false
        }

        return walkNamespaceFields(mutationType)
    }

    private fun validateAgainstSchema(
        expandedSelections: String,
        field: ResolverParams.Field,
        errors: MutableList<String>,
    ) {
        val document = DocumentParser.parse(expandedSelections)
        validator.validateDocument(schema, document, { true }, Locale.ENGLISH, QueryComplexityLimits.NONE).filterNot { FILTERED_ERRORS.contains(it.validationErrorType as ValidationErrorType) }
            .forEach { error ->
                val baseMessage = "Fragment validation failed for ${field.implFqn} (${field.typeName}.${field.fieldName}): ${error.message}"
                errors.add(
                    if (COMPILATION_SCHEMA_RELATED_ERRORS.contains(error.validationErrorType as ValidationErrorType)) {
                        "$baseMessage. This may be caused by a missing field or type in your tenant's compilation schema. " +
                            "See: https://developers.a.musta.ch/docs/default/component/viaduct/tenant-compilation-schemas/"
                    } else {
                        baseMessage
                    },
                )
            }
    }

    companion object {
        // A tenant may legitimately ship a named fragment no resolver spreads, so don't fail on
        // UnusedFragment. (UndefinedFragment is intentionally not filtered: cross-leaf spreads are
        // already resolved here, so an undefined fragment is a real error.)
        private val FILTERED_ERRORS = setOf(
            ValidationErrorType.UnusedFragment,
        )

        // Errors likely caused by a gap in the tenant's compilation schema; we append a docs hint.
        private val COMPILATION_SCHEMA_RELATED_ERRORS = setOf(
            ValidationErrorType.FieldUndefined,
            ValidationErrorType.UnknownType,
            ValidationErrorType.FragmentTypeConditionInvalid,
            ValidationErrorType.InlineFragmentTypeConditionInvalid,
        )
    }
}
