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

class NamespaceTypeBuiltInFactoriesTest {
    private val objectMapper = jacksonObjectMapper()

    private fun mkSchema(sdl: String): ViaductSchema {
        val fullSdl = "directive @namespaceType on OBJECT\n$sdl"
        return ViaductSchema(UnExecutableSchemaGenerator.makeUnExecutableSchema(SchemaParser().parse(fullSdl)))
    }

    private fun testRegistry() =
        ExecutionRegistryConfigFile(
            version = "1",
            executorFactory = NamespaceTypeExecutorFactory::class.java.name,
        )

    private fun executorFactory() = NamespaceTypeExecutorFactory(CodeInjector.Naive, testRegistry())

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
    fun `config factory selects only namespace-typed fields`() {
        val schema = mkSchema(
            """
            type Inner @namespaceType { value: String }
            type Outer @namespaceType { inner: Inner, count: Int }
            type Query { outer: Outer, name: String }
            """.trimIndent()
        )

        val config = NamespaceTypeModuleConfigFactory(schema).moduleConfigSource()!!
            .source.openStream().use { objectMapper.readValue<ExecutionRegistryConfigFile>(it) }
        val fileBasedCoords = config.fields.map { Coordinate(it.typeName, it.fieldName) }.toSet()

        // Only fields returning a @namespaceType object are included; scalar fields (count, name)
        // are excluded.
        assertEquals(setOf(Coordinate("Query", "outer"), Coordinate("Outer", "inner")), fileBasedCoords)
    }

    @Test
    fun `config factory throws on a wrapped namespace-type field`() {
        // A namespace type reached through a list/non-null wrapper is invalid; discovery must fail
        // fast rather than emit a resolver for a wrapped coordinate.
        val schema = mkSchema(
            """
            type Listings @namespaceType { availableRoomTypes: [String] }
            type Query { listings: Listings! }
            """.trimIndent()
        )

        assertThrows<IllegalStateException> {
            NamespaceTypeModuleConfigFactory(schema).moduleConfigSource()
        }
    }

    @Test
    fun `config factory discovers namespace fields under the mutation root`() {
        val schema = mkSchema(
            """
            type Query { name: String }
            type Mutation { listings: Listings }
            type Listings @namespaceType { pricing: ListingsPricing }
            type ListingsPricing @namespaceType { setCurrency: String }
            """.trimIndent()
        )

        val config = NamespaceTypeModuleConfigFactory(schema).moduleConfigSource()!!
            .source.openStream().use { objectMapper.readValue<ExecutionRegistryConfigFile>(it) }
        val coords = config.fields.map { Coordinate(it.typeName, it.fieldName) }.toSet()

        assertEquals(setOf(Coordinate("Mutation", "listings"), Coordinate("Listings", "pricing")), coords)
    }

    @Test
    fun `config factory discovers namespace fields under both query and mutation roots`() {
        val schema = mkSchema(
            """
            type Query { queryListings: QueryListings }
            type QueryListings @namespaceType { search: String }
            type Mutation { mutationListings: MutationListings }
            type MutationListings @namespaceType { createListing: String }
            """.trimIndent()
        )

        val config = NamespaceTypeModuleConfigFactory(schema).moduleConfigSource()!!
            .source.openStream().use { objectMapper.readValue<ExecutionRegistryConfigFile>(it) }
        val coords = config.fields.map { Coordinate(it.typeName, it.fieldName) }.toSet()

        assertEquals(
            setOf(Coordinate("Query", "queryListings"), Coordinate("Mutation", "mutationListings")),
            coords,
        )
    }

    @Test
    fun `config factory handles a schema with no mutation type`() {
        val schema = mkSchema(
            """
            type Query { listings: Listings }
            type Listings @namespaceType { count: Int }
            """.trimIndent()
        )

        val config = NamespaceTypeModuleConfigFactory(schema).moduleConfigSource()!!
            .source.openStream().use { objectMapper.readValue<ExecutionRegistryConfigFile>(it) }
        assertEquals(listOf(Coordinate("Query", "listings")), config.fields.map { Coordinate(it.typeName, it.fieldName) })
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

    @Test
    fun `executor factory exposes the GRT-prefix constructor used by the bootstrap path`() {
        // ModuleConfigBootstrapper requests this 3-arg constructor when a grtPackagePrefix override
        // is in effect; without it, GRT-prefixed builds fail with NoSuchMethodException at startup.
        val ctor = NamespaceTypeExecutorFactory::class.java.getDeclaredConstructor(
            CodeInjector::class.java,
            String::class.java,
            ExecutionRegistryConfigFile::class.java,
        )
        val factory = ctor.newInstance(CodeInjector.Naive, "com.example.grt", testRegistry())
        val schema = mkSchema(
            """
            type Listings @namespaceType { count: Int }
            type Query { listings: Listings }
            """.trimIndent()
        )
        val executor = factory.createFieldResolverExecutor(
            FieldEntryConfig("Query", "listings", isBatching = false, isSelective = false, attribution = "namespace-type-resolver", tenantAPIData = emptyMap()),
            schema,
        )
        assertEquals("Query.listings", executor.resolverId)
    }
}
