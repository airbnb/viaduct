package viaduct.java.runtime.bridge

import graphql.language.FragmentDefinition
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.future
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.NodeReference
import viaduct.engine.api.ResolveSelectionSetOptions
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.parse.CachedDocumentParser
import viaduct.errors.FrameworkException
import viaduct.errors.TenantUsageException
import viaduct.errors.handleFrameworkErrorsSuspend
import viaduct.graphql.utils.ParsedSelections
import viaduct.graphql.utils.SelectionsParserUtils
import viaduct.java.api.globalid.GlobalID
import viaduct.java.api.internal.InternalContext
import viaduct.java.api.internal.ResolverClassFinder
import viaduct.java.api.reflect.Type
import viaduct.java.api.types.NodeCompositeOutput
import viaduct.java.api.types.NodeObject
import viaduct.service.api.spi.GlobalIDCodec

/**
 * Shared implementation of the engine-context operations that every Java bridge execution context
 * needs.
 *
 * The three Java execution contexts ([SimpleFieldExecutionContext], [SimpleNodeExecutionContext],
 * [SimpleVariablesProviderContext]) all wrap the same engine handles — a nullable
 * [EngineExecutionContext], a [ResolverClassFinder], and (for the ones that support subqueries) a
 * [CoroutineScope]. This delegate holds the [InternalContext] surface plus `query`/`mutation`/`nodeRef`
 * once; the contexts forward to it while keeping their own public, typed API shapes.
 *
 * The "requires engineExecutionContext" [FrameworkException] messages are part of the contract:
 * callers and tests rely on them.
 *
 * @param engineExecutionContext the engine execution context, or null outside a live execution
 * @param classFinder resolves GRT classes by type name; may be null outside a live execution
 * @param coroutineScope scope for launching subquery coroutines; only required by [query]/[mutation]
 */
@Suppress("UNCHECKED_CAST")
internal class JavaEngineContextDelegate(
    private val engineExecutionContext: EngineExecutionContext? = null,
    private val classFinder: ResolverClassFinder? = null,
    private val coroutineScope: CoroutineScope? = null,
    private val knownFragments: Map<String, FragmentDefinition> = emptyMap(),
) {
    private fun requireEngineContext(operation: String): EngineExecutionContext =
        engineExecutionContext
            ?: throw FrameworkException("$operation requires engineExecutionContext.")

    // ── InternalContext surface ──

    fun getSchema(): ViaductSchema = requireEngineContext("getSchema()").fullSchema

    fun getGlobalIDCodec(): GlobalIDCodec = requireEngineContext("getGlobalIDCodec()").globalIDCodec

    fun getClassFinder(): ResolverClassFinder = classFinder ?: throw FrameworkException("getClassFinder() requires classFinder.")

    fun <T : NodeCompositeOutput> deserializeGlobalID(serialized: String): GlobalID<T> {
        val codec = requireEngineContext("deserializeGlobalID").globalIDCodec
        val (typeName, internalId) = try {
            codec.deserialize(serialized)
        } catch (e: IllegalArgumentException) {
            throw TenantUsageException("Invalid GlobalID: \"$serialized\"", e)
        }
        return GlobalIDImpl(type = typeFromName(typeName), internalId = internalId)
    }

    fun <T : NodeCompositeOutput> globalIDFor(
        type: Type<T>,
        internalID: String,
    ): GlobalID<T> = requireEngineContext("globalIDFor").globalIDCodec.createGlobalID(type, internalID)

    fun <T : NodeCompositeOutput> serialize(globalID: GlobalID<T>): String = requireEngineContext("serialize").globalIDCodec.serializeGlobalID(globalID)

    fun <T : NodeObject> globalIDStringFor(
        type: Type<T>,
        internalID: String,
    ): String = requireEngineContext("globalIDStringFor").globalIDCodec.serialize(type.name, internalID)

    // ── Node reference ──

    /**
     * Build a node-reference GRT for [id], instantiating [grtClass] with the per-request
     * [InternalContext] and a [NodeReference].
     *
     * The concrete GRT class is supplied by the caller because the two callers resolve it
     * differently: the field context prefers the [ResolverClassFinder] (falling back to the GlobalID
     * type's Java class), while the node context uses the GlobalID type's Java class directly.
     */
    fun <T : NodeCompositeOutput> nodeRef(
        id: GlobalID<T>,
        grtClass: Class<T>,
    ): T {
        val engineCtx = requireEngineContext("nodeRef")
        val typeName = id.getType().name
        val serializedId = engineCtx.globalIDCodec.serializeGlobalID(id)
        val graphqlType = engineCtx.activeSchema.schema.getObjectType(typeName)
            ?: throw FrameworkException("GraphQL type '$typeName' not found in schema for nodeRef.")
        val nodeReference = engineCtx.createNodeReference(serializedId, graphqlType)
        val internalContext = classFinder?.let { buildInternalContext(engineCtx, it) }
        return grtClass
            .getDeclaredConstructor(InternalContext::class.java, NodeReference::class.java)
            .newInstance(internalContext, nodeReference) as T
    }

    // ── Subquery execution ──

    fun <T : Any> query(
        selections: String,
        variables: Map<String, Any?>,
        targetClass: Class<T>,
    ): CompletableFuture<T> =
        subquery("ctx.query()", "query", selections, variables, targetClass, ResolveSelectionSetOptions.DEFAULT) { engineCtx ->
            engineCtx.activeSchema.schema.queryType.name
        }

    fun <T : Any> mutation(
        selections: String,
        variables: Map<String, Any?>,
        targetClass: Class<T>,
    ): CompletableFuture<T> =
        subquery("ctx.mutation()", "mutation", selections, variables, targetClass, ResolveSelectionSetOptions.MUTATION) { engineCtx ->
            engineCtx.activeSchema.schema.mutationType?.name
                ?: throw FrameworkException("ctx.mutation() is not available: the schema has no Mutation type.")
        }

    fun <T : Any> queryOperation(
        operationText: String,
        variables: Map<String, Any?>,
        targetClass: Class<T>,
    ): CompletableFuture<T> =
        subquery(
            "ctx.query()",
            "query",
            operationText,
            variables,
            targetClass,
            ResolveSelectionSetOptions.DEFAULT,
            isOperation = true,
        ) { engineCtx ->
            engineCtx.activeSchema.schema.queryType.name
        }

    fun <T : Any> mutationOperation(
        operationText: String,
        variables: Map<String, Any?>,
        targetClass: Class<T>,
    ): CompletableFuture<T> =
        subquery(
            "ctx.mutation()",
            "mutation",
            operationText,
            variables,
            targetClass,
            ResolveSelectionSetOptions.MUTATION,
            isOperation = true,
        ) { engineCtx ->
            engineCtx.activeSchema.schema.mutationType?.name
                ?: throw FrameworkException("ctx.mutation() is not available: the schema has no Mutation type.")
        }

    /**
     * Shared body for [query] and [mutation]: launch a coroutine that resolves the operation root
     * type's selection set with the given options and converts the engine result into a typed Java
     * object. [rootTypeName] computes the operation root type from the engine context (and may itself
     * throw, e.g. when there is no Mutation type).
     */
    private fun <T : Any> subquery(
        requireContextLabel: String,
        errorLabel: String,
        selections: String,
        variables: Map<String, Any?>,
        targetClass: Class<T>,
        options: ResolveSelectionSetOptions,
        isOperation: Boolean = false,
        rootTypeName: (EngineExecutionContext) -> String,
    ): CompletableFuture<T> {
        val engineCtx = engineExecutionContext
            ?: throw FrameworkException(
                "$requireContextLabel requires engineExecutionContext. Ensure the resolver is running within a live execution context."
            )
        val scope = coroutineScope
            ?: throw FrameworkException("$requireContextLabel requires a coroutineScope.")
        return scope.future {
            handleFrameworkErrorsSuspend(errorLabel) {
                val typeName = rootTypeName(engineCtx)
                val normalizedVariables = JavaTenantApiInputValueNormalizer.normalizeVariablesForEngine(variables, engineCtx)
                val selectionSet = if (isOperation) {
                    engineCtx.engineSelectionSetFactory.engineSelectionSet(
                        parseSelfContained(typeName, selections),
                        normalizedVariables,
                    )
                } else {
                    engineCtx.engineSelectionSetFactory.engineSelectionSet(typeName, selections, normalizedVariables)
                }
                val result = engineCtx.resolveSelectionSet(selectionSet, options)
                convertSyncEngineDataToJavaObject(
                    targetClass,
                    result,
                    classFinder?.let { buildInternalContext(engineCtx, it) }
                ) as T
            }
        }
    }

    private fun parseSelfContained(
        typeName: String,
        operationText: String,
    ): ParsedSelections {
        val normalized = SelectionsParserUtils.normalizeToFragmentDocument(
            operationText,
            typeName,
            CachedDocumentParser::parseDocument,
        )
        val selfContained = SelectionsParserUtils.inlineReachableFragments(normalized, knownFragments)
        return ParsedSelections.fromDocument(typeName, selfContained)
    }
}
