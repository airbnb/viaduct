package viaduct.engine.runtime.validation

import graphql.Directives
import graphql.ExecutionInput
import graphql.execution.preparsed.PreparsedDocumentEntry
import graphql.execution.preparsed.PreparsedDocumentProvider
import graphql.language.Directive
import graphql.language.Document
import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.FragmentSpread
import graphql.language.InlineFragment
import graphql.language.Node
import graphql.language.NullValue
import graphql.language.OperationDefinition
import graphql.language.Selection
import graphql.language.StringValue
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLTypeUtil
import graphql.util.TraversalControl
import graphql.util.Traverser
import graphql.util.TraverserContext
import graphql.util.TraverserVisitorStub
import graphql.validation.ValidationError
import graphql.validation.ValidationErrorType
import graphql.validation.Validator as GraphQLValidator
import java.util.concurrent.CompletableFuture
import java.util.function.Function
import viaduct.engine.api.EngineSchema

/**
 * Adds @defer validation after graphql-java's normal validation, reusing the parsed document.
 * Runs inside the provider's callback so caching providers can cache validation errors with the document.
 */
class IncrementalDirectiveValidation(
    private val wrappedProvider: PreparsedDocumentProvider,
    private val schema: EngineSchema,
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
                val errors = validateLabels(document)
                validateRootFields(document, schema, errors)
                PreparsedDocumentEntry(document, entry.errors + errors)
            }
        }
}

/**
 * Checks @defer labels. Omitted and null labels are allowed; all other labels must be
 * string literals. String labels must be unique across all operations and fragment definitions,
 * even when a directive is disabled with `if: false`. Labels inside a fragment definition count once,
 * regardless of how often that fragment is spread.
 */
private fun validateLabels(document: Document): MutableList<ValidationError> {
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

/** Tracks a selection's namespace and error path; [namespaceType] is null at the operation root. */
private data class RootSelection(
    val selection: Selection<*>,
    val queryPath: List<String>,
    val namespaceType: GraphQLObjectType?,
)

/**
 * Rejects @defer on inline fragments and fragment spreads at mutation and subscription roots,
 * including uses reached through named fragments and disabled or skipped selections.
 * Mutation namespaces group mutation actions, so the same restriction applies inside them.
 * Fields on a mutation's returned object can still be deferred.
 */
private fun validateRootFields(
    document: Document,
    schema: EngineSchema,
    errors: MutableList<ValidationError>
) {
    if (errors.size >= GraphQLValidator.getMaxValidationErrors()) return
    val operations = document.getDefinitionsOfType(OperationDefinition::class.java)
        .filter { it.operation != OperationDefinition.Operation.QUERY }
    if (operations.isEmpty()) return
    val fragments = document.getDefinitionsOfType(FragmentDefinition::class.java).associateBy { it.name }
    for (operation in operations) {
        val mutationType = schema.schema.mutationType.takeIf { operation.operation == OperationDefinition.Operation.MUTATION }
        val visitedFragments = mutableSetOf<Pair<String, GraphQLObjectType?>>()
        val pending = ArrayDeque<RootSelection>()
        pending.addAll(operation.selectionSet.selections.asReversed().map { RootSelection(it, emptyList(), null) })
        while (pending.isNotEmpty()) {
            val (selection, queryPath, namespaceType) = pending.removeLast()
            val directives = when (selection) {
                is InlineFragment -> selection.directives
                is FragmentSpread -> selection.directives
                is Field -> {
                    val fieldType = (namespaceType ?: mutationType)?.getFieldDefinition(selection.name)?.type?.let(GraphQLTypeUtil::unwrapAll)
                    if (fieldType is GraphQLObjectType && schema.isMutationNamespaceType(fieldType.name)) {
                        val childPath = queryPath + selection.name
                        pending.addAll(selection.selectionSet?.selections.orEmpty().asReversed().map { RootSelection(it, childPath, fieldType) })
                    }
                    continue
                }
                else -> continue
            }
            val defer = directives.firstOrNull { it.name == Directives.DeferDirective.name }
            if (defer != null) {
                errors += ValidationError.newValidationError()
                    .validationErrorType(ValidationErrorType.MisplacedDirective)
                    .sourceLocations(listOfNotNull(defer.sourceLocation))
                    .queryPath(queryPath)
                    .description(
                        if (namespaceType == null) {
                            "The @defer directive cannot be used on a root ${operation.operation.name.lowercase()} selection."
                        } else {
                            "The @defer directive cannot be used on a selection of mutation namespace type '${namespaceType.name}'."
                        },
                    )
                    .build()
                if (errors.size >= GraphQLValidator.getMaxValidationErrors()) return
            }
            val (selections, childPath) = when (selection) {
                is InlineFragment -> selection.selectionSet.selections to queryPath
                is FragmentSpread -> {
                    // Check each spread's directive before skipping an already-visited fragment body.
                    if (!visitedFragments.add(selection.name to namespaceType)) continue
                    val fragment = fragments[selection.name] ?: continue
                    fragment.selectionSet.selections to listOf(fragment.name)
                }
                else -> continue
            }
            pending.addAll(selections.asReversed().map { RootSelection(it, childPath, namespaceType) })
        }
    }
}
