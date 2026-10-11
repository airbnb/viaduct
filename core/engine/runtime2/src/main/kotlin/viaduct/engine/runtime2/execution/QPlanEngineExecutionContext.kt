package viaduct.engine.runtime2.execution

import graphql.schema.GraphQLObjectType
import viaduct.engine.api.Engine
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.NodeEngineObjectData
import viaduct.engine.api.NodeReference
import viaduct.engine.api.ResolveSelectionSetOptions
import viaduct.engine.api.RootFieldReference
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.runtime.DispatcherExecutionContext
import viaduct.engine.runtime.FieldDataLoader
import viaduct.engine.runtime.NodeDataLoader
import viaduct.engine.runtime2.model.RootFieldReferenceData
import viaduct.engine.runtime2.model.RootFieldReferenceDataCarrier
import viaduct.engine.runtime2.model.nodeRootFieldReferenceOf
import viaduct.engine.runtime2.model.registry.ResolutionExecutionContext
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.schema.SourceSchemaAdapter
import viaduct.engine.runtime2.schema.ViaductAndGJSchema
import viaduct.engine.runtime2.schema.fragmentFromDocument
import viaduct.engine.runtime2.schema.mutationSelections
import viaduct.errors.UnsetFieldException
import viaduct.graphql.schema.ViaductSchema

/** Engine API facade whose selection execution is owned by the active Resolution field task. */
internal class QPlanEngineExecutionContext(
    private val delegate: EngineExecutionContext,
    private val schemas: ViaductAndGJSchema,
    private val resolutionContext: ResolutionExecutionContext,
) : EngineExecutionContext by delegate,
    DispatcherExecutionContext {
    override val executionHandle: EngineExecutionContext.ExecutionHandle by lazy {
        Runtime2ExecutionHandle(delegate.engine, schemas, resolutionContext)
    }

    override fun fieldDataLoader(resolver: FieldResolverExecutor): FieldDataLoader =
        requireNotNull(delegate as? DispatcherExecutionContext) {
            "Dispatcher invocation requires a DispatcherExecutionContext"
        }.fieldDataLoader(resolver)

    override fun nodeDataLoader(resolver: NodeResolverExecutor): NodeDataLoader =
        requireNotNull(delegate as? DispatcherExecutionContext) {
            "Dispatcher invocation requires a DispatcherExecutionContext"
        }.nodeDataLoader(resolver)

    override suspend fun resolveSelectionSet(
        selectionSet: EngineSelectionSet,
        options: ResolveSelectionSetOptions,
    ): EngineObjectData.Sync {
        return (executionHandle as Runtime2ExecutionHandle).resolveSelectionSet(selectionSet, options)
    }

    override fun createNodeReference(
        id: String,
        graphQLObjectType: GraphQLObjectType,
    ): NodeReference = runtime2NodeReferenceOf(schemas.loweredSchema, id, graphQLObjectType)

    override fun createRootFieldReference(
        rootFieldPath: List<String>,
        type: GraphQLObjectType,
        args: Map<String, Any?>,
    ): RootFieldReference = runtime2RootFieldReferenceOf(schemas.loweredSchema, rootFieldPath, type, args)
}

internal fun runtime2NodeReferenceOf(
    schema: ViaductSchema,
    id: String,
    type: GraphQLObjectType,
): NodeReference =
    Runtime2NodeReference(
        id = id,
        type = type,
        referenceData = nodeRootFieldReferenceOf(
            queryNode = schema.requireObjectField("Query", "node"),
            type = schema.requireType(type.name) as ViaductSchema.Object,
            id = id,
        ),
    )

internal fun runtime2RootFieldReferenceOf(
    schema: ViaductSchema,
    rootFieldPath: List<String>,
    type: GraphQLObjectType,
    args: Map<String, Any?>,
): RootFieldReference =
    Runtime2RootFieldReference(
        rootFieldPath = rootFieldPath.toList(),
        type = type,
        args = args.toMap(),
        referenceData = SourceSchemaAdapter(schema).lowerRootFieldReference(rootFieldPath, type.name, args),
    )

private class Runtime2NodeReference(
    override val id: String,
    override val type: GraphQLObjectType,
    override val referenceData: RootFieldReferenceData,
) : NodeReference, NodeEngineObjectData, RootFieldReferenceDataCarrier {
    override suspend fun fetch(selection: String): Any? {
        if (selection == "id") return id
        throw UnsetFieldException(selection, type, "Only id can be read on an unresolved node reference")
    }

    override suspend fun fetchOrNull(selection: String): Any? = if (selection == "id") id else null

    override suspend fun fetchSelections(): Iterable<String> = listOf("id")
}

private class Runtime2RootFieldReference(
    override val rootFieldPath: List<String>,
    override val type: GraphQLObjectType,
    override val args: Map<String, Any?>,
    override val referenceData: RootFieldReferenceData,
) : RootFieldReference, EngineObjectData, RootFieldReferenceDataCarrier {
    override suspend fun fetch(selection: String): Any? = throw UnsetFieldException(selection, type, "Fields cannot be read on an unresolved root-field reference")

    override suspend fun fetchOrNull(selection: String): Any? = null

    override suspend fun fetchSelections(): Iterable<String> = emptyList()
}

/** Invocation-local capability used by [Engine] nested-execution methods. */
internal class Runtime2ExecutionHandle(
    val engine: Engine,
    private val schemas: ViaductAndGJSchema,
    private val resolutionContext: ResolutionExecutionContext,
) : EngineExecutionContext.ExecutionHandle {
    suspend fun resolveSelectionSet(
        selectionSet: EngineSelectionSet,
        options: ResolveSelectionSetOptions,
    ): EngineObjectData.Sync {
        val rootType = when (options.operationType) {
            Engine.OperationType.QUERY -> schemas.loweredSchema.requireQueryTypeDef()
            Engine.OperationType.MUTATION -> requireNotNull(schemas.loweredSchema.mutationTypeDef) {
                "Cannot execute mutation selections without a Mutation root"
            }
        }
        require(selectionSet.type == rootType.name) {
            "Cannot execute selections with type ${selectionSet.type} on schema root type ${rootType.name}"
        }
        val fragment =
            schemas.fragmentFromDocument(
                document = selectionSet.toFragment().parsedDocument,
                bindings = selectionSet.variables,
            )
        val selections = fragment.materializeSelections
        return when (options.operationType) {
            Engine.OperationType.QUERY -> resolutionContext.resolveSelectionSet(selections)
            Engine.OperationType.MUTATION -> resolutionContext.resolveMutationSelectionSet(
                selections,
                selections.mutationSelections(schemas.loweredSchema, rootType),
            )
        }
    }
}
