package execution

import graphql.GraphQLContext
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLTypeUtil
import java.util.Locale
import model.Arguments
import model.EngineErrorData
import model.Fragment
import model.ResolverOutputData
import model.RootFieldReferenceData
import model.emptyFragmentOf
import model.engineObjectDataOf
import model.lowering.SourceSchemaAdapter
import model.lowering.ViaductAndGJSchema
import model.parsing.fragmentFromDocument
import model.registry.FieldResolverDefinition
import model.registry.NodeResolverFunction
import model.registry.SelectiveFieldResolverFunction
import model.registry.VariableDeclaration
import model.registry.fieldResolverOf
import model.registry.nodeResolverOf
import model.registry.selectionAwareFieldResolverOf
import model.registry.selectiveFieldResolverOf
import model.registry.selectiveNodeResolverOf
import model.requireQueryTypeDef
import model.requireType
import viaduct.engine.api.Coordinate
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.NodeReference
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.api.RootFieldReference
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.FieldSelectivityProvider
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.graphql.schema.ViaductSchema as QPlanSchema

class ExecutorRegistryInputs(
    val fieldResolvers: Map<QPlanSchema.Field, FieldResolverDefinition>,
    val nodeResolvers: Map<QPlanSchema.Object, NodeResolverFunction>,
    val variableProviders: Map<Arguments.Variable, VariableDeclaration>,
)

/** Returned resolver functions retain [context]; do not share them across independent request contexts. */
fun executorRegistryInputs(
    fullSchema: EngineSchema,
    schemas: ViaductAndGJSchema,
    fieldExecutors: Iterable<Pair<Coordinate, FieldResolverExecutor>>,
    nodeExecutors: Iterable<Pair<String, NodeResolverExecutor>>,
    context: EngineExecutionContext,
    fieldSelectivityProvider: FieldSelectivityProvider = FieldSelectivityProvider.Never,
    includeDefaultQueryNodeResolvers: Boolean = true,
): ExecutorRegistryInputs {
    val fieldResolverExecutors = fieldExecutors.toList()
    val nodeResolverExecutors = nodeExecutors.toList()
    validateExecutorRegistrations(fieldResolverExecutors, nodeResolverExecutors)
    val schema = schemas.loweredSchema
    val sourceSchema = SourceSchemaAdapter(schema)
    val variableProviders = linkedMapOf<Arguments.Variable, VariableDeclaration>()
    val supplied =
        fieldResolverExecutors.associate { (coordinate, executor) ->
            val field =
                sourceSchema.field(coordinate.first, coordinate.second)
                    as? QPlanSchema.ObjectField
                    ?: throw IllegalArgumentException(
                        "Field executor ${coordinate.render()} does not map to a concrete object field",
                    )
            val sourceField =
                requireNotNull(fullSchema.schema.getObjectType(coordinate.first))
                    .getFieldDefinition(coordinate.second)
            val objectFragment = executor.objectFragment(schemas, field)
            val queryFragment = executor.queryFragment(schemas, field)
            val variables =
                executor.compileVariableDeclarations(
                    schema = schemas,
                    field = field,
                    objectFragment = objectFragment,
                    queryFragment = queryFragment,
                    context = context,
                )
            variables.declarations
                .forEach { (variable, declaration) ->
                    require(variableProviders.put(variable, declaration) == null) {
                        "Duplicate variable provider \$${variable.variableName} for ${coordinate.render()}"
                    }
                }

            suspend fun invokeExecutor(
                input: EngineObjectData.Sync,
                queryValue: EngineObjectData.Sync,
                arguments: Arguments.Resolved,
                selections: EngineSelectionSet?,
                executorContext: EngineExecutionContext,
            ): ResolverOutputData? {
                val selector =
                    FieldResolverExecutor.Selector(
                        arguments = arguments.fieldValues,
                        selections = selections,
                        syncObjectValueGetter = { input },
                        syncQueryValueGetter = { queryValue },
                    )
                val output =
                    executor.batchResolve(listOf(selector), executorContext)[selector]
                        ?: Result.failure(
                            IllegalStateException(
                                "Field executor ${coordinate.render()} omitted its selector",
                            ),
                        )
                return output.fold(
                    onSuccess = { normalizeSourceOutput(sourceField.type, it, sourceSchema) },
                    onFailure = { EngineErrorData.of(it) },
                )
            }
            val isSelective =
                executor.isSelective || fieldSelectivityProvider.isSelective(coordinate)
            val resolverFunction: SelectiveFieldResolverFunction =
                { input, queryValue, arguments, selections, resolutionContext ->
                    val selectionSet =
                        (field.type.baseTypeDef as? QPlanSchema.CompositeTypeDef)?.let {
                                type ->
                            type.takeIf { fullSchema.schema.getType(it.name) != null }
                                ?.let { selections.toEngineSelectionSet(it, fullSchema, sourceSchema) }
                        }
                    invokeExecutor(
                        input,
                        queryValue,
                        arguments,
                        selectionSet,
                        QPlanEngineExecutionContext(context, schemas, resolutionContext),
                    )
                }
            val resolver =
                if (isSelective) {
                    selectiveFieldResolverOf(objectFragment, queryFragment, resolverFunction)
                } else {
                    selectionAwareFieldResolverOf(objectFragment, queryFragment, resolverFunction)
                }
            val resolverWithVariablesProvider =
                variables.provider?.let { provider ->
                    resolver.withVariablesProvider(variables.providerNames, provider)
                } ?: resolver
            field to resolverWithVariablesProvider
        }

    require(supplied.size == fieldResolverExecutors.size) {
        "Field executor coordinates must map to distinct canonical fields"
    }
    return ExecutorRegistryInputs(
        fieldResolvers =
            supplied +
                namespaceFieldResolvers(fullSchema, schema, sourceSchema, supplied.keys) +
                if (includeDefaultQueryNodeResolvers) {
                    builtInNodeFieldResolvers(fullSchema, schema, context, supplied.keys)
                } else {
                    emptyMap()
                },
        variableProviders = variableProviders,
        nodeResolvers = qplanNodeResolvers(fullSchema, schemas, fieldResolverExecutors, nodeResolverExecutors, context),
    )
}

private fun namespaceFieldResolvers(
    fullSchema: EngineSchema,
    schema: QPlanSchema,
    sourceSchema: SourceSchemaAdapter,
    suppliedFields: Set<QPlanSchema.Field>,
): Map<QPlanSchema.Field, FieldResolverDefinition> =
    fullSchema.schema.allTypesAsList
        .filterIsInstance<GraphQLObjectType>()
        .flatMap { sourceParent ->
            sourceParent.fieldDefinitions.mapNotNull { sourceField ->
                val sourceOutput = GraphQLTypeUtil.unwrapAll(sourceField.type) as? GraphQLObjectType
                    ?: return@mapNotNull null
                if (!sourceOutput.hasAppliedDirective("namespaceType")) return@mapNotNull null
                val field = sourceSchema.field(sourceParent.name, sourceField.name)
                require(field is QPlanSchema.ObjectField) {
                    "Namespace field ${sourceParent.name}/${sourceField.name} " +
                        "does not map to a concrete object field"
                }
                if (field in suppliedFields) return@mapNotNull null
                val outputType = schema.requireType(sourceOutput.name)
                require(outputType is QPlanSchema.Object) {
                    "Namespace field ${sourceParent.name}/${sourceField.name} " +
                        "does not return a canonical object"
                }
                field to
                    fieldResolverOf(schema.emptyFragmentOf(field.containingDef.name)) { _, _ ->
                        engineObjectDataOf(outputType)
                    }
            }
        }.toMap()

private fun FieldResolverExecutor.objectFragment(
    schema: ViaductAndGJSchema,
    field: QPlanSchema.ObjectField,
): Fragment =
    objectSelectionSet?.let { required ->
        schema.fragmentFromDocument(
            document = required.selections.toDocument(),
            variableField = field,
        )
    } ?: schema.loweredSchema.emptyFragmentOf(field.containingDef.name)

private fun FieldResolverExecutor.queryFragment(
    schema: ViaductAndGJSchema,
    field: QPlanSchema.ObjectField,
): Fragment =
    querySelectionSet?.let { required ->
        schema.fragmentFromDocument(
            document = required.selections.toDocument(),
            variableField = field,
        )
    } ?: schema.loweredSchema.emptyFragmentOf(schema.loweredSchema.requireQueryTypeDef().name)

private fun builtInNodeFieldResolvers(
    fullSchema: EngineSchema,
    schema: QPlanSchema,
    context: EngineExecutionContext,
    suppliedFields: Set<QPlanSchema.Field>,
): Map<QPlanSchema.Field, FieldResolverDefinition> {
    val sourceSchema = SourceSchemaAdapter(schema)
    val query = schema.emptyFragmentOf(schema.requireQueryTypeDef().name)
    return buildMap {
        fullSchema.schema.queryType.getFieldDefinition("node")?.let { sourceField ->
            val field = sourceSchema.field(fullSchema.schema.queryType.name, sourceField.name)
            if (field !in suppliedFields) {
                put(
                    field,
                    fieldResolverOf(query) { _, arguments ->
                        nodeReference(arguments.fieldValues["id"], context)
                    },
                )
            }
        }
        fullSchema.schema.queryType.getFieldDefinition("nodes")?.let { sourceField ->
            val field = sourceSchema.field(fullSchema.schema.queryType.name, sourceField.name)
            if (field !in suppliedFields) {
                put(
                    field,
                    fieldResolverOf(query) { _, arguments ->
                        val ids = arguments.fieldValues["ids"]
                        if (ids !is List<*>) {
                            EngineErrorData.of()
                        } else {
                            ids.map { nodeReference(it, context) }
                        }
                    },
                )
            }
        }
    }
}

private fun nodeReference(
    globalId: Any?,
    context: EngineExecutionContext,
): Any {
    if (globalId !is String) return EngineErrorData.of()
    return try {
        val (typeName) = context.globalIDCodec.deserialize(globalId)
        val type =
            context.fullSchema.schema.getObjectType(typeName)
                ?: return EngineErrorData.of()
        if (type.interfaces.none { it.name == "Node" }) return EngineErrorData.of()
        normalizeNodeReference(context.createNodeReference(globalId, type))
    } catch (_: IllegalArgumentException) {
        EngineErrorData.of()
    }
}

private fun qplanNodeResolvers(
    fullSchema: EngineSchema,
    schemas: ViaductAndGJSchema,
    fieldResolverExecutors: List<Pair<Coordinate, FieldResolverExecutor>>,
    nodeResolverExecutors: List<Pair<String, NodeResolverExecutor>>,
    context: EngineExecutionContext,
): Map<QPlanSchema.Object, NodeResolverFunction> {
    val schema = schemas.loweredSchema
    val sourceSchema = SourceSchemaAdapter(schema)
    val supplied =
        nodeResolverExecutors.associate { (typeName, executor) ->
            val type = schema.requireType(typeName) as QPlanSchema.Object
            val fieldResolverOwnedFields =
                fieldResolverExecutors
                    .mapNotNullTo(linkedSetOf()) { (coordinate, _) ->
                        coordinate.second.takeIf { coordinate.first == typeName }
                    }

            suspend fun invokeExecutor(
                id: String,
                selections: EngineSelectionSet,
                executorContext: EngineExecutionContext,
            ): ResolverOutputData? {
                if (
                    executor.isSelective &&
                    selections.selections().all { selection -> selection.fieldName == "id" }
                ) {
                    return ResolvedEngineObjectData(
                        requireNotNull(fullSchema.schema.getObjectType(typeName)),
                        emptyMap(),
                    )
                }
                val selector = NodeResolverExecutor.Selector(id, selections)
                val output =
                    executor.resolve(listOf(selector), executorContext)[selector]
                        ?: Result.failure(
                            IllegalStateException(
                                "Node executor $typeName omitted its selector",
                            ),
                        )
                return output.fold(
                    onSuccess = {
                        when (
                            val normalized =
                                normalizeSourceOutput(
                                    requireNotNull(fullSchema.schema.getObjectType(typeName)),
                                    it,
                                    sourceSchema,
                                )
                        ) {
                            is RootFieldReferenceData -> normalized
                            is EngineObjectData.Sync ->
                                if (executor.isSelective) {
                                    normalized.projectTopLevel(
                                        selections = selections,
                                        excludedFields = fieldResolverOwnedFields,
                                    )
                                } else {
                                    normalized
                                }
                            else -> error("Node executor $typeName returned a non-object value")
                        }
                    },
                    onFailure = { EngineErrorData.of(it) },
                )
            }
            type to
                if (executor.isSelective) {
                    selectiveNodeResolverOf { id, selections, resolutionContext ->
                        invokeExecutor(
                            id,
                            selections.toEngineSelectionSet(type, fullSchema, sourceSchema),
                            QPlanEngineExecutionContext(context, schemas, resolutionContext),
                        )
                    }
                } else {
                    nodeResolverOf { id, resolutionContext ->
                        invokeExecutor(
                            id,
                            context.engineSelectionSetFactory.engineSelectionSet(
                                typeName,
                                "id",
                                emptyMap(),
                            ),
                            QPlanEngineExecutionContext(context, schemas, resolutionContext),
                        )
                    }
                }
        }
    return supplied
}

private fun EngineObjectData.Sync.projectTopLevel(
    selections: EngineSelectionSet,
    excludedFields: Set<String>,
): EngineObjectData.Sync {
    val demandedFields =
        selections
            .selections()
            .mapTo(linkedSetOf()) { it.fieldName }
            .minus(excludedFields)
    val fields =
        getSelections()
            .filter { fieldName -> fieldName in demandedFields }
            .associateWithTo(linkedMapOf(), ::get)
    return ResolvedEngineObjectData(
        type,
        fields,
    )
}

private fun normalizeSourceOutput(
    expectedType: GraphQLOutputType,
    value: Any?,
    sourceSchema: SourceSchemaAdapter,
): Any? =
    if (value is EngineErrorData) {
        value
    } else if (value is RootFieldReference) {
        sourceSchema.lowerRootFieldReference(
            rootFieldPath = value.rootFieldPath,
            sourceTypeName = value.type.name,
            arguments = value.args,
        )
    } else {
        when (expectedType) {
            is GraphQLNonNull ->
                normalizeSourceOutput(expectedType.wrappedType as GraphQLOutputType, value, sourceSchema)
            is GraphQLList -> {
                if (value !is List<*>) {
                    value
                } else {
                    value.map {
                        normalizeSourceOutput(
                            expectedType.wrappedType as GraphQLOutputType,
                            it,
                            sourceSchema,
                        )
                    }
                }
            }
            is GraphQLObjectType ->
                when (value) {
                    is NodeReference -> normalizeNodeReference(value)
                    is EngineObjectData.Sync -> normalizeSourceObject(expectedType, value, sourceSchema)
                    is Map<*, *> -> normalizeSourceObjectMap(expectedType, value, sourceSchema)
                    else -> value
                }
            is GraphQLCompositeType ->
                when (value) {
                    is NodeReference -> normalizeNodeReference(value)
                    is EngineObjectData.Sync -> normalizeSourceObject(value, sourceSchema)
                    else -> value
                }
            is GraphQLScalarType ->
                value?.let {
                    expectedType.coercing.serialize(
                        it,
                        GraphQLContext.getDefault(),
                        Locale.getDefault(),
                    )
                }
            else -> value
        }
    }

private fun normalizeSourceObjectMap(
    expectedType: GraphQLObjectType,
    value: Map<*, *>,
    sourceSchema: SourceSchemaAdapter,
): EngineObjectData.Sync {
    require(value.keys.all { it is String }) {
        "Qplan requires string keys in map object executor outputs"
    }
    @Suppress("UNCHECKED_CAST")
    return normalizeSourceObject(
        ResolvedEngineObjectData(expectedType, value as Map<String, Any?>),
        sourceSchema,
    )
}

private fun normalizeNodeReference(reference: NodeReference): EngineObjectData.Sync =
    ResolvedEngineObjectData(
        reference.type,
        mapOf("id" to reference.id),
    )

private fun normalizeSourceObject(
    value: EngineObjectData,
    sourceSchema: SourceSchemaAdapter,
): EngineObjectData.Sync {
    require(value is EngineObjectData.Sync) {
        "Qplan requires synchronous EngineObjectData executor outputs"
    }
    return normalizeSourceObject(value.type, value, sourceSchema)
}

private fun normalizeSourceObject(
    type: GraphQLObjectType,
    value: EngineObjectData.Sync,
    sourceSchema: SourceSchemaAdapter,
): EngineObjectData.Sync {
    val fields =
        value.getSelections().associateWith { selection ->
            val field =
                requireNotNull(type.getFieldDefinition(selection)) {
                    "Executor output ${type.name} has no field named $selection"
                }
            normalizeSourceOutput(field.type, value.get(selection), sourceSchema)
        }
    return ResolvedEngineObjectData(type, fields)
}

private fun Pair<String, String>.render(): String = "$first.$second"

internal fun validateExecutorRegistrations(
    fieldResolverExecutors: List<Pair<Coordinate, FieldResolverExecutor>>,
    nodeResolverExecutors: List<Pair<String, NodeResolverExecutor>>,
) {
    require(fieldResolverExecutors.map { it.first }.toSet().size == fieldResolverExecutors.size) {
        "Qplan requires unique field executor coordinates"
    }
    require(nodeResolverExecutors.map { it.first }.toSet().size == nodeResolverExecutors.size) {
        "Qplan requires unique node executor types"
    }
    fieldResolverExecutors.forEach { (coordinate, executor) ->
        if (executor.isBatching) TODO("Qplan does not support batching field executor ${coordinate.render()}")
    }
    nodeResolverExecutors.forEach { (typeName, executor) ->
        if (executor.isBatching) TODO("Qplan does not support batching node executor $typeName")
    }
}
