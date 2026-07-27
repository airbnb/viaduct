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
import viaduct.service.api.spi.InputStreamSource

class QueryNodeBuiltInFactoriesTest {
    private val objectMapper = jacksonObjectMapper()

    private fun mkSchema(sdl: String): ViaductSchema {
        val esdl = "$sdl\ninterface Node { id: ID! }"
        return ViaductSchema(UnExecutableSchemaGenerator.makeUnExecutableSchema(SchemaParser().parse(esdl)))
    }

    private fun executorFactory() = QueryNodeExecutorFactory(CodeInjector.Naive, InputStreamSource.fromString("{}", "test"))

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
            ViaductQueryNodeResolverModuleBootstrapper.queryNodeResolver,
            factory.createFieldResolverExecutor(fieldEntry("node"), schema),
        )
        assertSame(
            ViaductQueryNodeResolverModuleBootstrapper.queryNodesResolver,
            factory.createFieldResolverExecutor(fieldEntry("nodes"), schema),
        )
    }

    @Test
    fun `executor factory rejects unknown field`() {
        assertThrows<IllegalArgumentException> {
            executorFactory().createFieldResolverExecutor(fieldEntry("bogus"), mkSchema("type Query { i: Int }"))
        }
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
