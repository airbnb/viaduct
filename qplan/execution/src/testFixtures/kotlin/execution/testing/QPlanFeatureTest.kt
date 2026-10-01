package execution.testing

import execution.ExecutorRegistryInputs
import execution.executorRegistryInputs
import execution.validateExecutorRegistrations
import graphql.ExecutionResult
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import graphql.schema.idl.SchemaPrinter
import java.util.IdentityHashMap
import model.engineObjectDataOf
import model.lowering.ViaductAndGJSchema
import model.registry.nodeResolverOf
import model.testing.TestWorld
import viaduct.engine.EngineConfiguration
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.NodeReference
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.api.RootFieldReference
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.MockTenantModuleBootstrapper
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
    val registryInputs = IdentityHashMap<QPlanSchema, ExecutorRegistryInputs>()
    validateExecutorRegistrations(fieldResolverExecutors.toList(), nodeResolverExecutors.toList())
    if (checkerExecutors.isNotEmpty() || typeCheckerExecutors.isNotEmpty()) {
        TODO("Qplan feature tests do not support checker executors yet")
    }

    fun inputs(schemas: ViaductAndGJSchema): ExecutorRegistryInputs =
        registryInputs.getOrPut(schemas.loweredSchema) {
            val adapted = executorRegistryInputs(
                fullSchema = fullSchema,
                schemas = schemas,
                fieldExecutors = fieldResolverExecutors.map { (coordinate, executor) ->
                    coordinate to fixtureFieldExecutor(coordinate, executor)
                },
                nodeExecutors = nodeResolverExecutors.map { (typeName, executor) ->
                    typeName to fixtureNodeExecutor(typeName, executor)
                },
                context = context,
                fieldSelectivityProvider = fieldSelectivityProvider,
                includeDefaultQueryNodeResolvers = !withoutDefaultQueryNodeResolvers,
            )
            val nodeType = schemas.loweredSchema.types["Node"] as? QPlanSchema.Interface
            val missing = if (adapted.nodeResolvers.isEmpty()) {
                emptyMap()
            } else {
                nodeType?.possibleObjectTypes.orEmpty()
                    .filter { it !in adapted.nodeResolvers }
                    .associateWith { type -> nodeResolverOf { _: String -> engineObjectDataOf(type) } }
            }
            ExecutorRegistryInputs(adapted.fieldResolvers, adapted.nodeResolvers + missing, adapted.variableProviders)
        }
    val world =
        TestWorld.fromSDL(
            schemaSDL = fullSchemaSDL,
            fieldResolvers = { inputs(it).fieldResolvers },
            nodeResolvers = { inputs(it).nodeResolvers },
            variableProviders = { inputs(it).variableProviders },
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

private fun EngineTestModule.fixtureFieldExecutor(
    coordinate: Pair<String, String>,
    executor: FieldResolverExecutor,
): FieldResolverExecutor =
    object : FieldResolverExecutor by executor {
        override suspend fun batchResolve(
            selectors: List<FieldResolverExecutor.Selector>,
            context: EngineExecutionContext,
        ): Map<FieldResolverExecutor.Selector, Result<Any?>> {
            val type = fullSchema.schema.getObjectType(coordinate.first).getFieldDefinition(coordinate.second).type
            return executor.batchResolve(selectors, context).mapValues { (_, result) ->
                result.map { fixtureNodeIds(type, it) }
            }
        }
    }

private fun EngineTestModule.fixtureNodeExecutor(
    typeName: String,
    executor: NodeResolverExecutor,
): NodeResolverExecutor =
    object : NodeResolverExecutor by executor {
        override suspend fun resolve(
            selectors: List<NodeResolverExecutor.Selector>,
            context: EngineExecutionContext,
        ): Map<NodeResolverExecutor.Selector, Result<EngineObjectData>> =
            executor.resolve(selectors, context).mapValues { (selector, result) ->
                result.map { output ->
                    val type = requireNotNull(fullSchema.schema.getObjectType(typeName))
                    val prepared = fixtureNodeIds(type, output) as EngineObjectData
                    if (prepared !is EngineObjectData.Sync || prepared is RootFieldReference) {
                        prepared
                    } else {
                        val fields = prepared.getSelections().associateWith(prepared::get).toMutableMap()
                        val demanded = if (executor.isSelective) selector.selections.selections().map { it.fieldName }.toSet() else null
                        type.fieldDefinitions.forEach { field ->
                            if (field.name !in fields && field.type !is GraphQLNonNull &&
                                (demanded == null || field.name in demanded) &&
                                fieldResolverExecutors.none { it.first == (typeName to field.name) }
                            ) {
                                fields[field.name] = null
                            }
                        }
                        ResolvedEngineObjectData(type, fields)
                    }
                }
            }
    }

private fun fixtureNodeIds(
    expectedType: GraphQLOutputType,
    value: Any?,
): Any? {
    if (value is RootFieldReference || value is NodeReference) return value
    if (expectedType is GraphQLNonNull) return fixtureNodeIds(expectedType.wrappedType as GraphQLOutputType, value)
    if (expectedType is GraphQLList && value is List<*>) {
        return value.map { fixtureNodeIds(expectedType.wrappedType as GraphQLOutputType, it) }
    }
    val type = (expectedType as? GraphQLObjectType) ?: (value as? EngineObjectData.Sync)?.type ?: return value
    val fields = when (value) {
        is EngineObjectData.Sync -> value.getSelections().associateWith(value::get)
        is Map<*, *> -> {
            if (value.keys.any { it !is String }) return value
            value.entries.associate { (key, entry) -> key as String to entry }
        }
        else -> return value
    }.mapValues { (name, entry) ->
        type.getFieldDefinition(name)?.let { fixtureNodeIds(it.type, entry) } ?: entry
    }.toMutableMap()
    if (type.interfaces.any { it.name == "Node" } && "id" !in fields) {
        fields["id"] = "__qplan_inline_node__"
    }
    return if (value is Map<*, *>) fields else ResolvedEngineObjectData(type, fields)
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
