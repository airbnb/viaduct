package viaduct.tenant.codegen.cli

import graphql.language.FragmentDefinition
import graphql.language.OperationDefinition
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import graphql.validation.QueryComplexityLimits
import graphql.validation.ValidationErrorType
import graphql.validation.Validator
import java.util.Locale
import viaduct.engine.api.parse.DocumentParser
import viaduct.tenant.codegen.ksp.OperationDescriptor
import viaduct.tenant.codegen.ksp.OperationKind

/**
 * Validates @GraphQLOperation documents against the schema at assembly time. Per operation:
 * 1. exactly one operation in the document;
 * 2. operation type matches the declared base class (subscription rejected);
 * 3. the document, with reachable external @GraphQLFragments appended, is valid against the schema;
 * 4. no selected field carries one of [forbiddenSelectionDirectives].
 */
internal class GraphQLOperationValidator(
    private val schema: GraphQLSchema,
    forbiddenSelectionDirectives: Set<String> = emptySet(),
) {
    private val validator = Validator()
    private val forbiddenDirectiveChecker = ForbiddenSelectionDirectiveChecker(schema, forbiddenSelectionDirectives)

    fun validate(
        operation: OperationDescriptor,
        fragmentsByName: Map<String, String>,
        errors: MutableList<String>,
    ) {
        val document = try {
            DocumentParser.parse(operation.text)
        } catch (e: Exception) {
            errors.add("Unable to parse @GraphQLOperation on ${operation.implFqn}: ${e.message}")
            return
        }

        val operations = document.getDefinitionsOfType(OperationDefinition::class.java)
        if (operations.size != 1) {
            errors.add("@GraphQLOperation on ${operation.implFqn} must contain exactly one operation, found ${operations.size}.")
            return
        }

        if (!operationTypeMatches(operations.single(), operation, errors)) {
            return
        }

        val expanded = FragmentSpreadCollector.appendReachableExternalFragments(document, fragmentsByName)
        if (validateAgainstSchema(operation, expanded, errors)) {
            validateSelectedFields(operation, operations.single(), expanded, errors)
        }
    }

    private fun operationTypeMatches(
        op: OperationDefinition,
        operation: OperationDescriptor,
        errors: MutableList<String>,
    ): Boolean {
        // An anonymous shorthand operation (`{ ... }`) parses as a QUERY operation.
        val actual = op.operation ?: OperationDefinition.Operation.QUERY
        val expected = when (operation.kind) {
            OperationKind.QUERY -> OperationDefinition.Operation.QUERY
            OperationKind.MUTATION -> OperationDefinition.Operation.MUTATION
        }
        if (actual != expected) {
            errors.add(
                "@GraphQLOperation on ${operation.implFqn} declares a ${actual.name.lowercase()} operation, " +
                    "but the object extends ${operation.kind.baseClassName()} (expected ${expected.name.lowercase()}).",
            )
            return false
        }
        return true
    }

    private fun validateAgainstSchema(
        operation: OperationDescriptor,
        expanded: graphql.language.Document,
        errors: MutableList<String>,
    ): Boolean {
        val schemaErrors = validator.validateDocument(schema, expanded, { true }, Locale.ENGLISH, QueryComplexityLimits.NONE)
            .filterNot { it.validationErrorType in FILTERED_ERRORS }
        schemaErrors.forEach { error ->
            errors.add("@GraphQLOperation validation failed for ${operation.implFqn}: ${error.message}")
        }
        return schemaErrors.isEmpty()
    }

    private fun validateSelectedFields(
        operation: OperationDescriptor,
        op: OperationDefinition,
        expanded: graphql.language.Document,
        errors: MutableList<String>,
    ) {
        if (!forbiddenDirectiveChecker.isEnabled) return
        val rootType: GraphQLObjectType = when (op.operation ?: OperationDefinition.Operation.QUERY) {
            OperationDefinition.Operation.MUTATION -> schema.mutationType
            else -> schema.queryType
        } ?: return
        val fragmentsByName = expanded.getDefinitionsOfType(FragmentDefinition::class.java).associateBy { it.name }
        forbiddenDirectiveChecker.violations(op, rootType, fragmentsByName).forEach { violation ->
            errors.add("@GraphQLOperation on ${operation.implFqn} ${violation.message()}")
        }
    }

    private fun OperationKind.baseClassName(): String =
        when (this) {
            OperationKind.QUERY -> "QueryFromAnnotation"
            OperationKind.MUTATION -> "MutationFromAnnotation"
        }

    companion object {
        // A tenant may legitimately declare a @GraphQLFragment that this operation doesn't use.
        private val FILTERED_ERRORS = setOf(ValidationErrorType.UnusedFragment)
    }
}
