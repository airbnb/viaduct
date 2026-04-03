package viaduct.java.runtime.bridge

import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.future
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.ResolveSelectionSetOptions
import viaduct.java.api.context.FieldExecutionContext
import viaduct.java.api.context.SelectiveFieldExecutionContext
import viaduct.java.api.resolvers.FieldResolverBase
import viaduct.java.api.types.Arguments
import viaduct.java.api.types.CompositeOutput
import viaduct.java.api.types.GraphQLObject
import viaduct.java.api.types.Query

// Internal marker type for the selections type parameter
object AnySelections : CompositeOutput

/**
 * Minimal implementation of FieldExecutionContext for Java resolvers.
 *
 * Bridges the engine's untyped data (argument maps) to the Java API's typed interfaces.
 * Arguments are populated from the engine's argument map using reflection on the Arguments class.
 *
 * Uses [Arguments] directly as the generic argument type so that any concrete Arguments
 * subtype (e.g., Query_person_Arguments) can be returned without a ClassCastException.
 * Callers access getArguments() through the erased interface and cast to their specific type.
 *
 * @param requestContext The request context from the engine
 * @param arguments The typed Arguments instance (populated from the engine's argument map), or null
 * @param objectValue The parent object value (e.g., a Person instance for a Person.fullAddress resolver), or null
 * @param queryValue The query root value (populated from the queryValueFragment result), or null
 * @param engineExecutionContext The engine execution context, required for ctx.query() and ctx.mutation()
 * @param coroutineScope The coroutine scope for launching subquery coroutines, required for ctx.query() and ctx.mutation()
 */
@Suppress("UNCHECKED_CAST", "TooManyFunctions")
class SimpleFieldExecutionContext(
    private val requestContext: Any?,
    private val arguments: Arguments? = null,
    private val objectValue: Any? = null,
    private val queryValue: Any? = null,
    private val engineExecutionContext: EngineExecutionContext? = null,
    private val coroutineScope: CoroutineScope? = null,
) : FieldExecutionContext<GraphQLObject, Query, Arguments, AnySelections>,
    SelectiveFieldExecutionContext<AnySelections>,
    FieldResolverBase.Context<GraphQLObject, Query, Arguments, AnySelections> {
    override fun getObjectValue(): GraphQLObject {
        if (objectValue != null) {
            return objectValue as GraphQLObject
        }
        throw UnsupportedOperationException(
            "Object value not available. Ensure the resolver declares an objectValueFragment."
        )
    }

    override fun getQueryValue(): Query {
        if (queryValue != null) {
            return queryValue as Query
        }
        throw UnsupportedOperationException(
            "Query value access not yet implemented for Java resolvers"
        )
    }

    override fun getArguments(): Arguments {
        return arguments ?: Arguments.NoArguments
    }

    override fun getSelections(): Any {
        throw UnsupportedOperationException(
            "Selections access not yet implemented for Java resolvers"
        )
    }

    override fun getRequestContext(): Any? = requestContext

    override fun <T : viaduct.java.api.types.NodeCompositeOutput> globalIDFor(
        type: viaduct.java.api.reflect.Type<T>,
        internalID: String
    ): viaduct.java.api.globalid.GlobalID<T> {
        throw UnsupportedOperationException(
            "globalIDFor not yet implemented for Java resolvers"
        )
    }

    override fun <T : viaduct.java.api.types.NodeCompositeOutput> serialize(globalID: viaduct.java.api.globalid.GlobalID<T>): String {
        throw UnsupportedOperationException(
            "serialize not yet implemented for Java resolvers"
        )
    }

    override fun <T : viaduct.java.api.types.NodeCompositeOutput> nodeFor(id: viaduct.java.api.globalid.GlobalID<T>): T {
        throw UnsupportedOperationException(
            "nodeFor not yet implemented for Java resolvers"
        )
    }

    override fun <T : Any> query(
        selections: String,
        variables: Map<String, Any?>,
        targetClass: Class<T>
    ): CompletableFuture<T> {
        val engineCtx = engineExecutionContext ?: throw UnsupportedOperationException(
            "ctx.query() requires engineExecutionContext. Ensure the resolver is running within a live execution context."
        )
        val scope = coroutineScope ?: throw UnsupportedOperationException(
            "ctx.query() requires a coroutineScope."
        )
        return scope.future {
            val queryTypeName = engineCtx.activeSchema.schema.queryType.name
            val selectionSet = engineCtx.engineSelectionSetFactory.engineSelectionSet(
                queryTypeName,
                selections,
                variables
            )
            val result = engineCtx.resolveSelectionSet(selectionSet, ResolveSelectionSetOptions.DEFAULT)
            // TODO(RFC-265): Wrap result in wrapFrameworkErrors to properly attribute subquery errors
            @Suppress("UNCHECKED_CAST")
            convertAsyncEngineDataToJavaObject(targetClass, result) as T
        }
    }

    override fun <T : Any> mutation(
        selections: String,
        variables: Map<String, Any?>,
        targetClass: Class<T>
    ): CompletableFuture<T> {
        val engineCtx = engineExecutionContext ?: throw UnsupportedOperationException(
            "ctx.mutation() requires engineExecutionContext. Ensure the resolver is running within a live execution context."
        )
        val scope = coroutineScope ?: throw UnsupportedOperationException(
            "ctx.mutation() requires a coroutineScope."
        )
        return scope.future {
            val mutationType = engineCtx.activeSchema.schema.mutationType
                ?: throw UnsupportedOperationException(
                    "ctx.mutation() is not available: the schema has no Mutation type."
                )
            val selectionSet = engineCtx.engineSelectionSetFactory.engineSelectionSet(
                mutationType.name,
                selections,
                variables
            )
            val result = engineCtx.resolveSelectionSet(selectionSet, ResolveSelectionSetOptions.MUTATION)
            // TODO(RFC-265): Wrap result in wrapFrameworkErrors to properly attribute subquery errors
            @Suppress("UNCHECKED_CAST")
            convertAsyncEngineDataToJavaObject(targetClass, result) as T
        }
    }
}
