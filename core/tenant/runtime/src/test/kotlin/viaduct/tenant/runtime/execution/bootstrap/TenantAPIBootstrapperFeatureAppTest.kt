@file:Suppress("ForbiddenImport")

package viaduct.tenant.runtime.execution.batchresolver.bootstrap

import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.api.FieldValue
import viaduct.api.Resolver
import viaduct.engine.api.ViaductSchema
import viaduct.graphql.utils.DefaultSchemaFactory
import viaduct.tenant.runtime.execution.FieldBatchResolverExecutorImpl
import viaduct.tenant.runtime.execution.FieldUnbatchedResolverExecutorImpl
import viaduct.tenant.runtime.execution.NodeBatchResolverExecutorImpl
import viaduct.tenant.runtime.execution.NodeUnbatchedResolverExecutorImpl
import viaduct.tenant.runtime.execution.batchresolver.bootstrap.resolverbases.NodeResolvers
import viaduct.tenant.runtime.execution.batchresolver.bootstrap.resolverbases.QueryResolvers
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase

class TenantAPIBootstrapperFeatureAppTest : FeatureAppTestBase() {
    override var sdl = """
        | #START_SCHEMA
        | extend type Query {
        |   field: String @resolver
        |   batchField: String @resolver
        | }
        |
        | type TestNode implements Node @resolver {
        |   id: ID!
        |   value: String
        | }
        |
        | type TestBatchNode implements Node @resolver {
        |   id: ID!
        |   value: String
        | }
        | #END_SCHEMA
    """.trimMargin()

    @Resolver
    class Query_FieldResolver : QueryResolvers.Field() {
        override suspend fun resolve(ctx: Context): String {
            return "123"
        }
    }

    @Resolver
    class Query_BatchFieldResolver : QueryResolvers.BatchField() {
        override suspend fun batchResolve(contexts: List<Context>): List<FieldValue<String>> {
            return listOf(FieldValue.Companion.ofValue("123"))
        }
    }

    @Resolver
    class TestNodeResolver : NodeResolvers.TestNode() {
        override suspend fun resolve(ctx: Context): TestNode {
            return TestNode.Builder(ctx).id(ctx.id).value("test-value").build()
        }
    }

    @Resolver
    class TestBatchNodeResolver : NodeResolvers.TestBatchNode() {
        override suspend fun batchResolve(contexts: List<Context>): List<FieldValue<TestBatchNode>> {
            return contexts.map { FieldValue.Companion.ofValue(TestBatchNode.Builder(it).id(it.id).value("test-value").build()) }
        }
    }

    @Test
    fun `test resolver functions are registered correctly`() =
        runBlocking {
            val tenantAPIBootstrapper = viaductTenantAPIBootstrapperBuilder.create()
            val tenantModuleBootstrappers = tenantAPIBootstrapper.tenantModuleBootstrappers()
            assertEquals(1, tenantModuleBootstrappers.toList().size)

            val tenant = tenantModuleBootstrappers.first()
            val schema = mkSchema(sdl)
            val fieldResolverExecutors = tenant.fieldResolverExecutors(schema).toMap()
            val nodeResolverExecutors = tenant.nodeResolverExecutors(schema).toMap()
            assertEquals(2, fieldResolverExecutors.size)
            assertEquals(2, nodeResolverExecutors.size)

            assert(("Query" to "field") in fieldResolverExecutors.keys)
            assert(fieldResolverExecutors.get(("Query" to "field")) is FieldUnbatchedResolverExecutorImpl)
            assert(("Query" to "batchField") in fieldResolverExecutors.keys)
            assert(fieldResolverExecutors.get(("Query" to "batchField")) is FieldBatchResolverExecutorImpl)
            assert("TestNode" in nodeResolverExecutors.keys)
            assert(nodeResolverExecutors.get("TestNode") is NodeUnbatchedResolverExecutorImpl)
            assert("TestBatchNode" in nodeResolverExecutors.keys)
            assert(nodeResolverExecutors.get("TestBatchNode") is NodeBatchResolverExecutorImpl)
        }

    private fun mkSchema(sdl: String): ViaductSchema {
        val tdr = SchemaParser().parse(sdl).apply {
            DefaultSchemaFactory.addDefaults(this)
        }
        return ViaductSchema(SchemaGenerator().makeExecutableSchema(tdr, RuntimeWiring.MOCKED_WIRING))
    }
}
