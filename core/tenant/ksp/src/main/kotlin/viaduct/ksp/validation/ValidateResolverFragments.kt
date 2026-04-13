package viaduct.ksp.validation

import graphql.language.FragmentDefinition
import graphql.parser.Parser
import graphql.schema.GraphQLSchema
import graphql.validation.ValidationErrorType
import graphql.validation.Validator
import java.util.Locale
import viaduct.graphql.utils.SelectionsParserUtils
import viaduct.tenant.validation.ErrorMessage
import viaduct.tenant.validation.IValidator

/**
 * Validates resolver fragments against the GraphQL schema, and sures that they
 * are on the expected type.
 */
internal class ValidateResolverFragments(
    private val specs: List<ResolverFragmentSpec>,
    private val schema: GraphQLSchema,
    private val fragmentValidator: Validator
) : IValidator {
    override fun validate(): List<ErrorMessage> {
        val errors = mutableListOf<ErrorMessage>()

        specs.forEach { spec ->
            validateType(spec)?.let { error -> errors.add(error) }
            errors.addAll(validateAgainstSchema(spec))
        }

        return errors
    }

    private fun validateType(spec: ResolverFragmentSpec): ErrorMessage? {
        val fragmentTypeName = try {
            extractFragmentTypeName(spec.fragment)
        } catch (e: Exception) {
            return "Unable to extract the fragment type name from the resolver ${spec.metadata.fullClassName}'s fragment '${spec.fragment}': ${e.message}"
        }

        when (spec.metadata.fragmentType) {
            ResolverFragmentType.OBJECT -> {
                val typeName = spec.metadata.typeName
                val isMutation = typeName == schema.mutationType?.name

                if (isMutation) {
                    return "Mutation resolver ${spec.metadata.fullClassName} should not set objectValueFragment."
                } else if (fragmentTypeName != typeName) {
                    return "objectValueFragment for resolver ${spec.metadata.fullClassName} must be on the parent type ($typeName), " +
                        "but found type $fragmentTypeName"
                }
            }

            ResolverFragmentType.QUERY -> {
                val queryTypeName = schema.queryType.name
                if (fragmentTypeName != queryTypeName) {
                    return "queryValueFragment for resolver ${spec.metadata.fullClassName} must be on the root query type ($queryTypeName), " +
                        "but found type $fragmentTypeName"
                }
            }
        }

        return null
    }

    private fun extractFragmentTypeName(fragmentString: String): String {
        val document = Parser().parseDocument(fragmentString)
        val fragments = document.definitions.filterIsInstance<FragmentDefinition>()
        val entryPoint = SelectionsParserUtils.findEntryPointFragment(fragments)
        return entryPoint.typeCondition.name
    }

    private fun validateAgainstSchema(spec: ResolverFragmentSpec): List<ErrorMessage> {
        return fragmentValidator
            .validateDocument(schema, spec.fragmentDocument(), Locale("en"))
            .filterNot { FILTERED_VALIDATION_ERROR.contains(it.validationErrorType as ValidationErrorType) }
            .map { error ->
                val baseMessage = "Fragment validation failed for ${spec.metadata.fullClassName}: ${error.message}"
                if (POSSIBLE_COMPILATION_SCHEMA_RELATED_ERRORS.contains(error.validationErrorType as ValidationErrorType)) {
                    "$baseMessage. This may be caused by a missing field or type in your tenant's compilation schema. " +
                        "See: https://developers.a.musta.ch/docs/default/component/viaduct/tenant-compilation-schemas/"
                } else {
                    baseMessage
                }
            }
    }

    companion object {
        // Validation errors we skip on
        // Full list of validation errors can be found here:
        // https://github.com/graphql-java/graphql-java/blob/master/src/main/java/graphql/validation/ValidationErrorType.java
        private val FILTERED_VALIDATION_ERROR = setOf(
            ValidationErrorType.UnusedFragment,
        )

        // Validation errors that may be caused by missing fields/types in the tenant's compilation schema
        private val POSSIBLE_COMPILATION_SCHEMA_RELATED_ERRORS = setOf(
            ValidationErrorType.FieldUndefined,
            ValidationErrorType.UnknownType,
            ValidationErrorType.FragmentTypeConditionInvalid,
            ValidationErrorType.InlineFragmentTypeConditionInvalid,
        )
    }
}
