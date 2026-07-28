package viaduct.service.runtime.builtinresolvers

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.bootstrap.executionregistry.ExecutionRegistryConfigFile
import viaduct.engine.api.bootstrap.executionregistry.FieldEntryConfig
import viaduct.service.api.spi.CodeInjector

class QueryNodeBuiltInFactoriesTest {
    private val objectMapper = jacksonObjectMapper()

    private fun mkSchema(sdl: String): ViaductSchema {
        val esdl = "$sdl\ninterface Node { id: ID! }"
        return ViaductSchema(UnExecutableSchemaGenerator.makeUnExecutableSchema(SchemaParser().parse(esdl)))
    }

    private fun testRegistry() =
        ExecutionRegistryConfigFile(
            version = "1",
            executorFactory = QueryNodeExecutorFactory::class.java.name,
        )

    private fun executorFactory() = QueryNodeExecutorFactory(CodeInjector.Naive, testRegistry())

    @Test
    fun `config factory returns null when schema has neither node nor nodes`() {
        assertNull(QueryNodeModuleConfigFactory(mkSchema("type Query { i: Int }")).moduleConfigSource())
    }

    @Test
    fun `config factory emits node and nodes entries with stable tenant name`() {
        val source = QueryNodeModuleConfigFactory(
            mkSchema("type Query { node(id: ID!): Node, nodes(ids: [ID!]!): [Node]! }")
        ).moduleConfigSource()

        assertNotNull(source)
        assertEquals("viaduct.builtin.query_node", source!!.tenantName)

        val config = source.source.openStream().use { objectMapper.readValue<ExecutionRegistryConfigFile>(it) }
        assertEquals(QueryNodeExecutorFactory::class.java.name, config.executorFactory)
        assertEquals("viaduct.builtin.query_node", config.tenantName)
        assertEquals(
            setOf("Query" to "node", "Query" to "nodes"),
            config.fields.map { it.typeName to it.fieldName }.toSet(),
        )
    }

    @Test
    fun `config factory emits only node when nodes is absent`() {
        val source = QueryNodeModuleConfigFactory(mkSchema("type Query { node(id: ID!): Node }")).moduleConfigSource()
        val config = source!!.source.openStream().use { objectMapper.readValue<ExecutionRegistryConfigFile>(it) }
        assertEquals(listOf("Query" to "node"), config.fields.map { it.typeName to it.fieldName })
    }

    @Test
    fun `executor factory maps node and nodes entries to the shared resolver singletons`() {
        val factory = executorFactory()
        val schema = mkSchema("type Query { node(id: ID!): Node, nodes(ids: [ID!]!): [Node]! }")

        assertSame(
            QueryNodeExecutorFactory.queryNodeResolver,
            factory.createFieldResolverExecutor(fieldEntry("node"), schema),
        )
        assertSame(
            QueryNodeExecutorFactory.queryNodesResolver,
            factory.createFieldResolverExecutor(fieldEntry("nodes"), schema),
        )
    }

    @Test
    fun `executor factory rejects unknown field`() {
        assertThrows<IllegalArgumentException> {
            executorFactory().createFieldResolverExecutor(fieldEntry("bogus"), mkSchema("type Query { i: Int }"))
        }
    }

    @Test
    fun `executor factory exposes the GRT-prefix constructor used by the bootstrap path`() {
        // ModuleConfigBootstrapper requests this 3-arg constructor when a grtPackagePrefix override
        // is in effect; without it, GRT-prefixed builds fail with NoSuchMethodException at startup.
        val ctor = QueryNodeExecutorFactory::class.java.getDeclaredConstructor(
            CodeInjector::class.java,
            String::class.java,
            ExecutionRegistryConfigFile::class.java,
        )
        val factory = ctor.newInstance(CodeInjector.Naive, "com.example.grt", testRegistry())
        assertSame(
            QueryNodeExecutorFactory.queryNodeResolver,
            factory.createFieldResolverExecutor(fieldEntry("node"), mkSchema("type Query { node(id: ID!): Node }")),
        )
    }

    private fun fieldEntry(fieldName: String) =
        FieldEntryConfig(
            typeName = "Query",
            fieldName = fieldName,
            isBatching = false,
            isSelective = false,
            attribution = "query-node-resolver",
            tenantAPIData = emptyMap(),
        )
}
