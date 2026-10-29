package viaduct.engine.runtime2.execution

import graphql.execution.ExecutionContext
import graphql.language.Document
import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.FragmentSpread
import graphql.language.InlineFragment
import graphql.language.OperationDefinition
import graphql.language.SelectionSet
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import viaduct.apiannotations.InternalApi
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.registry.Assumptions
import viaduct.engine.runtime2.model.registry.ResolverRegistry

/**
 * Engine2's operation plan cache. The validated GraphQL [Document] instance returned by the
 * service's document cache is the primary key; each eligible query operation keeps its decoded
 * selection forest. Resolution then attaches its demand analyses to that forest and to the forests
 * derived from it, so a repeated request reuses the whole plan by identity.
 *
 * Decoding resolves argument literals and operation variables, so a forest is exact only for its
 * coerced variable values. Operations without variable definitions keep one forest; others keep a
 * small least-recently-used set keyed by the coerced values. Entries are weakly keyed by document
 * and disappear when the document cache evicts the document.
 *
 * Eligibility follows the first plan-cache step: query operations without `@skip` or `@include` in
 * the operation or any fragment definition of its document. Other operations decode per request.
 */
@InternalApi
class OperationSelectionCache {
    private val documents: MutableMap<Document, DocumentPlans> = Collections.synchronizedMap(WeakHashMap())

    fun selections(
        world: Assumptions,
        executionContext: ExecutionContext,
        decode: () -> SelectionForest,
    ): SelectionForest {
        val operation = executionContext.operationDefinition
        if (operation.operation != OperationDefinition.Operation.QUERY) return decode()
        val document = executionContext.document
        val plans = synchronized(documents) { documents.getOrPut(document) { DocumentPlans(document) } }
        val plan = plans.plan(OperationPlanKey(operation, world.resolverRegistry, executionContext.locale))
        if (!plan.eligible) return decode()
        return plan.selections(executionContext.coercedVariables.toMap(), decode)
    }
}

/** Per-document plans; keys compare AST nodes and registries by identity, as neither defines equality. */
private data class OperationPlanKey(
    val operation: OperationDefinition,
    val registry: ResolverRegistry,
    val locale: Locale?,
)

private class DocumentPlans(document: Document) {
    private val conditionFree = document.definitions.none { definition ->
        when (definition) {
            is OperationDefinition -> definition.selectionSet.hasConditionalDirective()
            is FragmentDefinition -> definition.selectionSet.hasConditionalDirective()
            else -> false
        }
    }
    private val plans = ConcurrentHashMap<OperationPlanKey, OperationPlan>()

    /** Bounded so per-invocation registries that are not drawn from a fixed set cannot grow one document. */
    fun plan(key: OperationPlanKey): OperationPlan =
        plans[key] ?: if (plans.size >= MAX_OPERATION_PLANS) {
            UNCACHED_PLAN
        } else {
            plans.computeIfAbsent(key) { OperationPlan(eligible = conditionFree, variableFree = key.operation.variableDefinitions.isEmpty()) }
        }

    private companion object {
        const val MAX_OPERATION_PLANS = 32
        val UNCACHED_PLAN = OperationPlan(eligible = false, variableFree = false)
    }
}

private class OperationPlan(
    val eligible: Boolean,
    private val variableFree: Boolean,
) {
    @Volatile private var variableFreeSelections: SelectionForest? = null
    private val selectionsByVariables =
        object : LinkedHashMap<Map<String, Any?>, SelectionForest>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Map<String, Any?>, SelectionForest>): Boolean = size > MAX_VARIABLE_PLANS
        }

    fun selections(
        variables: Map<String, Any?>,
        decode: () -> SelectionForest,
    ): SelectionForest {
        if (variableFree) {
            variableFreeSelections?.let { return it }
            // Concurrent first requests may each decode; the first published forest wins.
            return synchronized(this) { variableFreeSelections ?: decode().also { variableFreeSelections = it } }
        }
        synchronized(selectionsByVariables) { selectionsByVariables[variables] }?.let { return it }
        val decoded = decode()
        return synchronized(selectionsByVariables) { selectionsByVariables.getOrPut(variables) { decoded } }
    }

    private companion object {
        const val MAX_VARIABLE_PLANS = 16
    }
}

private fun SelectionSet.hasConditionalDirective(): Boolean =
    selections.any { selection ->
        when (selection) {
            is Field -> selection.hasConditionalDirective() || selection.selectionSet?.hasConditionalDirective() == true
            is InlineFragment -> selection.hasConditionalDirective() || selection.selectionSet.hasConditionalDirective()
            is FragmentSpread -> selection.hasConditionalDirective()
            else -> false
        }
    }

private fun graphql.language.DirectivesContainer<*>.hasConditionalDirective(): Boolean = directives.any { it.name == "skip" || it.name == "include" }
