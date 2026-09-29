package execution.testing

import graphql.ExecutionResult
import graphql.GraphQLContext
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLTypeUtil
import graphql.schema.idl.SchemaPrinter
import java.util.IdentityHashMap
import java.util.Locale
import model.Arguments
import model.EngineErrorData
import model.EngineOutputData
import model.Fragment
import model.RootFieldReferenceData
import model.SourceSchemaAdapter
import model.emptyFragmentOf
import model.engineObjectDataOf
import model.fragmentFromDocument
import model.registry.SelectiveFieldResolverFunction
import model.requireQueryTypeDef
import model.requireType
import model.testing.FieldResolverDefinition
import model.testing.NodeResolverFunction
import model.testing.TestWorld
import model.testing.VariableDeclaration
import model.testing.fieldResolverOf
import model.testing.nodeResolverOf
import model.testing.selectionAwareFieldResolverOf
import model.testing.selectiveFieldResolverOf
import model.testing.selectiveNodeResolverOf
import viaduct.engine.EngineConfiguration
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.NodeReference
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.api.RootFieldReference
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.MockTenantModuleBootstrapper
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.FieldSelectivityProvider
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.runtime.mocks.ContextMocks
import viaduct.graphql.schema.ViaductSchema as QPlanSchema
import viaduct.graphql.test.assertJson as realAssertJson

/**
 * GraphQL feature-test surface backed directly by qplan and an [EngineTestModule]'s executors.
 *
 * This is intentionally a pre-dispatcher integration: it does not construct a DispatcherRegistry
 * or data loaders. The current integration accepts synchronous, unbatched field executors,
 * including selective ones.
 */
class QPlanFeatureTest internal constructor(
    private val fixture: ExecutionTestFixture,
) {
    fun runQuery(
        query: String,
        variables: Map<String, Any?> = emptyMap(),
    ): ExecutionResult = fixture.runQuery(query, variables)

    fun runQueryWithTimeout(
        query: String,
        variables: Map<String, Any?> = emptyMap(),
        timeoutMillis: Long = 1_000,
    ): ExecutionResult {
        require(timeoutMillis > 0) { "Timeout must be positive" }
        return runQuery(query, variables)
    }

    fun ExecutionResult.assertJson(expectedJson: String): Unit = realAssertJson(expectedJson)
}

/**
 * Runs qplan against the executor registry represented by this in-memory engine module.
 *
 * Source executor values are adapted before qplan's existing fixture lowering. Consequently,
 * `__typename` remains GraphQL-Java completion over qplan's generated typename resolvers, while
 * Node references and Node resolver outputs use the canonical qplan node-bridge lowering.
 */
fun EngineTestModule.runQPlanFeatureTest(
    withoutDefaultQueryNodeResolvers: Boolean = false,
    schema: EngineSchema? = null,
    engineConfig: EngineConfiguration? = null,
    block: QPlanFeatureTest.() -> Unit,
) {
    val fullSchemaSDL = qplanSchemaSDL(fullSchema)
    val executableSchemaSDL = qplanSchemaSDL(schema ?: fullSchema)
    val context = ContextMocks(myFullSchema = fullSchema).engineExecutionContext
    val fieldSelectivityProvider =
        engineConfig?.fieldSelectivityProvider ?: FieldSelectivityProvider.Never
    val registryInputs = IdentityHashMap<QPlanSchema, QPlanRegistryInputs>()
    validateSupportedExecutors()

    val world =
        TestWorld.fromSDL(
            schemaSDL = fullSchemaSDL,
            fieldResolvers = { canonicalSchema ->
                registryInputs
                    .getOrPut(canonicalSchema) {
                        qplanRegistryInputs(
                            schema = canonicalSchema,
                            context = context,
                            fieldSelectivityProvider = fieldSelectivityProvider,
                            includeDefaultQueryNodeResolvers = !withoutDefaultQueryNodeResolvers,
                        )
                    }
                    .fieldResolvers
            },
            nodeResolvers = { canonicalSchema ->
                qplanNodeResolvers(canonicalSchema, context)
            },
            variableProviders = { canonicalSchema ->
                registryInputs
                    .getOrPut(canonicalSchema) {
                        qplanRegistryInputs(
                            schema = canonicalSchema,
                            context = context,
                            fieldSelectivityProvider = fieldSelectivityProvider,
                            includeDefaultQueryNodeResolvers = !withoutDefaultQueryNodeResolvers,
                        )
                    }
                    .variableProviders
            },
        )
    ExecutionTestFixture.fromWorld(executableSchemaSDL, world).use { fixture ->
        QPlanFeatureTest(fixture).block()
    }
}

fun MockTenantModuleBootstrapper.runQPlanFeatureTest(
    withoutDefaultQueryNodeResolvers: Boolean = false,
    schema: EngineSchema? = null,
    engineConfig: EngineConfiguration? = null,
    block: QPlanFeatureTest.() -> Unit,
) {
    EngineTestModule(
        fullSchema = fullSchema,
        fieldResolverExecutors = fieldResolverExecutors,
        nodeResolverExecutors = nodeResolverExecutors,
        checkerExecutors = checkerExecutors,
        typeCheckerExecutors = typeCheckerExecutors,
    ).runQPlanFeatureTest(
        withoutDefaultQueryNodeResolvers = withoutDefaultQueryNodeResolvers,
        schema = schema,
        engineConfig = engineConfig,
        block = block,
    )
}

private fun EngineTestModule.validateSupportedExecutors() {
    if (checkerExecutors.isNotEmpty() || typeCheckerExecutors.isNotEmpty()) {
        TODO("Qplan feature tests do not support checker executors yet")
    }
    fieldResolverExecutors.forEach { (coordinate, executor) ->
        if (executor.isBatching) {
            TODO("Qplan feature tests do not support batching field executor ${coordinate.render()}")
        }
    }
    nodeResolverExecutors.forEach { (typeName, executor) ->
        if (executor.isBatching) {
            TODO("Qplan feature tests do not support batching node executor $typeName")
        }
    }
}

private data class QPlanRegistryInputs(
    val fieldResolvers: Map<QPlanSchema.Field, FieldResolverDefinition>,
    val variableProviders: Map<Arguments.Variable, VariableDeclaration>,
)

private fun EngineTestModule.qplanRegistryInputs(
    schema: QPlanSchema,
    context: EngineExecutionContext,
    fieldSelectivityProvider: FieldSelectivityProvider,
    includeDefaultQueryNodeResolvers: Boolean,
): QPlanRegistryInputs {
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
            val objectFragment = executor.objectFragment(schema, field)
            val queryFragment = executor.queryFragment(schema, field)
            val variables =
                executor.compileVariableDeclarations(
                    schema = schema,
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
            ): EngineOutputData? {
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
                        QPlanEngineExecutionContext(context, schema, resolutionContext),
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

    val duplicateCount = fieldResolverExecutors.count() - supplied.size
    require(duplicateCount == 0) {
        "Qplan feature tests require unique field executor coordinates"
    }
    return QPlanRegistryInputs(
        fieldResolvers =
            supplied +
                namespaceFieldResolvers(schema, sourceSchema, supplied.keys) +
                if (includeDefaultQueryNodeResolvers) {
                    builtInNodeFieldResolvers(schema, context, supplied.keys)
                } else {
                    emptyMap()
                },
        variableProviders = variableProviders,
    )
}

private fun EngineTestModule.namespaceFieldResolvers(
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
    schema: QPlanSchema,
    field: QPlanSchema.ObjectField,
): Fragment =
    objectSelectionSet?.let { required ->
        schema.fragmentFromDocument(
            document = required.selections.toDocument(),
            variableField = field,
        )
    } ?: schema.emptyFragmentOf(field.containingDef.name)

private fun FieldResolverExecutor.queryFragment(
    schema: QPlanSchema,
    field: QPlanSchema.ObjectField,
): Fragment =
    querySelectionSet?.let { required ->
        schema.fragmentFromDocument(
            document = required.selections.toDocument(),
            variableField = field,
        )
    } ?: schema.emptyFragmentOf(schema.requireQueryTypeDef().name)

private fun EngineTestModule.builtInNodeFieldResolvers(
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

private fun EngineTestModule.qplanNodeResolvers(
    schema: QPlanSchema,
    context: EngineExecutionContext,
): Map<QPlanSchema.Object, NodeResolverFunction> {
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
            ): EngineOutputData? {
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
                                    completeMissingNodeFields(typeName, normalized)
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
                            QPlanEngineExecutionContext(context, schema, resolutionContext),
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
                            QPlanEngineExecutionContext(context, schema, resolutionContext),
                        )
                    }
                }
        }
    require(nodeResolverExecutors.count() == supplied.size) {
        "Qplan feature tests require unique node executor types"
    }
    if (supplied.isEmpty()) return supplied

    val nodeType = schema.types["Node"] as? QPlanSchema.Interface ?: return supplied
    val unavailable =
        nodeType.possibleObjectTypes
            .filter { type -> type !in supplied }
            .associateWith { type ->
                nodeResolverOf { _: String -> model.engineObjectDataOf(type) }
            }
    return supplied + unavailable
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
    demandedFields.forEach { fieldName ->
        val field = type.getFieldDefinition(fieldName) ?: return@forEach
        if (fieldName !in fields && field.type !is GraphQLNonNull) {
            fields[fieldName] = null
        }
    }
    return ResolvedEngineObjectData(
        type,
        fields,
    )
}

private fun EngineTestModule.completeMissingNodeFields(
    typeName: String,
    value: EngineObjectData.Sync,
): EngineObjectData.Sync {
    val type = requireNotNull(fullSchema.schema.getObjectType(typeName))
    val fields = value.getSelections().associateWith(value::get).toMutableMap()
    type.fieldDefinitions.forEach { field ->
        if (
            field.name !in fields &&
            field.type !is GraphQLNonNull &&
            fieldResolverExecutors.none { (coordinate, _) ->
                coordinate == (typeName to field.name)
            }
        ) {
            fields[field.name] = null
        }
    }
    return ResolvedEngineObjectData(type, fields)
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
    /*
     * EngineTestModule field executors may return a raw GraphQL object source as a map because
     * FieldResolverExecutor's output contract is Any?. Production execution accepts that source,
     * resolves its child fields into an OER, and only then projects required selections as
     * EngineObjectData. This pre-dispatcher adapter bypasses those steps, so materialize the map
     * here before it crosses into qplan's stricter EngineOutputData domain. The declared concrete
     * object type makes this conversion unambiguous; abstract map outputs remain unsupported.
     */
    require(value.keys.all { it is String }) {
        "Qplan feature tests require string keys in map object executor outputs"
    }
    @Suppress("UNCHECKED_CAST")
    return normalizeSourceObject(
        createEngineObjectData(expectedType, value as Map<String, Any?>),
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
        "Qplan feature tests require synchronous EngineObjectData executor outputs"
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
        }.toMutableMap()
    if (
        type.interfaces.any { it.name == "Node" } &&
        "id" !in fields
    ) {
        fields["id"] = "__qplan_inline_node__"
    }
    return ResolvedEngineObjectData(type, fields)
}

private fun qplanSchemaSDL(schema: EngineSchema): String {
    val options =
        SchemaPrinter.Options
            .defaultOptions()
            .includeIntrospectionTypes(false)
            .includeScalarTypes(false)
            .includeDirectiveDefinition { directiveName -> directiveName == "parent" }
            .includeDirectives { directiveName -> directiveName == "parent" }
            .includeSchemaDefinition(false)
    return SchemaPrinter(options).print(schema.schema)
}

private fun Pair<String, String>.render(): String = "$first.$second"
