@file:Suppress("DEPRECATION")

package viaduct.engine.runtime2.execution.testing

import graphql.ExecutionResult
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import viaduct.engine.EngineConfiguration
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.NodeReference
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.api.RootFieldReference
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.FeatureTest
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.MockNodeUnbatchedResolverExecutor
import viaduct.engine.api.mocks.MockTenantModuleBootstrapper
import viaduct.engine.api.mocks.runStandardViaductFeatureTest
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.runtime.tenantloading.InvalidVariableException
import viaduct.engine.runtime.tenantloading.RequiredSelectionsAreInvalid
import viaduct.engine.runtime.tenantloading.RequiredSelectionsCycleException
import viaduct.service.runtime.StandardViaduct

/** GraphQL feature-test surface backed by engine2 through production [StandardViaduct] wiring. */
class QPlanFeatureTest internal constructor(
    private val delegate: FeatureTest,
) {
    fun runQuery(
        query: String,
        variables: Map<String, Any?> = emptyMap(),
    ): ExecutionResult = delegate.runQuery(query, variables)

    fun runQueryWithTimeout(
        query: String,
        variables: Map<String, Any?> = emptyMap(),
        timeoutMillis: Long = 1_000,
    ): ExecutionResult = delegate.runQueryWithin(query, variables, timeoutMillis)

    fun ExecutionResult.assertJson(expectedJson: String): Unit = with(delegate) { assertJson(expectedJson) }
}

/**
 * Runs engine2 through [StandardViaduct] against this in-memory engine module.
 *
 * This follows the old engine's production-derived feature harness by bootstrapping module configs,
 * dispatchers, data loaders, and checkers. Unlike that harness, requests enter through the service
 * API so feature tests also cover engine selection and the rest of [StandardViaduct]'s execution
 * boundary.
 */
fun EngineTestModule.runQPlanFeatureTest(
    withoutDefaultQueryNodeResolvers: Boolean = false,
    schema: EngineSchema? = null,
    engineConfig: EngineConfiguration? = null,
    block: QPlanFeatureTest.() -> Unit,
) {
    val suppliedFieldExecutors = fieldResolverExecutors.toList()
    val suppliedFieldCoordinates = suppliedFieldExecutors.mapTo(mutableSetOf()) { it.first }
    val missingQueryExecutors =
        fullSchema.schema.queryType.fieldDefinitions
            .filter { ("Query" to it.name) !in suppliedFieldCoordinates }
            .map { field ->
                ("Query" to field.name) to
                    MockFieldUnbatchedResolverExecutor(
                        resolverId = "runtime2-feature-test-default:Query.${field.name}",
                        unbatchedResolveFn = { _, _, _, _, _ -> fixtureDefaultValue(field.type) },
                    )
            }
    val suppliedNodeExecutors = nodeResolverExecutors.toList()
    val missingNodeExecutors =
        if (suppliedNodeExecutors.isEmpty()) {
            emptyList()
        } else {
            val suppliedTypes = suppliedNodeExecutors.mapTo(mutableSetOf()) { it.first }
            val nodeType = fullSchema.schema.getType("Node") as? GraphQLInterfaceType
            nodeType
                ?.let(fullSchema.schema::getImplementations)
                .orEmpty()
                .filter { it.name !in suppliedTypes }
                .map { type ->
                    type.name to
                        MockNodeUnbatchedResolverExecutor(typeName = type.name) { _, _, _ ->
                            ResolvedEngineObjectData(type, emptyMap())
                        }
                }
        }
    val adaptedModule =
        EngineTestModule(
            fullSchema = fullSchema,
            fieldResolverExecutors = (suppliedFieldExecutors + missingQueryExecutors).map { (coordinate, executor) ->
                coordinate to fixtureFieldExecutor(coordinate, executor)
            },
            nodeResolverExecutors = (suppliedNodeExecutors + missingNodeExecutors).map { (typeName, executor) ->
                typeName to fixtureNodeExecutor(typeName, executor)
            },
            checkerExecutors = checkerExecutors,
            typeCheckerExecutors = typeCheckerExecutors,
        )
    try {
        adaptedModule.runStandardViaductFeatureTest(
            engine2Enabled = true,
            withoutDefaultQueryNodeResolvers =
                withoutDefaultQueryNodeResolvers ||
                    suppliedFieldCoordinates.any { (typeName, fieldName) ->
                        typeName == "Query" && fieldName in setOf("node", "nodes")
                    },
            schema = schema,
            engineConfig = engineConfig,
        ) {
            QPlanFeatureTest(this).block()
        }
    } catch (failure: RequiredSelectionsAreInvalid) {
        throw IllegalArgumentException("Invalid GraphQL fragment: ${failure.message}", failure)
    } catch (failure: InvalidVariableException) {
        throw failure.asFeatureTestFailure()
    } catch (failure: RequiredSelectionsCycleException) {
        throw IllegalArgumentException("Resolver object fragments contain a demand cycle", failure)
    }
}

/** Preserves the source feature harness's public validation vocabulary. */
private fun InvalidVariableException.asFeatureTestFailure(): IllegalArgumentException {
    val compatibleMessage =
        when {
            reason.startsWith("Types not compatible") -> {
                val path = reason.substringAfter(" at location [").substringBefore(']')
                "Variable $variableName object provider path $path is incompatible with one of its argument locations"
            }

            reason.contains("must terminate on a scalar or enum type") -> {
                val path = reason.substringAfter("Path [").substringBefore(']')
                "from-field path $path must terminate at a scalar or enum"
            }

            else -> message
        }
    return IllegalArgumentException(compatibleMessage, this)
}

private fun fixtureDefaultValue(type: GraphQLOutputType): Any? =
    when (type) {
        is GraphQLNonNull -> fixtureDefaultValue(type.wrappedType as GraphQLOutputType)
        is GraphQLList -> emptyList<Any?>()
        else -> null
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
