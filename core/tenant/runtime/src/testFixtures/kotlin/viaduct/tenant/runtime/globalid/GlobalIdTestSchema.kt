package viaduct.tenant.runtime.globalid

import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase
import viaduct.engine.api.ViaductSchema
import viaduct.graphql.utils.DefaultSchemaFactory

@TestSchema(
    """
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

"""
)
object GlobalIdTestSchema : KotlinFeatureAppTestContractBase() {
    public override fun sdl(): String = super.sdl()

    val schema: ViaductSchema by lazy {
        ViaductSchema(
            UnExecutableSchemaGenerator.makeUnExecutableSchema(
                SchemaParser().parse(sdl()).apply {
                    DefaultSchemaFactory.addDefaults(this)
                }
            )
        )
    }
}
