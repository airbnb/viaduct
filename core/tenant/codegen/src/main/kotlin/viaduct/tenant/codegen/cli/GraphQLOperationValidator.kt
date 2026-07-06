package viaduct.tenant.codegen.cli

import graphql.language.FragmentDefinition
import graphql.language.OperationDefinition
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
 * 3. the document, with reachable external @GraphQLFragments appended, is valid against the schema.
 */
internal class GraphQLOperationValidator(
    private val schema: GraphQLSchema,
) {
    private val validator = Validator()

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

        validateAgainstSchema(operation, document, fragmentsByName, errors)
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
        document: graphql.language.Document,
        fragmentsByName: Map<String, String>,
        errors: MutableList<String>,
    ) {
        val expanded = appendReachableFragments(document, fragmentsByName)
        validator.validateDocument(schema, expanded, { true }, Locale.ENGLISH, QueryComplexityLimits.NONE)
            .filterNot { it.validationErrorType in FILTERED_ERRORS }
            .forEach { error ->
                errors.add("@GraphQLOperation validation failed for ${operation.implFqn}: ${error.message}")
            }
    }

    /**
     * Appends external @GraphQLFragment definitions transitively reachable from the document's
     * fragment spreads. Fragments already defined locally in the document are not appended, so a
     * local definition shadows a same-named external one.
     */
    private fun appendReachableFragments(
        document: graphql.language.Document,
        fragmentsByName: Map<String, String>,
    ): graphql.language.Document {
        if (fragmentsByName.isEmpty()) return document

        val localNames = document.getDefinitionsOfType(FragmentDefinition::class.java).map { it.name }.toSet()
        val reachable = FragmentSpreadCollector.collectReachableExternalFragments(
            roots = document.definitions.mapNotNull {
                when (it) {
                    is OperationDefinition -> it.selectionSet
                    is FragmentDefinition -> it.selectionSet
                    else -> null
                }
            },
            knownFragments = fragmentsByName,
            alreadyDefined = localNames,
        )
        if (reachable.isEmpty()) return document

        val builder = graphql.language.Document.newDocument()
        document.definitions.forEach(builder::definition)
        reachable.forEach { name ->
            DocumentParser.parse(fragmentsByName.getValue(name))
                .getDefinitionsOfType(FragmentDefinition::class.java)
                .forEach(builder::definition)
        }
        return builder.build()
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
