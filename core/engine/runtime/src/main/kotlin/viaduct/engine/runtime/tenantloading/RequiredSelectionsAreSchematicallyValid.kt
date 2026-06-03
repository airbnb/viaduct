package viaduct.engine.runtime.tenantloading

import graphql.language.AstPrinter
import graphql.language.Document
import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.FragmentSpread
import graphql.validation.OperationValidationRule
import graphql.validation.QueryComplexityLimits
import graphql.validation.ValidationError
import graphql.validation.ValidationError.newValidationError
import graphql.validation.ValidationErrorType
import graphql.validation.Validator as GJValidator
import java.util.Locale
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.ViaductSchema
import viaduct.engine.runtime.validation.Validator
import viaduct.graphql.utils.SelectionsParserUtils.EntryPointFragmentName

class RequiredSelectionsAreSchematicallyValid(private val schema: ViaductSchema) : Validator<RequiredSelectionsValidationCtx> {
    private val validator = DocumentValidator()

    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun validate(ctx: RequiredSelectionsValidationCtx) {
        val requiredSelections = if (ctx.fieldName != null) {
            ctx.requiredSelectionSetRegistry.getRequiredSelectionSetsForField(ctx.typeName, ctx.fieldName)
        } else {
            ctx.requiredSelectionSetRegistry.getRequiredSelectionSetsForType(ctx.typeName)
        }
        requiredSelections.forEach { rss -> validate(ctx.typeName, ctx.fieldName, rss) }
    }

    private fun validate(
        typeName: String,
        fieldName: String?,
        rss: RequiredSelectionSet
    ) {
        val errs = validator.validateDocument(schema.schema, rss.selections.toDocument(), Locale.getDefault())
        if (errs.isNotEmpty()) {
            throw RequiredSelectionsAreInvalid(typeName, fieldName, rss, errs)
        }
    }
}

private class DocumentValidator {
    private val validator = GJValidator()

    fun validateDocument(
        schema: graphql.schema.GraphQLSchema,
        document: Document,
        locale: Locale
    ): List<ValidationError> {
        val graphqlJavaErrors =
            validator.validateDocument(
                schema,
                document,
                { it != OperationValidationRule.NO_UNUSED_FRAGMENTS },
                locale,
                QueryComplexityLimits.NONE
            )

        return graphqlJavaErrors + validateNoUnusedFragments(document)
    }
}

/**
 * A modified version of graphql-java's NoUnusedFragments rule.
 * This version has an exception for [EntryPointFragmentName] and is simplified by not handling operations,
 * which aren't used in required selection sets
 */
private fun validateNoUnusedFragments(document: Document): List<ValidationError> {
    val fragments = document.getDefinitionsOfType(FragmentDefinition::class.java)
    if (fragments.size == 1) {
        return emptyList()
    }

    val fragmentSpreads = mutableSetOf<String>()
    if (fragments.any { it.name == EntryPointFragmentName }) {
        fragmentSpreads += EntryPointFragmentName
    }

    fragments.forEach { fragment ->
        collectFragmentSpreads(fragment.selectionSet, fragmentSpreads)
    }

    return fragments.mapNotNull { fragment ->
        if (fragment.name in fragmentSpreads) {
            null
        } else {
            newValidationError()
                .validationErrorType(ValidationErrorType.UnusedFragment)
                .description(
                    "Fragment '${
                        fragment.name
                    }' is unused."
                )
                .sourceLocation(fragment.sourceLocation)
                .build()
        }
    }
}

private fun collectFragmentSpreads(
    selectionSet: graphql.language.SelectionSet?,
    fragmentSpreads: MutableSet<String>
) {
    selectionSet?.selections?.forEach { selection ->
        when (selection) {
            is FragmentSpread -> fragmentSpreads += selection.name
            is Field -> collectFragmentSpreads(selection.selectionSet, fragmentSpreads)
            is graphql.language.InlineFragment ->
                collectFragmentSpreads(selection.selectionSet, fragmentSpreads)
        }
    }
}

class RequiredSelectionsAreInvalid(
    val typeName: String,
    val fieldName: String?,
    val rss: RequiredSelectionSet,
    val errors: List<ValidationError>
) : Exception() {
    override val message: String by lazy {
        buildString {
            if (fieldName != null) {
                append("Coordinate $typeName.$fieldName ")
            } else {
                append("Type $typeName ")
            }
            append("has required selections that are not schematically valid\n")
            append("Required selections:\n")
            append(AstPrinter.printAst(rss.selections.toDocument()))
            append("\n")
            append("Errors:\n")
            errors.forEach { error ->
                append(error.toString())
                append("\n")
            }
        }
    }
}
