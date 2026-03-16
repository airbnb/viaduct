package viaduct.tenant.runtime.globalid

import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import viaduct.engine.api.ViaductSchema
import viaduct.graphql.utils.DefaultSchemaFactory
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase

class GlobalIdFeatureAppTest : FeatureAppTestBase() {
    companion object {
        val schema: ViaductSchema by lazy {
            ViaductSchema(
                UnExecutableSchemaGenerator.makeUnExecutableSchema(
                    SchemaParser().parse(GlobalIdFeatureAppTest().sdl).apply {
                        DefaultSchemaFactory.addDefaults(this)
                    }
                )
            )
        }
    }

    override var sdl =
        """
        #START_SCHEMA
        type User implements Node {
          id: ID!
          name: String!
          email: String!
        }

        input CreateUserInput {
          id: ID!
          name: String!
          email: String!
        }

        extend type Query {
          user(id: ID!): User
        }

        extend type Mutation {
          createUser(input: CreateUserInput!): User
        }

        type Foo {
          id: ID!
          name: String!
        }

        type Under_Score_Type {
          someField(stringArg: String!, intArg: Int): String
        }
        #END_SCHEMA
        """
}
