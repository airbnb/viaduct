package viaduct.service.runtime.builtinresolvers

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.Coordinate
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.bootstrap.executionregistry.ExecutionRegistryConfigFile
import viaduct.engine.api.bootstrap.executionregistry.FieldEntryConfig
import viaduct.service.api.spi.CodeInjector
import viaduct.service.api.spi.InputStreamSource

class NamespaceTypeBuiltInFactoriesTest {
    private val objectMapper = jacksonObjectMapper()

    private fun mkSchema(sdl: String): ViaductSchema {
        val fullSdl = "directive @namespaceType on OBJECT\n$sdl"
        return ViaductSchema(UnExecutableSchemaGenerator.makeUnExecutableSchema(SchemaParser().parse(fullSdl)))
    }

    private fun executorFactory() = NamespaceTypeExecutorFactory(CodeInjector.Naive, InputStreamSource.fromString("{}", "test"))

    @Test
    fun `config factory returns null when schema has no namespace fields`() {
        assertNull(NamespaceTypeModuleConfigFactory(mkSchema("type Query { name: String }")).moduleConfigSource())
    }

    @Test
    fun `config factory emits entries with stable tenant name and executor factory`() {
        val source = NamespaceTypeModuleConfigFactory(
            mkSchema(
                """
                type Listings @namespaceType { count: Int }
                type Query { listings: Listings }
                """.trimIndent()
            )
        ).moduleConfigSource()

        assertNotNull(source)
        assertEquals("viaduct.builtin.namespace_type", source!!.tenantName)
        val config = source.source.openStream().use { objectMapper.readValue<ExecutionRegistryConfigFile>(it) }
        assertEquals(NamespaceTypeExecutorFactory::class.java.name, config.executorFactory)
        assertEquals(listOf("Query" to "listings"), config.fields.map { it.typeName to it.fieldName })
    }

    @Test
    fun `config factory discovers nested namespace fields`() {
        val schema = mkSchema(
            """
            type Inner @namespaceType { value: String }
            type Outer @namespaceType { inner: Inner }
            type Query { outer: Outer }
            """.trimIndent()
        )
        val config = NamespaceTypeModuleConfigFactory(schema).moduleConfigSource()!!
            .source.openStream().use { objectMapper.readValue<ExecutionRegistryConfigFile>(it) }
        assertEquals(
            setOf("Query" to "outer", "Outer" to "inner"),
            config.fields.map { it.typeName to it.fieldName }.toSet(),
        )
    }

    @Test
    fun `file-based factory pair matches legacy bootstrapper coordinates`() {
        val schema = mkSchema(
            """
            type Inner @namespaceType { value: String }
            type Outer @namespaceType { inner: Inner, count: Int }
            type Query { outer: Outer, name: String }
            """.trimIndent()
        )

        val legacyCoords = NamespaceTypeResolverModuleBootstrapper()
            .fieldResolverExecutors(schema).map { it.first }.toSet()

        val config = NamespaceTypeModuleConfigFactory(schema).moduleConfigSource()!!
            .source.openStream().use { objectMapper.readValue<ExecutionRegistryConfigFile>(it) }
        val fileBasedCoords = config.fields.map { Coordinate(it.typeName, it.fieldName) }.toSet()

        assertEquals(legacyCoords, fileBasedCoords)
    }

    @Test
    fun `executor factory reconstructs a resolver for a namespace field`() {
        val schema = mkSchema(
            """
            type Listings @namespaceType { count: Int }
            type Query { listings: Listings }
            """.trimIndent()
        )
        val executor = executorFactory().createFieldResolverExecutor(
            FieldEntryConfig("Query", "listings", isBatching = false, isSelective = false, attribution = "namespace-type-resolver", tenantAPIData = emptyMap()),
            schema,
        )
        assertEquals("Query.listings", executor.resolverId)
    }

    @Test
    fun `executor factory rejects a missing field`() {
        assertThrows<IllegalArgumentException> {
            executorFactory().createFieldResolverExecutor(
                FieldEntryConfig("Query", "missing", isBatching = false, isSelective = false, attribution = "namespace-type-resolver", tenantAPIData = emptyMap()),
                mkSchema("type Query { name: String }"),
            )
        }
    }
}
