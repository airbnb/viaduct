package viaduct.service.runtime

import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.Coordinate
import viaduct.engine.api.ViaductSchema
import viaduct.service.runtime.builtinresolvers.NamespaceTypeResolverModuleBootstrapper

class NamespaceTypeResolverModuleBootstrapperTest {
    companion object {
        private fun mkSchema(sdl: String): ViaductSchema {
            val fullSdl = "directive @namespaceType on OBJECT\n$sdl"
            val s = UnExecutableSchemaGenerator.makeUnExecutableSchema(SchemaParser().parse(fullSdl))
            return ViaductSchema(s)
        }
    }

    @Test
    fun `no namespace types produces no resolvers`() {
        val schema = mkSchema("type Query { name: String }")
        val resolvers = NamespaceTypeResolverModuleBootstrapper().fieldResolverExecutors(schema)
        assertEquals(0, resolvers.count())
    }

    @Test
    fun `registers resolver for field returning namespace type`() {
        val schema = mkSchema(
            """
            type Listings @namespaceType { availableRoomTypes: [RoomType], count: Int }
            type RoomType { id: ID! }
            type Query { listings: Listings }
            """.trimIndent()
        )
        val resolvers = NamespaceTypeResolverModuleBootstrapper().fieldResolverExecutors(schema).toList()
        val coords = resolvers.map { it.first }.toSet()
        // Only Query.listings — fields on Listings (availableRoomTypes, count) return non-namespace types
        assertEquals(setOf(Coordinate("Query", "listings")), coords)
    }

    @Test
    fun `fails on wrapped namespace type field`() {
        val schema = mkSchema(
            """
            type Listings @namespaceType { availableRoomTypes: [String] }
            type Query { listings: Listings! }
            """.trimIndent()
        )
        assertThrows<IllegalStateException> {
            NamespaceTypeResolverModuleBootstrapper().fieldResolverExecutors(schema).toList()
        }
    }

    @Test
    fun `registers resolver for namespace type under mutation root`() {
        val schema = mkSchema(
            """
            type Query { name: String }
            type Mutation { listings: Listings }
            type Listings @namespaceType { createListing: String }
            """.trimIndent()
        )
        val resolvers = NamespaceTypeResolverModuleBootstrapper().fieldResolverExecutors(schema).toList()
        val coords = resolvers.map { it.first }.toSet()
        assertEquals(setOf(Coordinate("Mutation", "listings")), coords)
    }

    @Test
    fun `registers resolvers for nested namespace types under mutation root`() {
        val schema = mkSchema(
            """
            type Query { name: String }
            type Mutation { listings: Listings }
            type Listings @namespaceType { pricing: ListingsPricing }
            type ListingsPricing @namespaceType { setCurrency: String }
            """.trimIndent()
        )
        val resolvers = NamespaceTypeResolverModuleBootstrapper().fieldResolverExecutors(schema).toList()
        val coords = resolvers.map { it.first }.toSet()
        assertEquals(
            setOf(Coordinate("Mutation", "listings"), Coordinate("Listings", "pricing")),
            coords
        )
    }

    @Test
    fun `registers resolvers for namespace types under both query and mutation roots`() {
        val schema = mkSchema(
            """
            type Query { queryListings: QueryListings }
            type QueryListings @namespaceType { search: String }
            type Mutation { mutationListings: MutationListings }
            type MutationListings @namespaceType { createListing: String }
            """.trimIndent()
        )
        val resolvers = NamespaceTypeResolverModuleBootstrapper().fieldResolverExecutors(schema).toList()
        val coords = resolvers.map { it.first }.toSet()
        assertEquals(
            setOf(
                Coordinate("Query", "queryListings"),
                Coordinate("Mutation", "mutationListings")
            ),
            coords
        )
    }

    @Test
    fun `schema without mutation type does not break`() {
        val schema = mkSchema(
            """
            type Query { listings: Listings }
            type Listings @namespaceType { count: Int }
            """.trimIndent()
        )
        val resolvers = NamespaceTypeResolverModuleBootstrapper().fieldResolverExecutors(schema).toList()
        val coords = resolvers.map { it.first }.toSet()
        assertEquals(setOf(Coordinate("Query", "listings")), coords)
    }

    @Test
    fun `registers resolvers for nested namespace types`() {
        val schema = mkSchema(
            """
            type Listings @namespaceType { pricing: ListingsPricing }
            type ListingsPricing @namespaceType { currencyOptions: [String] }
            type Query { listings: Listings }
            """.trimIndent()
        )
        val resolvers = NamespaceTypeResolverModuleBootstrapper().fieldResolverExecutors(schema).toList()
        val coords = resolvers.map { it.first }.toSet()
        assertEquals(
            setOf(Coordinate("Query", "listings"), Coordinate("Listings", "pricing")),
            coords
        )
    }
}
