package viaduct.engine.runtime.select.loader

import graphql.schema.GraphQLSchema
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator

object SelectTestSchemaFixture {
    val sdl: String = """
        type Query {
          node: Node
          intField: Int
        }
        
        type Mutation {
          mutfield(x: Int!): String!
        }
        
        interface Node {
          id: ID!
          nodeSelf: Node!
        }
        
        type Foo implements Node {
          id: ID!
          nodeSelf: Node!
          fooId: ID!
          fooSelf: Foo!
        }
        
        type Bar {
          bar: Bar!
        }
        
        union FooOrBar = Foo | Bar
    """.trimIndent()

    val schema: GraphQLSchema by lazy {
        UnExecutableSchemaGenerator.makeUnExecutableSchema(
            SchemaParser().parse(sdl)
        )
    }
}
