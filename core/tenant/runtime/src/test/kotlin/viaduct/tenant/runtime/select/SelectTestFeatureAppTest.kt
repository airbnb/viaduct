package viaduct.tenant.runtime.select

import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import viaduct.engine.api.ViaductSchema
import viaduct.graphql.utils.DefaultSchemaFactory
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase

class SelectTestFeatureAppTest : FeatureAppTestBase() {
    companion object {
        val schema: ViaductSchema by lazy {
            ViaductSchema(
                UnExecutableSchemaGenerator.makeUnExecutableSchema(
                    SchemaParser().parse(SelectTestFeatureAppTest().sdl).apply {
                        DefaultSchemaFactory.addDefaults(this)
                    }
                )
            )
        }
    }

    override var sdl =
        """
        #START_SCHEMA
        extend type Query {
          intField: Int
        }

        extend type Mutation {
          mutfield(x: Int!): String!
        }

        extend interface Node {
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
        #END_SCHEMA
        """
}
