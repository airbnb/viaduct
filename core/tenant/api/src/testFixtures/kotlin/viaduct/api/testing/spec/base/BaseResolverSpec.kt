package viaduct.api.testing.spec.base

import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor
import viaduct.api.internal.InternalContext
import viaduct.api.internal.select.SelectionSetFactory
import viaduct.api.mocks.PrebakedResults
import viaduct.api.mocks.PrebakedRootFieldRefResults
import viaduct.api.reflect.RootObjectField
import viaduct.api.reflect.Type
import viaduct.api.select.SelectionSet
import viaduct.api.testing.types.MutationForSelection
import viaduct.api.testing.types.QueryForSelection
import viaduct.api.testing.types.RootFieldRefStub
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Mutation
import viaduct.api.types.Object
import viaduct.api.types.Query
import viaduct.apiannotations.ExperimentalApi
import viaduct.apiannotations.InternalApi
import viaduct.tenant.runtime.select.SelectionSetImpl

/**
 * Base input carrier with fields common to all resolver inputs.
 * Mirrors [viaduct.api.context.ExecutionContext] in the hierarchy.
 *
 * Subclasses are responsible for building their own execution context via
 * createContext / createContexts, receiving [InternalContext] and
 * [SelectionSetFactory] from ResolverTestBase — which keeps ResolverTestBase
 * free of per-resolver context-construction logic.
 */
@ExperimentalApi
@Suppress("USELESS_CAST")
abstract class BaseResolverSpec {
    var requestContext: Any? = null
    var contextQueryValues: List<Query> = emptyList()

    /**
     * Pre-baked return values for `ctx.rootFieldRef(field, args)` calls made by the
     * resolver under test. Each [RootFieldRefStub] declares the field, the arguments
     * the resolver is expected to invoke it with, and the value to return.
     *
     * Lookups match on the exact `(field, arguments)` pair via structural equality
     * on [Arguments]. If multiple stubs share the same key, the last one wins. Calls
     * with no matching stub throw.
     */
    var rootFieldRefValues: List<RootFieldRefStub<*, *>> = emptyList()

    @OptIn(InternalApi::class)
    protected fun buildQueryResultsMap(
        internalContext: InternalContext,
        selectionSetFactory: SelectionSetFactory,
    ): PrebakedResults<Query> = buildContextQueryMap(contextQueryValues, internalContext, selectionSetFactory)

    protected fun buildRootFieldRefResults(): PrebakedRootFieldRefResults = rootFieldRefResultsOf(rootFieldRefValues)

    @OptIn(InternalApi::class)
    protected inline fun <reified C : Any> getResolverContextKClass(resolverClass: Class<*>): KClass<out C> {
        val contextClass = resolverClass.classes.firstOrNull { it.simpleName == "Context" }
            ?: throw IllegalArgumentException(
                "Expected resolver (${resolverClass.kotlin.qualifiedName}) to contain a nested class called 'Context', but none was found."
            )
        @Suppress("UNCHECKED_CAST")
        return contextClass.kotlin as? KClass<out C>
            ?: throw IllegalArgumentException(
                "Expected resolver (${resolverClass.kotlin.qualifiedName}) Context class " +
                    "(${contextClass.kotlin.qualifiedName}) to implement ${C::class.simpleName}."
            )
    }

    protected fun <C : Any> KClass<out C>.wrapOrReturn(innerCtx: C): C = primaryConstructor?.call(innerCtx) ?: innerCtx
}

internal const val BLANK_CONTEXT_QUERY_SELECTION_KEY = "NoSelections"

@OptIn(InternalApi::class)
internal fun buildContextQueryMap(
    contextQueries: List<Query>,
    internalContext: InternalContext,
    selectionSetFactory: SelectionSetFactory,
): PrebakedResults<Query> =
    buildContextMap(
        contextValues = contextQueries,
        rootTypeName = internalContext.schema.schema.queryType.name,
        typeName = "Query",
        wrapperClass = QueryForSelection::class,
        extractSelection = { (it as QueryForSelection).selections },
        extractValue = { (it as QueryForSelection).query },
        internalContext = internalContext,
        selectionSetFactory = selectionSetFactory,
    )

@OptIn(InternalApi::class)
internal fun buildContextMutationMap(
    contextMutations: List<Mutation>,
    internalContext: InternalContext,
    selectionSetFactory: SelectionSetFactory,
): PrebakedResults<Mutation> =
    buildContextMap(
        contextValues = contextMutations,
        rootTypeName = internalContext.schema.schema.mutationType.name,
        typeName = "Mutation",
        wrapperClass = MutationForSelection::class,
        extractSelection = { (it as MutationForSelection).selections },
        extractValue = { (it as MutationForSelection).mutation },
        internalContext = internalContext,
        selectionSetFactory = selectionSetFactory,
    )

@OptIn(InternalApi::class)
private fun <T : CompositeOutput> buildContextMap(
    contextValues: List<T>,
    rootTypeName: String,
    typeName: String,
    wrapperClass: KClass<*>,
    extractSelection: (T) -> String,
    extractValue: (T) -> T,
    internalContext: InternalContext,
    selectionSetFactory: SelectionSetFactory,
): PrebakedResults<T> {
    if (contextValues.isEmpty()) return prebakedResultsOf(emptyMap())

    if (contextValues.count { !wrapperClass.isInstance(it) } > 1) {
        throw IllegalArgumentException(
            "Cannot have multiple $typeName values in context${typeName}s. " +
                "Please provide a single $typeName or use ${wrapperClass.simpleName} to distinguish results."
        )
    }

    @Suppress("UNCHECKED_CAST")
    val rootType = internalContext.reflectionLoader.reflectionFor(rootTypeName) as Type<T>
    val resultMap = contextValues.associate { rawValue ->
        if (wrapperClass.isInstance(rawValue)) {
            val selSet = selectionSetFactory.selectionsOn(rootType, extractSelection(rawValue), emptyMap())
            createSelectionSetKey(selSet) to extractValue(rawValue)
        } else {
            BLANK_CONTEXT_QUERY_SELECTION_KEY to rawValue
        }
    }
    return prebakedResultsOf(resultMap)
}

internal fun createSelectionSetKey(selectionSet: SelectionSet<*>): String {
    return when (selectionSet) {
        is SelectionSetImpl -> selectionSet.engineSelectionSet.printAsFieldSet()
        SelectionSet.NoSelections -> BLANK_CONTEXT_QUERY_SELECTION_KEY
        else -> error("Unexpected SelectionSet type: ${selectionSet::class.qualifiedName}")
    }
}

/**
 * Builds a stateless [PrebakedRootFieldRefResults] from a list of [RootFieldRefStub]s.
 *
 * Lookup keys on the exact `(pathFromQueryRoot, arguments)` pair via structural
 * equality on [Arguments]. When multiple stubs share the same key, the last one
 * wins — the same precedence rule as a Kotlin `Map` built from the list. Calls
 * with no matching stub throw.
 */
fun rootFieldRefResultsOf(values: List<RootFieldRefStub<*, *>>): PrebakedRootFieldRefResults {
    data class Key(val path: List<String>, val arguments: Arguments)
    val byKey: Map<Key, Object> = values.associate { stub ->
        Key(stub.field.pathFromQueryRoot, stub.arguments) to stub.value
    }
    return object : PrebakedRootFieldRefResults {
        @Suppress("UNCHECKED_CAST")
        override fun <A : Arguments, T : Object> get(
            field: RootObjectField<*, T, A>,
            arguments: A
        ): T {
            val key = Key(field.pathFromQueryRoot, arguments)
            return (byKey[key] as? T)
                ?: throw IllegalArgumentException(
                    "No pre-baked rootFieldRef result for path '${key.path.joinToString(".")}' " +
                        "with arguments $arguments. " +
                        "Available stubs: ${byKey.keys.map { "${it.path.joinToString(".")} (args=${it.arguments})" }}"
                )
        }
    }
}

internal fun <T : CompositeOutput> prebakedResultsOf(results: Map<String, T>) =
    object : PrebakedResults<T> {
        override fun get(selections: SelectionSet<T>): T {
            val key = createSelectionSetKey(selections)
            @Suppress("UNCHECKED_CAST", "USELESS_CAST")
            return (results[key] ?: results[BLANK_CONTEXT_QUERY_SELECTION_KEY])?.let { it as T }
                ?: throw IllegalArgumentException(
                    "No mocked results provided for selections: '$key'. Available keys: ${results.keys}"
                )
        }
    }
