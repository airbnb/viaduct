package viaduct.tenant.codegen.cli

import graphql.language.Document
import graphql.language.FragmentDefinition
import graphql.schema.GraphQLSchema
import graphql.validation.QueryComplexityLimits
import graphql.validation.ValidationErrorType
import graphql.validation.Validator
import java.util.Locale
import viaduct.engine.api.parse.DocumentParser
import viaduct.tenant.codegen.ksp.NamedFragmentDescriptor

/**
 * Validates @GraphQLFragment declarations against the schema at assembly time, independently of
 * whether any resolver or operation spreads them. Per fragment:
 *
 * 1. the `FragmentFromAnnotation<T>` GRT type argument, when known, matches the fragment text's
 *    `on <Type>` condition — this catches declaring a fragment `on User` while typing the object as
 *    `FragmentFromAnnotation<Query>`, which is otherwise unguarded until runtime;
 * 2. the fragment, with reachable sibling @GraphQLFragments appended, is valid against the schema —
 *    this catches undefined fields and bad type conditions on fragments nothing happens to spread.
 */
internal class NamedFragmentValidator(
    private val schema: GraphQLSchema,
) {
    private val validator = Validator()

    fun validate(
        fragment: NamedFragmentDescriptor,
        fragmentsByName: Map<String, String>,
        errors: MutableList<String>,
    ) {
        val definition = try {
            DocumentParser.parse(fragment.text)
                .getDefinitionsOfType(FragmentDefinition::class.java)
                .singleOrNull()
        } catch (e: Exception) {
            errors.add("Unable to parse @GraphQLFragment '${fragment.text}': ${e.message}")
            return
        }
        if (definition == null) {
            errors.add("@GraphQLFragment must contain exactly one fragment definition: '${fragment.text}'")
            return
        }

        validateGrtMatchesTypeCondition(fragment, definition, errors)
        validateAgainstSchema(definition, fragmentsByName, errors)
    }

    /**
     * Checks the fragment's declared GRT type argument against its `on <Type>` condition. Only runs
     * when the GRT type name is known; a null [NamedFragmentDescriptor.grtTypeName] skips this check.
     */
    private fun validateGrtMatchesTypeCondition(
        fragment: NamedFragmentDescriptor,
        definition: FragmentDefinition,
        errors: MutableList<String>,
    ) {
        val grtTypeName = fragment.grtTypeName ?: return
        val typeConditionName = definition.typeCondition.name
        if (grtTypeName != typeConditionName) {
            errors.add(
                "@GraphQLFragment '${definition.name}' is declared as FragmentFromAnnotation<$grtTypeName> " +
                    "but its fragment text is on type '$typeConditionName'. The GRT type argument must match " +
                    "the fragment's 'on <Type>' condition.",
            )
        }
    }

    private fun validateAgainstSchema(
        definition: FragmentDefinition,
        fragmentsByName: Map<String, String>,
        errors: MutableList<String>,
    ) {
        val document = Document.newDocument().definition(definition).build()
        val expanded = FragmentSpreadCollector.appendReachableExternalFragments(document, fragmentsByName)

        validator.validateDocument(schema, expanded, { true }, Locale.ENGLISH, QueryComplexityLimits.NONE)
            .filterNot { it.validationErrorType in FILTERED_ERRORS }
            .forEach { error ->
                errors.add("@GraphQLFragment validation failed for '${definition.name}': ${error.message}")
            }
    }

    companion object {
        // A standalone fragment document is never "used" by an operation, so UnusedFragment is
        // expected here and must not fail the build.
        private val FILTERED_ERRORS = setOf(ValidationErrorType.UnusedFragment)
    }
}
