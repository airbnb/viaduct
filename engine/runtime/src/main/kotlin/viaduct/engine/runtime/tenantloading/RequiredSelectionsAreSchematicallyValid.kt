package viaduct.engine.runtime.tenantloading

import graphql.language.AstPrinter
import graphql.language.Document
import graphql.language.FragmentDefinition
import graphql.language.FragmentSpread
import graphql.validation.AbstractRule
import graphql.validation.ValidationContext
import graphql.validation.ValidationError
import graphql.validation.ValidationErrorCollector
import graphql.validation.ValidationErrorType
import graphql.validation.Validator as GJValidator
import graphql.validation.rules.NoUnusedFragments
import java.util.Locale
import viaduct.engine.api.Coordinate
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.select.Constants.EntryPointFragmentName
import viaduct.engine.api.select.SelectionsParser
import viaduct.engine.runtime.validation.Validator

class RequiredSelectionsAreSchematicallyValid(private val schema: ViaductSchema) : Validator<RequiredSelectionsValidationCtx> {
    private val validator = DocumentValidator()

    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun validate(ctx: RequiredSelectionsValidationCtx) {
        ctx.requiredSelectionSetRegistry.getRequiredSelectionSets(ctx.coord.first, ctx.coord.second, true)
            .forEach { rss -> validate(ctx.coord, rss) }
    }

    private fun validate(
        coordinate: Coordinate,
        rss: RequiredSelectionSet
    ) {
        val errs = validator.validateDocument(schema.schema, rss.selections.toDocument(), Locale.getDefault())
        if (errs.isNotEmpty()) {
            throw RequiredSelectionsAreInvalid(coordinate, rss, errs)
        }
    }
}

private class DocumentValidator : GJValidator() {
    override fun createRules(
        validationContext: ValidationContext,
        validationErrorCollector: ValidationErrorCollector
    ): List<AbstractRule> =
        super.createRules(validationContext, validationErrorCollector).map {
            when (it) {
                // Viaduct required selection sets are generated as a document containing a single fragment named Main
                // This will by default fail the NoUnusedFragments rule, since the fragment is not used in a document.
                // Override this rule so that we're still able to detect unused fragments that aren't named Main
                is NoUnusedFragments -> ViaductNoUnusedFragments(validationContext, validationErrorCollector)
                else -> it
            }
        }
}

/**
 * A modified version of graphql-java's [NoUnusedFragments].
 * This version has an exception for [EntryPointFragmentName] and is simplified by not handling operations,
 * which aren't used in required selection sets
 */
private class ViaductNoUnusedFragments(
    validationContext: ValidationContext,
    validationErrorCollector: ValidationErrorCollector
) : AbstractRule(validationContext, validationErrorCollector) {
    private val fragmentSpreads = mutableSetOf<String>()
    private val fragmentDefinitions = mutableMapOf<String, FragmentDefinition>()

    override fun checkFragmentSpread(fragmentSpread: FragmentSpread) {
        fragmentSpreads += fragmentSpread.name
    }

    override fun checkFragmentDefinition(fragmentDefinition: FragmentDefinition) {
        fragmentDefinitions.put(fragmentDefinition.name, fragmentDefinition)
    }

    override fun documentFinished(document: Document) {
        /**
         * RSS documents may either define 1 fragment, or must contain one named Main
         * This rule is baked into [SelectionsParser.parse]
         */
        val fragments = document.getDefinitionsOfType(FragmentDefinition::class.java)
        if (fragments.size == 1) return
        if (fragments.any { it.name == EntryPointFragmentName }) {
            fragmentSpreads.add(EntryPointFragmentName)
        }

        fragmentDefinitions.forEach { name, def ->
            if (name !in fragmentSpreads) {
                val message = i18n(ValidationErrorType.UnusedFragment, "NoUnusedFragments.unusedFragments", name)
                addError(ValidationErrorType.UnusedFragment, def.getSourceLocation(), message)
            }
        }
    }
}

class RequiredSelectionsAreInvalid(val coordinate: Coordinate, val rss: RequiredSelectionSet, val errors: List<ValidationError>) : Exception() {
    override val message: String by lazy {
        buildString {
            append("Coordinate ${coordinate.first}.${coordinate.second} has required selections that are not schematically valid\n")
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
