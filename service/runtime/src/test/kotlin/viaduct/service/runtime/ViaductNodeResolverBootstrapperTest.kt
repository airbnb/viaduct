package viaduct.service.runtime

import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.Coordinate
import viaduct.engine.api.FieldResolverExecutor
import viaduct.engine.api.ViaductSchema
import viaduct.service.runtime.noderesolvers.ViaductQueryNodeResolverModuleBootstrapper

class ViaductNodeResolverBootstrapperTest {
    companion object {
        fun mkSchema(sdl: String): ViaductSchema {
            val esdl = sdl + "\ninterface Node { id: ID! }"
            val s = UnExecutableSchemaGenerator.makeUnExecutableSchema(SchemaParser().parse(esdl))
            return ViaductSchema(s)
        }

        fun hasNodeResolver(resolvers: Iterable<Pair<Coordinate, FieldResolverExecutor>>) =
            resolvers.any { (c, r) ->
                c == Coordinate("Query", "node") && r == ViaductQueryNodeResolverModuleBootstrapper.queryNodeResolver
            }

        fun hasNodesResolver(resolvers: Iterable<Pair<Coordinate, FieldResolverExecutor>>) =
            resolvers.any { (c, r) ->
                c == Coordinate("Query", "nodes") && r == ViaductQueryNodeResolverModuleBootstrapper.queryNodesResolver
            }
    }

    @Test
    fun `No resolvers test`() {
        val s = mkSchema("type Query { i: Int }")
        val resolvers = ViaductQueryNodeResolverModuleBootstrapper().fieldResolverExecutors(s)
        assertEquals(0, resolvers.count(), "Should have length zero: $resolvers")
    }

    @Test
    fun `Query-node resolver test`() {
        val s = mkSchema("type Query { node(id: ID!): Node }")
        val resolvers = ViaductQueryNodeResolverModuleBootstrapper().fieldResolverExecutors(s)
        assertEquals(1, resolvers.count(), "Should have length one: $resolvers")
        assertTrue(hasNodeResolver(resolvers), "Should have node resolver: $resolvers")
    }

    @Test
    fun `Query-nodes resolver test`() {
        val s = mkSchema("type Query { nodes(id: [ID!]!): [Node]! }")
        val resolvers = ViaductQueryNodeResolverModuleBootstrapper().fieldResolverExecutors(s)
        assertEquals(1, resolvers.count(), "Should have length one: $resolvers")
        assertTrue(hasNodesResolver(resolvers), "Should have nodes resolver: $resolvers")
    }

    @Test
    fun `Both resolvers test`() {
        val s = mkSchema(
            """
            type Query {
              node(id: ID!): Node
              nodes(id: [ID!]!): [Node]!
            }"""
        )
        val resolvers = ViaductQueryNodeResolverModuleBootstrapper().fieldResolverExecutors(s)
        assertEquals(2, resolvers.count(), "Should have length two: $resolvers")
        assertTrue(hasNodeResolver(resolvers), "Should have nodes resolver: $resolvers")
        assertTrue(hasNodesResolver(resolvers), "Should have nodes resolver: $resolvers")
    }
}
