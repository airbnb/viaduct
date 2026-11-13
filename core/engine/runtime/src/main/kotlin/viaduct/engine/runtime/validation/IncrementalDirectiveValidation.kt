package viaduct.engine.runtime.validation

import graphql.Directives
import graphql.ExecutionInput
import graphql.execution.preparsed.PreparsedDocumentEntry
import graphql.execution.preparsed.PreparsedDocumentProvider
import graphql.language.Directive
import graphql.language.Document
import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.Node
import graphql.language.NullValue
import graphql.language.StringValue
import graphql.util.TraversalControl
import graphql.util.Traverser
import graphql.util.TraverserContext
import graphql.util.TraverserVisitorStub
import graphql.validation.ValidationError
import graphql.validation.ValidationErrorType
import graphql.validation.Validator as GraphQLValidator
import java.util.concurrent.CompletableFuture
import java.util.function.Function

/**
 * Adds @defer validation after graphql-java's normal validation, reusing the parsed document.
 * Runs inside the provider's callback so caching providers can cache validation errors with the document.
 */
class IncrementalDirectiveValidation(
    private val wrappedProvider: PreparsedDocumentProvider,
) : PreparsedDocumentProvider {
    override fun getDocumentAsync(
        executionInput: ExecutionInput,
        parseAndValidateFunction: Function<ExecutionInput, PreparsedDocumentEntry>,
    ): CompletableFuture<PreparsedDocumentEntry?> =
        wrappedProvider.getDocumentAsync(executionInput) { input ->
            val entry = parseAndValidateFunction.apply(input)
            val document = entry.document
            if (document == null) {
                entry
            } else {
                PreparsedDocumentEntry(document, entry.errors + validateLabels(document))
            }
        }
}

/**
 * Checks @defer labels. Omitted and null labels are allowed; all other labels must be
 * string literals. String labels must be unique across all operations and fragment definitions,
 * even when a directive is disabled with `if: false`. Labels inside a fragment definition count once,
 * regardless of how often that fragment is spread.
 */
private fun validateLabels(document: Document): List<ValidationError> {
    val errors = mutableListOf<ValidationError>()
    val firstDirectiveByLabel = mutableMapOf<String, Directive>()
    val visitor = object : TraverserVisitorStub<Node<*>>() {
        override fun enter(context: TraverserContext<Node<*>>): TraversalControl {
            if (errors.size >= GraphQLValidator.getMaxValidationErrors()) return TraversalControl.QUIT
            val node = context.thisNode()
            if (node !is Directive || node.name != Directives.DeferDirective.name) return TraversalControl.CONTINUE

            val label = node.getArgument("label")?.value
            val firstDirective = if (label is StringValue) firstDirectiveByLabel.putIfAbsent(label.value, node) else null
            val message = when {
                firstDirective != null -> "Label values on @defer must be unique across the document."
                label == null || label is NullValue || label is StringValue -> null
                else -> "The label argument on @defer must be a static string."
            }
            if (message != null) {
                // Match graphql-java's validation paths so partial query validation can locate these errors.
                val queryPath = context.parentNodes.asReversed().mapNotNull {
                    when (it) {
                        is Field -> it.name
                        is FragmentDefinition -> it.name
                        else -> null
                    }
                }
                errors += ValidationError.newValidationError()
                    .validationErrorType(ValidationErrorType.WrongType)
                    .sourceLocations(listOfNotNull(firstDirective?.sourceLocation, node.sourceLocation))
                    .queryPath(queryPath)
                    .description(message)
                    .build()
            }
            return TraversalControl.CONTINUE
        }
    }
    Traverser.depthFirst<Node<*>> { it.children }.traverse(document, visitor)
    return errors
}
