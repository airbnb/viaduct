package viaduct.engine.runtime.validation

import graphql.Directives
import graphql.ErrorType
import graphql.ExecutionInput
import graphql.GraphQLError
import graphql.GraphqlErrorBuilder
import graphql.execution.ExecutionId
import graphql.execution.ResultPath
import graphql.execution.conditional.ConditionalNodes
import graphql.execution.preparsed.PreparsedDocumentEntry
import graphql.execution.preparsed.PreparsedDocumentProvider
import graphql.language.BooleanValue
import graphql.language.Directive
import graphql.language.DirectivesContainer
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
import graphql.language.VariableReference
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLTypeUtil
import graphql.util.TraversalControl
import graphql.util.Traverser
import graphql.util.TraverserContext
import graphql.util.TraverserVisitorStub
import graphql.validation.ValidationError
import graphql.validation.ValidationErrorType
import graphql.validation.Validator as GraphQLValidator
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Function
import viaduct.engine.api.EngineSchema
import viaduct.graphql.utils.rawValue

private const val SUBSCRIPTION_DEFER_MESSAGE =
    "The @defer directive is not supported on subscription operations. Disable it with if: false."

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
                validateSubscriptionOperations(document, errors)
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

private data class SubscriptionSelection(
    val selection: Selection<*>,
    val queryPath: List<String>,
    val parentSpreads: List<FragmentSpread>,
    val atRoot: Boolean,
)

/**
 * Subscriptions allow @defer only with if: false or a variable, or when @skip/@include
 * on the selection or an enclosing selection can exclude it.
 * Collects validation errors with query paths so PQV can locate invalid experimental selections.
 * Allowed variable conditions are evaluated later, when the selected operation executes.
 */
private fun validateSubscriptionOperations(
    document: Document,
    errors: MutableList<ValidationError>
) {
    if (errors.size >= GraphQLValidator.getMaxValidationErrors()) return
    val operations = document.getDefinitionsOfType(OperationDefinition::class.java)
        .filter { it.operation == OperationDefinition.Operation.SUBSCRIPTION }
    if (operations.isEmpty()) return
    val fragments = document.getDefinitionsOfType(FragmentDefinition::class.java).associateBy { it.name }
    for (operation in operations) {
        val visitedFragments = mutableSetOf<Pair<String, Boolean>>()
        val pending = ArrayDeque<SubscriptionSelection>()
        pending.addAll(operation.selectionSet.selections.asReversed().map { SubscriptionSelection(it, emptyList(), emptyList(), true) })
        while (pending.isNotEmpty()) {
            val (selection, queryPath, parentSpreads, atRoot) = pending.removeLast()
            val container: DirectivesContainer<*> = when (selection) {
                is Field -> selection
                is InlineFragment -> selection
                is FragmentSpread -> selection
                else -> continue
            }
            if (canBeExcluded(container)) continue
            val defer = container.directives.firstOrNull { it.name == Directives.DeferDirective.name }
            val condition = defer?.getArgument("if")?.value
            // The root rule already reports these directives, but nested selections still need checking.
            if (!atRoot && selection !is Field && defer != null && condition !is VariableReference && !(condition is BooleanValue && !condition.isValue)) {
                errors += ValidationError.newValidationError()
                    .validationErrorType(ValidationErrorType.MisplacedDirective)
                    .sourceLocations(listOfNotNull(defer.sourceLocation) + parentSpreads.mapNotNull { it.sourceLocation })
                    .queryPath(queryPath)
                    .description(SUBSCRIPTION_DEFER_MESSAGE)
                    .build()
                if (errors.size >= GraphQLValidator.getMaxValidationErrors()) return
            }
            var spreads = parentSpreads
            val (children, childPath) = when (selection) {
                is Field -> selection.selectionSet?.selections.orEmpty() to queryPath + selection.name
                is InlineFragment -> selection.selectionSet.selections to queryPath
                is FragmentSpread -> {
                    if (!visitedFragments.add(selection.name to atRoot)) continue
                    val fragment = fragments[selection.name] ?: continue
                    spreads = listOf(selection) + parentSpreads
                    fragment.selectionSet.selections to listOf(fragment.name)
                }
                else -> continue
            }
            pending.addAll(children.asReversed().map { SubscriptionSelection(it, childPath, spreads, atRoot && selection !is Field) })
        }
    }
}

private fun canBeExcluded(container: DirectivesContainer<*>): Boolean =
    container.directives.any { directive ->
        val condition = directive.getArgument("if")?.value
        when (directive.name) {
            Directives.SkipDirective.name -> condition is VariableReference || (condition is BooleanValue && condition.isValue)
            Directives.IncludeDirective.name -> condition is VariableReference || (condition is BooleanValue && !condition.isValue)
            else -> false
        }
    }

/** Reports an enabled subscription defer at the containing object's response path. */
fun subscriptionDeferError(
    directive: Directive,
    path: ResultPath
): GraphQLError =
    GraphqlErrorBuilder.newError()
        .message(SUBSCRIPTION_DEFER_MESSAGE)
        .errorType(ErrorType.DataFetchingException)
        .location(directive.sourceLocation)
        .path(path)
        .build()

/**
 * Objects sharing a field selection and runtime type reuse the decision, including when no defer applies.
 * A different execution ID replaces the cache so reused GraphQL contexts cannot reuse stale decisions.
 */
class SubscriptionDeferChecks private constructor(
    private val executionId: ExecutionId,
) {
    // graphql-java Field nodes use identity equality, keeping separate query selections separate.
    private val decisions = ConcurrentHashMap<Pair<Field, String>, Optional<Directive>>()

    /**
     * Returns an error if any selection enables defer for this object, or null when none does.
     * One matching directive is enough to reject the object; a fresh error preserves each occurrence's path.
     */
    private fun error(
        environment: DataFetchingEnvironment,
        objectType: GraphQLObjectType,
        path: ResultPath,
    ): GraphQLError? {
        for (field in environment.mergedField.fields) {
            val defer = decisions.computeIfAbsent(field to objectType.name) {
                Optional.ofNullable(findEnabledDefer(field, objectType, environment))
            }.orElse(null) ?: continue
            return subscriptionDeferError(defer, path)
        }
        return null
    }

    companion object {
        fun error(
            environment: DataFetchingEnvironment,
            objectTypeName: String,
            path: ResultPath,
        ): GraphQLError? {
            if (environment.operationDefinition?.operation != OperationDefinition.Operation.SUBSCRIPTION) return null
            val checks = requireNotNull(
                environment.graphQlContext.compute<SubscriptionDeferChecks>(SubscriptionDeferChecks::class.java) { _, existing ->
                    existing?.takeIf { it.executionId == environment.executionId }
                        ?: SubscriptionDeferChecks(environment.executionId)
                },
            )
            return checks.error(environment, environment.graphQLSchema.getObjectType(objectTypeName), path)
        }
    }
}

/**
 * Finds the first enabled defer among fragments included for this object's actual type and variables.
 * Stops at child fields, whose objects are checked separately when they reach completion.
 * Only one directive is needed because any enabled defer makes the containing subscription object fail.
 */
private fun findEnabledDefer(
    field: Field,
    objectType: GraphQLObjectType,
    environment: DataFetchingEnvironment,
): Directive? {
    val selections = field.selectionSet?.selections ?: return null
    val schema = environment.graphQLSchema
    val variables = environment.variables
    val conditionalNodes = ConditionalNodes()
    val visitedFragments = mutableSetOf<String>()
    val pending = ArrayDeque<Selection<*>>()
    pending.addAll(selections.asReversed())
    while (pending.isNotEmpty()) {
        val selection = pending.removeLast()
        val container: DirectivesContainer<*>
        val children: List<Selection<*>>
        val typeCondition: String?
        when (selection) {
            is InlineFragment -> {
                container = selection
                children = selection.selectionSet.selections
                typeCondition = selection.typeCondition?.name
            }
            is FragmentSpread -> {
                val fragment = environment.fragmentsByName[selection.name] ?: continue
                container = selection
                children = fragment.selectionSet.selections
                typeCondition = fragment.typeCondition.name
            }
            else -> continue
        }
        if (!conditionalNodes.shouldInclude(container, variables, schema, environment.graphQlContext)) continue
        if (typeCondition != null) {
            val matches = when (val type = schema.getType(typeCondition)) {
                is GraphQLObjectType -> type.name == objectType.name
                is GraphQLCompositeType -> schema.isPossibleType(type, objectType)
                else -> false
            }
            if (!matches) continue
        }
        val defer = container.directives.firstOrNull { it.name == Directives.DeferDirective.name }
        if (defer != null && defer.getArgument("if")?.value?.rawValue(variables) != false) return defer
        // A later spread's own directive must be checked even if its fragment body was already visited.
        if (selection is FragmentSpread && !visitedFragments.add(selection.name)) continue
        pending.addAll(children.asReversed())
    }
    return null
}
