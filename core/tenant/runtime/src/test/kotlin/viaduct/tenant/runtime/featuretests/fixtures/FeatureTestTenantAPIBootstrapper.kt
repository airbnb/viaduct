package viaduct.tenant.runtime.featuretests.fixtures

import viaduct.api.internal.ReflectionLoader
import viaduct.engine.api.Coordinate
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.LegacyTenantModuleBootstrapper
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.api.spi.TenantAPIBootstrapper
import viaduct.service.api.spi.TenantAPIBootstrapperBuilder
import viaduct.tenant.runtime.execution.FieldUnbatchedResolverExecutorImpl
import viaduct.tenant.runtime.execution.NodeBatchResolverExecutorImpl
import viaduct.tenant.runtime.execution.NodeUnbatchedResolverExecutorImpl

/** Intended for testing only - the implementation is naive and not scalable. */
class FeatureTestTenantAPIBootstrapperBuilder(
    val fieldUnbatchedResolverStubs: Map<Coordinate, FieldResolverStub>,
    val nodeUnbatchedResolverStubs: Map<String, NodeUnbatchedResolverStub>,
    val nodeBatchResolverStubs: Map<String, NodeBatchResolverStub>,
    val reflectionLoader: ReflectionLoader,
) : TenantAPIBootstrapperBuilder<LegacyTenantModuleBootstrapper> {
    override fun create() =
        object : TenantAPIBootstrapper {
            val module: LegacyTenantModuleBootstrapper = FeatureTestTenantModuleBootstrapper(
                fieldUnbatchedResolverStubs,
                nodeUnbatchedResolverStubs,
                nodeBatchResolverStubs,
                reflectionLoader,
            )

            override suspend fun tenantModuleBootstrappers() = listOf(module)
        }
}

/** Intended for testing only - the implementation is naive and not scalable. */
class FeatureTestTenantModuleBootstrapper(
    val fieldUnbatchedResolverStubs: Map<Coordinate, FieldResolverStub>,
    val nodeUnbatchedResolverStubs: Map<String, NodeUnbatchedResolverStub>,
    val nodeBatchResolverExecutorStubs: Map<String, NodeBatchResolverStub>,
    val reflectionLoader: ReflectionLoader,
) : LegacyTenantModuleBootstrapper {
    override fun fieldResolverExecutors(schema: ViaductSchema): Iterable<Pair<Coordinate, FieldResolverExecutor>> =
        fieldUnbatchedResolverStubs.mapNotNull { (coord, stub) ->
            // Skip resolvers for fields that don't exist in the schema (e.g., after schema hot-swap)
            val graphQLType = schema.schema.getType(coord.first) as? graphql.schema.GraphQLObjectType
                ?: return@mapNotNull null
            if (graphQLType.getFieldDefinition(coord.second) == null) {
                return@mapNotNull null
            }

            val (objectSelectionSet, querySelectionSet) = stub.requiredSelectionSets(coord, schema.schema, reflectionLoader)
            val resolverFactory = stub.resolverFactory(schema, reflectionLoader)
            coord to FieldUnbatchedResolverExecutorImpl(
                objectSelectionSet = objectSelectionSet,
                querySelectionSet = querySelectionSet,
                isSelective = false,
                resolver = stub.resolver,
                resolveFn = stub::resolve,
                resolverId = "${coord.first}.${coord.second}",
                reflectionLoader = reflectionLoader,
                resolverContextFactory = resolverFactory,
                resolverName = stub.resolverName ?: "test-field-unbatched-resolver"
            )
        }

    override fun nodeResolverExecutors(schema: ViaductSchema): Iterable<Pair<String, NodeResolverExecutor>> =
        nodeUnbatchedResolverStubs.mapNotNull { (typeName, stub) ->
            // Skip resolvers for types that don't exist in the schema (e.g., after schema hot-swap)
            if (schema.schema.getType(typeName) == null) {
                return@mapNotNull null
            }

            typeName to NodeUnbatchedResolverExecutorImpl(
                resolver = stub.resolver,
                resolveFunction = stub::resolve,
                typeName = typeName,
                reflectionLoader = reflectionLoader,
                factory = stub.resolverFactory,
                resolverName = stub.resolverName ?: "test-node-unbatched-resolver",
                isSelective = false,
            )
        } + nodeBatchResolverExecutorStubs.mapNotNull { (typeName, stub) ->
            // Skip resolvers for types that don't exist in the schema (e.g., after schema hot-swap)
            if (schema.schema.getType(typeName) == null) {
                return@mapNotNull null
            }

            typeName to NodeBatchResolverExecutorImpl(
                resolver = stub.resolver,
                batchResolveFunction = stub::batchResolve,
                typeName = typeName,
                reflectionLoader = reflectionLoader,
                factory = stub.resolverFactory,
                resolverName = stub.resolverName ?: "test-node-batch-resolver",
                isSelective = false,
            )
        }
}
